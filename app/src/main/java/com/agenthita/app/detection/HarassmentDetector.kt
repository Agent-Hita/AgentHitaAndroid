package com.agenthita.app.detection

/**
 * Detects harassment, stalking, and coercive control patterns: explicit threats,
 * doxxing, persistent unwanted contact, reputation threats, and controlling behaviour.
 */
class HarassmentDetector : PatternMatcher {

    override val category = HarmCategory.HARASSMENT

    private val explicitThreatSignals = listOf(
        "i will hurt you", "i will harm you", "i'm going to hurt you",
        "you'll pay for this", "you will pay for this", "you'll regret this",
        "watch your back", "i'm coming for you", "you'll be sorry",
        "i will make you suffer", "i will destroy you", "i'm going to destroy you",
        "you'll wish you hadn't", "this isn't over", "you haven't heard the last of me",
        "i know what i'll do to you", "you're going to get what's coming",
        // Coercive ultimatum patterns (do X or I will retaliate)
        "or else i will", "or i will tell", "else i will tell",
        "or i will expose", "else i will expose",
        "or i will share", "else i will share",
        "send money or", "send money else", "pay me or", "pay or else"
    )

    private val doxxingSignals = listOf(
        "i know where you live", "i know your address", "i found your home",
        "i know where you work", "i know your workplace", "i found out where you are",
        "i know your family", "i know your parents", "i know your kids",
        "i've been watching you", "i've been following you",
        "i know your daily routine", "i know your schedule",
        "i'll find you", "i know how to find you", "i can track you",
        "i can see you right now", "i'm outside your house", "i'm outside your home",
        "i'm outside right now watching", "i'm standing outside", "i'm near your house",
        "i'm parked outside", "i can see your lights"
    )

    private val persistentContactSignals = listOf(
        "why aren't you answering", "why aren't you replying",
        "i've called you so many times", "i've messaged you so many times",
        "you can't ignore me", "you can't keep ignoring me",
        "i'll keep messaging until you respond", "answer me now",
        "you have to respond", "you owe me a response",
        "i'll call you until you pick up", "i won't stop until you talk to me",
        "you can't hide from me", "i won't leave you alone until",
        "stop ignoring me", "stop avoiding me"
    )

    private val reputationThreatSignals = listOf(
        "i'll tell everyone", "i will tell everyone", "i'll tell your family",
        "i'll tell your parents", "i'll tell your friends",
        "i'll tell all your friends", "i will tell all your friends",
        "tell all your friends", "tell all your family",
        "tell everyone you know", "i will tell everyone you know",
        "i'll show your family", "i'll show everyone",
        "i'll ruin your reputation", "i will ruin you",
        "everyone will know", "everyone will find out",
        "i'll post this everywhere", "i'll put this online",
        "i'll make sure everyone knows", "i'll go public",
        "i'll report you", "i'll expose you",
        "will expose you to", "will tell your contacts"
    )

    private val coerciveControlSignals = listOf(
        "you belong to me", "you're mine", "you'll always be mine",
        "you can't leave me", "you're not allowed to leave",
        "if you leave me", "if you leave i will",
        "i'll hurt myself if you leave", "i'll kill myself if you go",
        "look what you made me do", "you made me do this",
        "this is your fault", "you drove me to this",
        "no one else will want you", "you're nothing without me",
        "you'll never find anyone better", "i own you",
        "you do what i say", "you'll do as i tell you"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        explicitThreatSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("explicit_threat", signal, 1.0f))
        }
        doxxingSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("doxxing", signal, 0.9f))
        }
        persistentContactSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("persistent_contact", signal, 0.5f))
        }
        reputationThreatSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("reputation_threat", signal, 0.8f))
        }
        coerciveControlSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("coercive_control", signal, 0.8f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.HARASSMENT,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Any physical threat combined with doxxing is immediately critical
        val threatComboBonus = if (
            uniqueTypes.contains("explicit_threat") && uniqueTypes.contains("doxxing")
        ) 0.35f else 0f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + threatComboBonus).coerceIn(0f, 1f)
    }

    private fun scoreToRiskLevel(score: Float) = when {
        score >= 0.55f -> RiskLevel.HIGH
        score >= 0.28f -> RiskLevel.MEDIUM
        score >= 0.12f -> RiskLevel.LOW
        else           -> RiskLevel.NONE
    }

    private fun normalizeContractions(text: String) = text
        .replace("i'll", "i will")
        .replace("i've", "i have")
        .replace("i'm", "i am")
        .replace("you'll", "you will")
        .replace("you're", "you are")
        .replace("won't", "will not")
        .replace("don't", "do not")
        .replace("can't", "cannot")
        .replace("i'd", "i would")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No harassment signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected harassment indicators: ${types.joinToString(", ")}. " +
               "This conversation shows patterns associated with threatening behaviour, stalking, or coercive control."
    }
}
