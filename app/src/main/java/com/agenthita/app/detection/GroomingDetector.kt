package com.agenthita.app.detection

/**
 * Detects grooming patterns: age probing → trust building → isolation from caregivers
 * → boundary testing. Particularly dangerous across conversation history — benefits
 * from ConversationBuffer context window.
 */
class GroomingDetector : PatternMatcher {

    override val category = HarmCategory.GROOMING

    private val ageProbingSignals = listOf(
        "how old are you", "what grade are you in", "are you in school",
        "you seem so young", "you look young", "how young are you",
        "you're so mature for your age", "mature for your age",
        "are you still in school", "what school do you go to",
        "are you a student", "how old do you have to be"
    )

    private val trustBuildingSignals = listOf(
        "you're so special", "you're special to me", "you understand me",
        "i've never felt this way", "no one understands me like you",
        "you're not like other girls", "you're not like other people",
        "i can talk to you about anything", "you can tell me anything",
        "i'll always be here for you", "i care about you so much",
        "you can trust me completely", "i would never hurt you",
        "i'll protect you", "i'll take care of you", "you mean everything to me"
    )

    private val isolationSignals = listOf(
        "don't tell your parents", "do not tell your parents",
        "your parents wouldn't understand", "they won't understand",
        "they'll try to stop us", "your friends won't get it",
        "they're just jealous", "your family is controlling you",
        "no one needs to know about us", "this is just between us",
        "keep our relationship private", "our special secret",
        "your parents are too strict", "they don't want you to be happy"
    )

    private val boundaryTestingSignals = listOf(
        "are you home alone", "is anyone home with you",
        "have you ever kissed anyone", "do you have a boyfriend",
        "are you a virgin", "have you ever done anything like this",
        "what do you look like", "what are you wearing",
        "show me your room", "come meet me", "let's meet up",
        "i want to see you in person", "let's meet somewhere private",
        "can you sneak out", "come over when your parents aren't home"
    )

    private val escalationSignals = listOf(
        "i can teach you", "let me show you", "i'll show you what it's like",
        "you'll enjoy it", "it'll feel good", "everyone does this",
        "it's normal at your age", "it's natural", "don't be scared",
        "i won't tell anyone", "you'll like it", "you're ready for this",
        "you're old enough", "it's not a big deal"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        ageProbingSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("age_probing", signal, 0.5f))
        }
        trustBuildingSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("trust_building", signal, 0.5f))
        }
        isolationSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("isolation", signal, 0.7f))
        }
        boundaryTestingSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("boundary_testing", signal, 0.8f))
        }
        escalationSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("escalation", signal, 0.9f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.GROOMING,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Multi-stage grooming arc is significantly more dangerous than any single signal
        val arcBonus = (uniqueTypes.size - 1) * 0.18f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + arcBonus).coerceIn(0f, 1f)
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
        .replace("i've", "i have")
        .replace("i'm", "i am")
        .replace("you're", "you are")
        .replace("they're", "they are")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No grooming signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected grooming-related patterns: ${types.joinToString(", ")}. " +
               "This conversation shows signals associated with predatory trust-building or manipulation of a vulnerable person."
    }
}
