package com.agenthita.app.detection

/**
 * Detects romance scam patterns: rapid love-bombing → fake crisis abroad →
 * escalating financial requests. Often unfolds over days/weeks — conversation
 * buffer context significantly improves accuracy.
 */
class RomanceScamDetector : PatternMatcher {

    override val category = HarmCategory.ROMANCE_SCAM

    private val loveBombingSignals = listOf(
        "you're the one", "you complete me", "i've never met anyone like you",
        "you're my soulmate", "we were meant to be", "destiny brought us together",
        "i love you already", "i fell for you instantly", "i can't stop thinking about you",
        "you're perfect", "you're my everything", "i've been looking for someone like you",
        "i want to spend my life with you", "marry me", "you're my dream come true",
        "i've never felt this way about anyone", "you're unlike anyone i've ever met"
    )

    private val fakeProfileSignals = listOf(
        "i'm working overseas", "i'm working abroad", "i'm on an oil rig",
        "i'm in the military", "i'm deployed", "i'm on a mission",
        "i'm a surgeon working in", "i'm a doctor in", "i'm an engineer abroad",
        "i'm currently stationed", "i'm on a peacekeeping mission",
        "i work on a ship", "i'm offshore", "i'm on assignment",
        "i'll visit you when i get back", "as soon as my contract ends",
        "once i finish this project", "i'm stuck here right now"
    )

    private val isolationSignals = listOf(
        "don't tell your family about us", "your friends won't understand",
        "they'll try to keep us apart", "they're jealous of what we have",
        "i only need you", "you only need me", "no one else matters",
        "don't listen to them", "they don't know us like we know each other"
    )

    private val crisisSignals = listOf(
        "i'm in trouble", "i need your help urgently", "i'm stuck",
        "my account is frozen", "i can't access my funds",
        "there's been an emergency", "i had an accident",
        "i'm in the hospital", "my wallet was stolen",
        "i'm stranded", "i can't get home", "i need a loan",
        "stuck at customs", "customs is holding my package",
        "i'll pay you back as soon as", "i'll transfer it all back",
        "i just need a small amount", "just this once i promise"
    )

    private val moneyRequestSignals = listOf(
        "send me money", "transfer money", "can you send",
        "wire me", "i need funds", "lend me", "loan me",
        "gift card", "bitcoin", "crypto", "western union",
        "money transfer", "pay for my", "cover the cost",
        "i'll repay you", "i'll pay you back double",
        "investment opportunity", "double your money",
        "i have a business deal", "i need your bank details"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        loveBombingSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("love_bombing", signal, 0.5f))
        }
        fakeProfileSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("fake_profile", signal, 0.7f))
        }
        isolationSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("isolation", signal, 0.6f))
        }
        crisisSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("fake_crisis", signal, 0.8f))
        }
        moneyRequestSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("money_request", signal, 0.9f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.ROMANCE_SCAM,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Love bombing + money request is the canonical romance scam combo
        val comboBonus = if (
            uniqueTypes.contains("money_request") &&
            (uniqueTypes.contains("love_bombing") || uniqueTypes.contains("fake_profile"))
        ) 0.3f else 0f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + comboBonus).coerceIn(0f, 1f)
    }

    private fun scoreToRiskLevel(score: Float) = when {
        score >= 0.55f -> RiskLevel.HIGH
        score >= 0.28f -> RiskLevel.MEDIUM
        score >= 0.12f -> RiskLevel.LOW
        else           -> RiskLevel.NONE
    }

    private fun normalizeContractions(text: String) = text
        .replace("i'll", "i will")
        .replace("don't", "do not")
        .replace("won't", "will not")
        .replace("can't", "cannot")
        .replace("you'll", "you will")
        .replace("i've", "i have")
        .replace("i'm", "i am")
        .replace("i'd", "i would")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No romance scam signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected romance scam indicators: ${types.joinToString(", ")}. " +
               "This conversation shows patterns consistent with a fake romantic relationship designed to extract money."
    }
}
