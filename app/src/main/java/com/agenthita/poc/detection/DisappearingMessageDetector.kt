package com.agenthita.poc.detection

/**
 * Detects when a contact activates disappearing/ephemeral message mode.
 *
 * This is a significant warning sign: someone enabling message deletion is
 * attempting to hide the conversation history from guardians, parents, or
 * the user themselves. Scored HIGH by default when confirmed activation is
 * detected — no multi-signal arc required.
 *
 * Signal sources:
 *   - System notification text from WhatsApp, Instagram, Snapchat, Telegram
 *   - In-conversation requests to switch to disappearing/ephemeral mode
 *   - "View once" image/video requests (hide evidence after viewing)
 */
class DisappearingMessageDetector : PatternMatcher {

    override val category = HarmCategory.DISAPPEARING_MESSAGES

    // System-generated notifications confirming activation (very high confidence)
    private val activationConfirmedSignals = listOf(
        "disappearing messages are on",
        "disappearing messages turned on",
        "turned on disappearing messages",
        "enabled disappearing messages",
        "messages will disappear after",
        "messages set to disappear",
        "vanish mode is on",
        "vanish mode turned on",
        "ephemeral mode is on",
        "messages are now set to disappear",
        "self-destruct timer",
        "timer has been set",
        "chats will delete after",
        "snaps and chats will delete"
    )

    // Someone requesting the user switch to disappearing mode (medium confidence)
    private val activationRequestSignals = listOf(
        "turn on disappearing messages",
        "enable disappearing messages",
        "switch to disappearing messages",
        "use disappearing messages",
        "let's use disappearing messages",
        "turn on vanish mode",
        "enable vanish mode",
        "switch to vanish mode",
        "delete messages automatically",
        "set messages to disappear",
        "use ephemeral chat",
        "let's chat on snapchat",
        "switch to snap so it deletes",
        "talk on snap instead",
        "message me on snap"
    )

    // View-once requests — used to send content that disappears after viewing
    private val viewOnceSignals = listOf(
        "view once",
        "open once",
        "opens once",
        "can only view once",
        "will disappear after you view",
        "disappears after viewing",
        "disappears once opened"
    )

    // Explicit hiding intent paired with messaging
    private val hidingIntentSignals = listOf(
        "so no one can see",
        "so it doesn't save",
        "won't be saved",
        "leaves no trace",
        "no record of this",
        "delete this after reading",
        "delete after you read",
        "don't screenshot",
        "don't save this"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = text.lowercase()
        val matches = mutableListOf<SignalMatch>()

        activationConfirmedSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("activation_confirmed", signal, 0.95f))
        }
        activationRequestSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("activation_request", signal, 0.65f))
        }
        viewOnceSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("view_once", signal, 0.75f))
        }
        hidingIntentSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("hiding_intent", signal, 0.60f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.DISAPPEARING_MESSAGES,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        // A single confirmed activation is enough — no arc bonus needed
        val hasConfirmed = matches.any { it.signal == "activation_confirmed" }
        if (hasConfirmed) return matches.maxOf { it.weight }
        return matches.sumOf { it.weight.toDouble() }.toFloat().coerceIn(0f, 1f)
    }

    private fun scoreToRiskLevel(score: Float) = when {
        score >= 0.90f -> RiskLevel.HIGH
        score >= 0.55f -> RiskLevel.MEDIUM
        score >= 0.12f -> RiskLevel.LOW
        else           -> RiskLevel.NONE
    }

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No disappearing message signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected disappearing/ephemeral message activity: ${types.joinToString(", ")}. " +
               "This contact may be attempting to hide conversation history."
    }
}
