package com.agenthita.app.detection

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
        "don't tell your friends", "dont tell your friends",
        // Isolation by discrediting others who might intervene
        "others would just judge", "they would just judge", "people would judge",
        "others would not understand", "they would not understand",
        "they don't understand what we have", "no one would understand",
        "they just want to control you", "they want to control us",
        // Protecting the relationship framing
        "come between what we have", "come between us",
        "try to come between us", "try to come between what we have",
        "get in the way of what we have", "destroy what we have",
        "ruin what we have", "ruin what we've built"
    )

    private val imageSignals = listOf(
        "send me a pic", "send me a photo", "send me a picture",
        "show me", "show yourself", "send nudes", "send photos",
        "take a photo for me", "send me something", "pic or video",
        "send a video", "facetime me", "video call me", "prove it with a photo",
        "i want to see you", "let me see you"
    )

    private val pressureSignals = listOf(
        // Love / care conditional pressure
        "if you loved me", "if you really loved me", "if you truly loved me",
        "if you really liked me", "if you really cared about me", "if you cared about me",
        "prove you love me", "prove you care", "prove it",
        // Trust manipulation
        "i thought you trusted me", "don't you trust me", "you said you trusted me",
        "i thought we trusted each other",
        // Dismissing hesitation / normalising
        "you're so boring", "you're being dramatic", "you're overreacting",
        "stop being so uptight", "just being shy", "you're just being shy",
        "don't be so shy", "why are you so shy", "stop being shy",
        "you're overthinking this", "don't overthink it", "stop overthinking",
        "it's not a big deal", "it's no big deal", "it's really not a big deal",
        "it's perfectly normal", "it's completely normal",
        // Normalisation by claiming universal behaviour
        "everyone does it", "everyone does this", "all couples do this",
        "this is what people do", "this is what couples do",
        "this is what people do when they care", "that's what people in love do",
        "people do this when they care about each other",
        // Sexual escalation framing
        "take things further", "willing to take things further",
        "ready to take things to the next level", "take this to the next level",
        "take our relationship further", "go further", "go all the way",
        // False consent assumption
        "you actually want this", "you know you want this",
        "i know you want this", "i know you want to",
        "you want this too", "i can tell you want this",
        "i know you actually want this",
        // Isolation / third-party dismissal
        "i won't tell anyone", "i just want to see you", "other girls do it"
    )

    private val threatSignals = listOf(
        "i will share", "i will send this", "everyone will see",
        "i will post", "i will show your friends", "i will expose",
        "you will regret", "you'll regret", "i have your photos", "i have pictures of you",
        "pay me or", "send more or", "do what i say or",
        "send money or", "send money else", "pay or else", "pay or i will",
        "or else i will tell", "else i will tell", "else i will expose",
        "or i will tell all", "i will tell all your friends", "i will tell all your family",
        "tell all your friends", "tell all your family", "tell everyone you know",
        "tell everyone", "i will ruin you", "i will tell everyone",
        "i'll share", "i'll post", "i'll expose", "i'll tell everyone", "i'll ruin",
        "i'll tell all your friends", "i'll tell all your family"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        secrecySignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("secrecy", signal, 0.6f))
        }
        imageSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("image_solicitation", signal, 0.8f))
        }
        pressureSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("pressure", signal, 0.7f))
        }
        threatSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("threat", signal, 1.0f))
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
        .replace("you're", "you are")
        .replace("they're", "they are")
        .replace("it's", "it is")
        .replace("that's", "that is")
        .replace("we've", "we have")
        .replace("you'd", "you would")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No sextortion signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected sextortion-related patterns: ${types.joinToString(", ")}. " +
               "This conversation shows signals associated with sexual manipulation or grooming."
    }
}
