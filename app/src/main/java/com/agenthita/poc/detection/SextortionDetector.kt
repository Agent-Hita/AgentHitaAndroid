package com.agenthita.poc.detection

/**
 * Detects sextortion patterns: secrecy conditioning → image solicitation →
 * escalating pressure → threat. High severity, high value for youth protection.
 */
class SextortionDetector : PatternMatcher {

    override val category = HarmCategory.SEXTORTION

    private val secrecySignals = listOf(
        "don't tell anyone", "dont tell anyone", "keep this between us",
        "this is our secret", "just between you and me", "don't show anyone",
        "dont show anyone", "our little secret", "nobody needs to know",
        "keep it private", "don't tell your parents", "dont tell your parents",
        "don't tell your friends", "dont tell your friends"
    )

    private val imageSignals = listOf(
        "send me a pic", "send me a photo", "send me a picture",
        "show me", "show yourself", "send nudes", "send photos",
        "take a photo for me", "send me something", "pic or video",
        "send a video", "facetime me", "video call me", "prove it with a photo",
        "i want to see you", "let me see you"
    )

    private val pressureSignals = listOf(
        "if you loved me", "if you really liked me", "prove it",
        "i thought you trusted me", "don't you trust me", "you're so boring",
        "everyone does it", "it's not a big deal", "you're being dramatic",
        "why are you so shy", "i won't tell anyone", "i just want to see you",
        "you're overreacting", "stop being so uptight", "other girls do it"
    )

    private val threatSignals = listOf(
        "i will share", "i will send this", "everyone will see",
        "i will post", "i will show your friends", "i will expose",
        "you will regret", "you'll regret", "i have your photos", "i have pictures of you",
        "pay me or", "send more or", "do what i say or",
        "tell everyone", "i will ruin you", "i will tell everyone",
        "i'll share", "i'll post", "i'll expose", "i'll tell everyone", "i'll ruin"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        secrecySignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("secrecy", signal, 0.6f))
        }
        imageSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("image_solicitation", signal, 0.8f))
        }
        pressureSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("pressure", signal, 0.7f))
        }
        threatSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("threat", signal, 1.0f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.SEXTORTION,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Bonus for multi-type escalation pattern (secrecy + image + pressure = grooming arc)
        val escalationBonus = (uniqueTypes.size - 1) * 0.15f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + escalationBonus).coerceIn(0f, 1f)
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
        .replace("they'll", "they will")
        .replace("you've", "you have")
        .replace("i've", "i have")
        .replace("i'm", "i am")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No sextortion signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected sextortion-related patterns: ${types.joinToString(", ")}. " +
               "This conversation shows signals associated with sexual manipulation or grooming."
    }
}
