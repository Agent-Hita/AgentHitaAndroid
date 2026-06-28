package com.agenthita.app.detection

enum class HarmCategory {
    SEXTORTION,
    FINANCIAL_SCAM,
    GROOMING,
    ROMANCE_SCAM,
    IDENTITY_PHISHING,
    LURING,
    HARASSMENT,
    DISAPPEARING_MESSAGES
}

enum class RiskLevel {
    NONE, LOW, MEDIUM, HIGH
}

data class SignalMatch(
    val signal: String,       // Signal type name (e.g. "secrecy", "urgency")
    val matchedPhrase: String, // The phrase that triggered the signal
    val weight: Float          // Signal weight [0.0, 1.0]
)

data class DetectionResult(
    val category: HarmCategory,
    val riskLevel: RiskLevel,
    val score: Float,              // Combined score [0.0, 1.0]
    val signals: List<SignalMatch>,
    val explanation: String,       // Human-readable explanation for the flag
    // True when the HIGH result is driven by cross-message context and the prior
    // context messages already scored HIGH by rules — meaning a guardian alert was
    // likely already sent for those messages. The local notification still shows;
    // only the guardian email alert is suppressed.
    val suppressGuardianAlert: Boolean = false
)

interface PatternMatcher {
    val category: HarmCategory
    fun analyze(text: String): DetectionResult
}
