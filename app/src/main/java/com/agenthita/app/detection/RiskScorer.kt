package com.agenthita.app.detection

import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.UserCategory
import com.agenthita.app.model.OnDeviceClassifier

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
    private val classifier: OnDeviceClassifier,
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
            val wordScore = WordLexicon.score(text, cat)
            val boost = if (wordScore > 0f) (wordScore * 0.5f).coerceAtMost(0.26f) else 0f
            val combined = (result.score + boost).coerceIn(0f, 1f)
            result.copy(score = combined, riskLevel = scoreToRiskLevel(combined))
        }

        // Context escalation (mild — cannot create new alerts)
        val contextScores: Map<HarmCategory, Float> = if (context.isEmpty()) emptyMap()
        else {
            val ctxText = context.joinToString(" ")
            detectors.associate { it.category to it.analyze(ctxText).score }
        }

        val escalated: Map<HarmCategory, DetectionResult> = wordBoosted.mapValues { (cat, result) ->
            if (result.riskLevel == RiskLevel.NONE) return@mapValues result
            val ctxBoost = ((contextScores[cat] ?: 0f) * 0.2f).coerceAtMost(0.15f)
            val s = (result.score + ctxBoost).coerceIn(0f, 1f)
            result.copy(score = s, riskLevel = scoreToRiskLevel(s))
        }

        // Layer 2: single Gemma multi-class inference. Gemma is invoked unconditionally
        // when loaded so it can rescue messages that both rule layers miss entirely.
        // ageHint biases Gemma toward age-relevant harm — a message that is borderline
        // for an adult may be clearly harmful for a child.
        val ageHint: String? = when (userCategory) {
            UserCategory.CHILD      -> "child under 13 years old"
            UserCategory.ADOLESCENT -> "adolescent aged 13 to 17"
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
            escalated
        } else {
            val (gemmaCat, gemmaSeverity) = gemmaResult
            escalated.mapValues { (cat, result) ->
                if (cat != gemmaCat) return@mapValues result
                mergeGemmaResult(result, gemmaSeverity)
            }
        }

        return merged.values.filter { it.riskLevel != RiskLevel.NONE }
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
        return when {
            gemmaSeverity == RiskLevel.NONE                               -> ruleResult
            // Rules NONE, Gemma MEDIUM/HIGH → raise to MEDIUM.
            // Gemma runs unconditionally so it can rescue messages patterns missed entirely.
            // Capped at MEDIUM to guard against hallucinations on genuinely safe messages.
            ruleLevel == RiskLevel.NONE && gemmaSeverity >= RiskLevel.MEDIUM ->
                ruleResult.copy(
                    riskLevel   = RiskLevel.MEDIUM,
                    score       = 0.35f,
                    explanation = "Detected by AI analysis — behavioural patterns in this conversation are consistent with ${ruleResult.category.name.lowercase().replace('_', ' ')}."
                )
            // Rules LOW, Gemma MEDIUM → MEDIUM
            ruleLevel == RiskLevel.LOW  && gemmaSeverity == RiskLevel.MEDIUM ->
                ruleResult.copy(riskLevel = RiskLevel.MEDIUM, score = 0.40f)
            // Rules LOW, Gemma HIGH → HIGH (corroborated)
            ruleLevel == RiskLevel.LOW  && gemmaSeverity == RiskLevel.HIGH ->
                ruleResult.copy(riskLevel = RiskLevel.HIGH,   score = 0.70f)
            // Rules MEDIUM, Gemma HIGH → HIGH
            ruleLevel == RiskLevel.MEDIUM && gemmaSeverity == RiskLevel.HIGH ->
                ruleResult.copy(riskLevel = RiskLevel.HIGH,   score = 0.80f)
            else                                                          -> ruleResult
        }
    }

    /** Returns the single highest-risk result from a scored set. */
    fun highestRisk(results: List<DetectionResult>): DetectionResult? =
        results.maxByOrNull { it.score }

    /**
     * Maps a raw score to a [RiskLevel] using thresholds from [RemoteConfig].
     *
     * Thresholds are lowered for younger protected persons so the same message
     * triggers a higher alert level for a child than for an adult.
     * Default values: CHILD HIGH≥0.40 | ADOLESCENT HIGH≥0.48 | ADULT HIGH≥0.55
     */
    private fun scoreToRiskLevel(score: Float): RiskLevel {
        val t = RemoteConfig.riskThresholds
        val band = when (userCategory) {
            UserCategory.CHILD      -> t.child
            UserCategory.ADOLESCENT -> t.adolescent
            else                    -> t.adult
        }
        return when {
            score >= band.high   -> RiskLevel.HIGH
            score >= band.medium -> RiskLevel.MEDIUM
            score >= band.low    -> RiskLevel.LOW
            else                 -> RiskLevel.NONE
        }
    }
}
