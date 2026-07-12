package com.agenthita.app.detection

import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.UserCategory

/**
 * Orchestrates the two-layer detection pipeline:
 *   Layer 1 — Rule-based pattern matching (fast, explainable, always runs)
 *   Layer 2 — On-device ML classifier boost (adjusts rule score when model is loaded)
 *
 * [userCategory] lowers risk thresholds for younger protected persons so the same
 * message flags at a higher level for a child than for an adult.
 *
 * Returns only results with RiskLevel > NONE.
 */
class RiskScorer(
    private val classifier: Classifier,
    private val userCategory: UserCategory = UserCategory.SELF_PROTECTING_ADULT
) {

    private val detectors: List<PatternMatcher> = listOf(
        SextortionDetector(),
        FinancialScamDetector(),
        GroomingDetector(),
        RomanceScamDetector(),
        IdentityPhishingDetector(),
        LuringDetector(),
        HarassmentDetector()
    )

    /**
     * Score [text] (the latest message only) with optional [context] — prior messages
     * from the same conversation.
     *
     * Three-layer pipeline:
     *   Layer 1a — Phrase detectors: exact multi-word pattern matching (high precision).
     *   Layer 1b — Word lexicon: order-independent per-word scoring. Catches colloquial
     *              phrasing that phrase patterns miss. Word scores are capped so they
     *              cannot reach MEDIUM+ without at least some phrase corroboration.
     *   Layer 2  — Single Gemma multi-class inference: semantic understanding. Runs
     *              unconditionally so it can rescue messages that both rule layers miss.
     *              Pure-Gemma detections are capped at MEDIUM to guard hallucination.
     */
    fun score(text: String, context: List<String> = emptyList()): List<DetectionResult> {
        if (text.isBlank() || text.length < 10) return emptyList()

        // Layer 1a: phrase detectors on latest message only
        val ruleResults: Map<HarmCategory, DetectionResult> =
            detectors.associate { it.category to it.analyze(text) }

        // Layer 1b: word lexicon — order-independent, fills gaps between phrase patterns
        // Word boost is capped at 0.26 so word scoring alone stays below MEDIUM (0.28).
        // Combined with even a single phrase hit it can push over the threshold.
        // Always calls scoreToRiskLevel (even with zero word boost) so age-adjusted
        // thresholds apply to pure-phrase results too.
        val wordBoosted: Map<HarmCategory, DetectionResult> = ruleResults.mapValues { (cat, result) ->
            val rawWordScore = WordLexicon.score(text, cat)
            // Teens use "kill", "destroy", "attack" etc. in gaming/social contexts constantly.
            // Dampen HARASSMENT word-lexicon weight for younger users so accumulation of
            // hyperbolic vocabulary doesn't cross the threshold without a phrase-level signal.
            val wordScore = if (
                cat == HarmCategory.HARASSMENT &&
                (userCategory == UserCategory.ADOLESCENT || userCategory == UserCategory.CHILD)
            ) rawWordScore * 0.4f else rawWordScore
            val boost = if (wordScore > 0f) (wordScore * 0.5f).coerceAtMost(0.26f) else 0f
            val combined = (result.score + boost).coerceIn(0f, 1f)
            result.copy(score = combined, riskLevel = scoreToRiskLevel(combined))
        }

        // Location/address disclosure by a child or adolescent.
        // A minor sharing their whereabouts is a grooming or luring risk signal
        // regardless of whether the contact's request appears in the current window.
        // Score is raised to at least 0.70 so it clears the MEDIUM threshold for both
        // CHILD (≥0.62) and ADOLESCENT (≥0.65) users even with no other phrase signal.
        val disclosureBoosted: Map<HarmCategory, DetectionResult> = if (
            (userCategory == UserCategory.CHILD || userCategory == UserCategory.ADOLESCENT) &&
            containsUserAddressDisclosure(text)
        ) {
            wordBoosted.mapValues { (cat, result) ->
                if (cat != HarmCategory.GROOMING && cat != HarmCategory.LURING) return@mapValues result
                val boosted = (result.score + 0.40f).coerceAtLeast(0.70f).coerceIn(0f, 1f)
                result.copy(
                    score     = boosted,
                    riskLevel = scoreToRiskLevel(boosted),
                    signals   = result.signals + SignalMatch(
                        signal        = "location_disclosure",
                        matchedPhrase = "Minor sharing address or location",
                        weight        = 0.80f
                    )
                )
            }
        } else {
            wordBoosted
        }

        // Context escalation (mild — cannot create new alerts)
        // Run detectors on the full context text so we have both the score for boosting
        // and the riskLevel for detecting whether prior messages already scored HIGH.
        val contextResults: Map<HarmCategory, DetectionResult> = if (context.isEmpty()) emptyMap()
        else {
            // Newline-joined so each message stays its own line — the detectors'
            // per-line direction handling ([USER]:/[CONTACT]:) needs line boundaries.
            val ctxText = context.joinToString("\n")
            detectors.associate { it.category to it.analyze(ctxText) }
        }

        val escalated: Map<HarmCategory, DetectionResult> = disclosureBoosted.mapValues { (cat, result) ->
            if (result.riskLevel == RiskLevel.NONE) return@mapValues result
            val ctxBoost = ((contextResults[cat]?.score ?: 0f) * 0.2f).coerceAtMost(0.15f)
            val s = (result.score + ctxBoost).coerceIn(0f, 1f)
            result.copy(score = s, riskLevel = scoreToRiskLevel(s))
        }

        // OTP handed over across windows: the contact's code request was in a prior
        // (already seen) message and the user's new message is just the bare digits.
        // The bare code alone carries no rule signal, so — unlike the mild context
        // escalation above, which never creates new alerts — this deliberately
        // elevates to HIGH: a one-time passcode leaving the user's device in reply
        // to a request is the completion of the scam. In-window shares (request and
        // code in the same block) are already caught by IdentityPhishingDetector.
        val ctxCodeRequest = contextResults[HarmCategory.IDENTITY_PHISHING]
            ?.signals?.any { it.signal == "code_request" } == true
        val otpEscalated: Map<HarmCategory, DetectionResult> =
            if (!ctxCodeRequest || !userSharesBareCode(text)) escalated
            else escalated.mapValues { (cat, result) ->
                if (cat != HarmCategory.IDENTITY_PHISHING) return@mapValues result
                if (result.signals.any { it.signal == "otp_shared" }) return@mapValues result
                val boosted = maxOf(result.score, 0.87f)
                result.copy(
                    score       = boosted,
                    riskLevel   = scoreToRiskLevel(boosted),
                    signals     = result.signals + SignalMatch(
                        signal        = "otp_shared",
                        matchedPhrase = "One-time passcode shared after a code request",
                        weight        = 0.87f
                    ),
                    explanation = "A one-time passcode (OTP) was shared after the contact asked for it. " +
                                  "Legitimate services never ask anyone to share an OTP — sharing one can " +
                                  "let a scammer take over accounts or approve payments."
                )
            }

        // Layer 2: single Gemma multi-class inference. Gemma is invoked unconditionally
        // when loaded so it can rescue messages that both rule layers miss entirely.
        // ageHint biases Gemma toward age-relevant harm — a message that is borderline
        // for an adult may be clearly harmful for a child.
        val ageHint: String? = when (userCategory) {
            UserCategory.CHILD      -> "child under 13 years old"
            UserCategory.ADOLESCENT -> "adolescent aged 13 to 17; note that casual profanity and hyperbolic language (e.g. 'this kills', 'I'll destroy you at the game') are developmentally normal for this age group and should not alone be treated as harassment — only flag if there are specific targeting signals such as explicit threats, doxxing, or coercive control"
            else                    -> null
        }
        val gemmaResult: Pair<HarmCategory, RiskLevel>? = if (classifier.isLoaded) {
            classifier.classify(text, context, ageHint).also { result ->
                android.util.Log.d("RiskScorer", if (result != null) "Gemma → ${result.first} ${result.second}" else "Gemma → NONE")
            }
        } else {
            android.util.Log.d("RiskScorer", "Gemma not loaded — rules-only")
            null
        }

        // Merge Gemma into rule results
        val merged: Map<HarmCategory, DetectionResult> = if (gemmaResult == null) {
            otpEscalated
        } else {
            val (gemmaCat, gemmaSeverity) = gemmaResult
            otpEscalated.mapValues { (cat, result) ->
                if (cat != gemmaCat) return@mapValues result
                mergeGemmaResult(result, gemmaSeverity)
            }
        }

        // Post-Gemma context escalation for cross-message behavioural arcs.
        //
        // The existing mergeGemmaResult step already elevates NONE-rule + Gemma HIGH to
        // MEDIUM for all users. This step goes further when context (prior seen messages)
        // also shows a signal for the same category — i.e. Gemma's HIGH is corroborated
        // by the conversation history, not just the current message in isolation.
        //
        // child / adolescent / vulnerable adult → elevate to HIGH.
        // self-protecting adult → stays at MEDIUM (adult HIGH requires rule corroboration).
        //
        // suppressGuardianAlert is set when the context itself already scores HIGH by
        // rules, meaning a guardian alert was likely already sent for those prior messages.
        // The local notification still fires; only the external guardian email is suppressed.
        val finalResults: Map<HarmCategory, DetectionResult> = if (
            gemmaResult == null || gemmaResult.second != RiskLevel.HIGH
        ) {
            merged
        } else {
            val gemmaCat = gemmaResult.first
            val isVulnerable = userCategory != UserCategory.SELF_PROTECTING_ADULT
            merged.mapValues { (cat, result) ->
                if (cat != gemmaCat) return@mapValues result
                val ctxScore = contextResults[cat]?.score ?: 0f
                if (ctxScore <= 0f) return@mapValues result  // no context signal — Gemma alone
                val ctxAlreadyHigh = contextResults[cat]?.riskLevel == RiskLevel.HIGH
                if (!isVulnerable) {
                    // Adults: context corroborates but doesn't push past MEDIUM without rules.
                    // Still flag suppress so we don't re-alert on prior-HIGH context.
                    return@mapValues if (ctxAlreadyHigh) result.copy(suppressGuardianAlert = true) else result
                }
                // Vulnerable users: context + Gemma HIGH together warrant HIGH.
                result.copy(
                    riskLevel = RiskLevel.HIGH,
                    score = maxOf(result.score, 0.87f),
                    signals = result.signals + SignalMatch(
                        signal       = "ai_context_pattern",
                        matchedPhrase = "Cross-message behavioural pattern",
                        weight       = 0.87f
                    ),
                    suppressGuardianAlert = ctxAlreadyHigh
                )
            }
        }

        return finalResults.values.filter { it.riskLevel != RiskLevel.NONE }
    }

    /**
     * Merges a Gemma severity verdict into an existing rule-based result.
     *
     * Rules provide grounding; Gemma provides semantic understanding. The merge
     * rules prevent pure-Gemma hallucinations from producing HIGH alerts while
     * still allowing Gemma to rescue categories that rules missed or under-scored.
     */
    private fun mergeGemmaResult(ruleResult: DetectionResult, gemmaSeverity: RiskLevel): DetectionResult {
        val ruleLevel = ruleResult.riskLevel
        val isVulnerable = userCategory != UserCategory.SELF_PROTECTING_ADULT
        return when {
            gemmaSeverity == RiskLevel.NONE                               -> ruleResult
            // Rules NONE, Gemma MEDIUM → raise to MEDIUM for children, adolescents, and
            // vulnerable adults only. For self-protecting adults, Gemma MEDIUM alone is too
            // noisy when extraction produces low-quality text (layout changes, community
            // broadcasts, UI chrome) and requires Gemma HIGH to produce an alert.
            ruleLevel == RiskLevel.NONE && gemmaSeverity == RiskLevel.MEDIUM && isVulnerable ->
                ruleResult.copy(
                    riskLevel   = RiskLevel.MEDIUM,
                    score       = 0.65f,
                    signals     = listOf(SignalMatch("ai_analysis", "AI-detected behavioural pattern", 0.65f)),
                    explanation = "Detected by AI analysis — behavioural patterns in this conversation are consistent with ${ruleResult.category.name.lowercase().replace('_', ' ')}."
                )
            // Rules NONE, Gemma HIGH → raise to MEDIUM (all categories). Requires Gemma HIGH
            // for self-protecting adults so a single ambiguous message cannot produce an alert
            // with zero rule corroboration.
            ruleLevel == RiskLevel.NONE && gemmaSeverity == RiskLevel.HIGH ->
                ruleResult.copy(
                    riskLevel   = RiskLevel.MEDIUM,
                    score       = 0.65f,
                    signals     = listOf(SignalMatch("ai_analysis", "AI-detected behavioural pattern", 0.65f)),
                    explanation = "Detected by AI analysis — behavioural patterns in this conversation are consistent with ${ruleResult.category.name.lowercase().replace('_', ' ')}."
                )
            // Rules LOW, Gemma MEDIUM → MEDIUM
            ruleLevel == RiskLevel.LOW  && gemmaSeverity == RiskLevel.MEDIUM ->
                ruleResult.copy(riskLevel = RiskLevel.MEDIUM, score = 0.65f,
                    signals = ruleResult.signals.ifEmpty { listOf(SignalMatch("ai_analysis", "AI-detected behavioural pattern", 0.65f)) })
            // Rules LOW, Gemma HIGH → HIGH (corroborated)
            ruleLevel == RiskLevel.LOW  && gemmaSeverity == RiskLevel.HIGH ->
                ruleResult.copy(riskLevel = RiskLevel.HIGH,   score = 0.82f,
                    signals = ruleResult.signals.ifEmpty { listOf(SignalMatch("ai_analysis", "AI-detected behavioural pattern", 0.82f)) })
            // Rules MEDIUM, Gemma HIGH → HIGH
            ruleLevel == RiskLevel.MEDIUM && gemmaSeverity == RiskLevel.HIGH ->
                ruleResult.copy(riskLevel = RiskLevel.HIGH,   score = 0.83f)
            else                                                          -> ruleResult
        }
    }

    /** Returns the single highest-risk result from a scored set. */
    fun highestRisk(results: List<DetectionResult>): DetectionResult? =
        results.maxByOrNull { it.score }

    /**
     * Returns true when a [USER] message in [text] is essentially just a numeric
     * one-time code — 4–8 digits (or WhatsApp-style "482-913") in a short message.
     * Only consulted when prior context contains a code request from the contact;
     * a bare number with no such context never creates an alert.
     */
    private fun userSharesBareCode(text: String): Boolean {
        val codeRegex = Regex("""\b\d{3}[-\s]\d{3}\b|\b\d{4,8}\b""")
        // Codes people legitimately share in chat — postal PINs (6 digits in India),
        // coupons, tracking numbers.
        val benignCodeContexts = listOf(
            "pin code", "pincode", "postal code", "zip code", "area code",
            "promo code", "coupon code", "discount code", "referral code",
            "tracking", "order number"
        )
        return text.lines().any { line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("[USER]:", ignoreCase = true)) return@any false
            val body = trimmed.substring("[USER]:".length).trim().lowercase()
            body.length <= 24 &&
                codeRegex.containsMatchIn(body) &&
                benignCodeContexts.none { body.contains(it) }
        }
    }

    /**
     * Returns true when a [USER] message in [text] contains an explicit address
     * or location disclosure — e.g. "I live at 14 Oak St" or "my address is…".
     * Phone-number-only sharing is intentionally excluded.
     * Only called for CHILD / ADOLESCENT users.
     */
    private fun containsUserAddressDisclosure(text: String): Boolean {
        val userText = text.lines()
            .filter { it.trimStart().startsWith("[USER]:", ignoreCase = true) }
            .joinToString(" ")
            .lowercase()
        if (userText.isBlank()) return false

        val locationPhrases = listOf(
            "i live at", "my address is", "my house is at",
            "i stay at", "we live at", "my home is at",
            "come to my house", "come to my place", "come to my home",
            "my place is at", "here's my address", "here is my address",
            "my home address is", "my location is"
        )
        if (locationPhrases.any { userText.contains(it) }) return true

        // Structural house address: one or more digits + words + recognised street suffix
        val streetAddress = Regex(
            """\b\d+\s+\w[\w\s]{0,30}(street|avenue|road|boulevard|lane|drive|court|way|place|circle|terrace|\bst\b|\bave\b|\brd\b|\bblvd\b|\bln\b|\bdr\b|\bct\b|\bpl\b)"""
        )
        return streetAddress.containsMatchIn(userText)
    }

    /**
     * Maps a raw score to a [RiskLevel] using thresholds from [RemoteConfig].
     *
     * Thresholds are lowered for younger protected persons so the same message
     * triggers a higher alert level for a child than for an adult.
     * Default values: CHILD HIGH≥0.80 MEDIUM≥0.62 | ADOLESCENT HIGH≥0.82 MEDIUM≥0.65 |
     *                 ADULT/VULNERABLE_ADULT HIGH≥0.85 MEDIUM≥0.70
     */
    private fun scoreToRiskLevel(score: Float): RiskLevel {
        val t = RemoteConfig.riskThresholds
        val band = when (userCategory) {
            UserCategory.CHILD            -> t.child
            UserCategory.ADOLESCENT       -> t.adolescent
            UserCategory.VULNERABLE_ADULT -> t.vulnerableAdult
            else                          -> t.adult
        }
        return when {
            score >= band.high   -> RiskLevel.HIGH
            score >= band.medium -> RiskLevel.MEDIUM
            score >= band.low    -> RiskLevel.LOW
            else                 -> RiskLevel.NONE
        }
    }
}
