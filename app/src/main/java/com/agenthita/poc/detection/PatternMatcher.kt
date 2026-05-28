package com.agenthita.poc.detection

enum class HarmCategory {
    SEXTORTION,
    FINANCIAL_SCAM,
    GROOMING,
    ROMANCE_SCAM,
    IDENTITY_PHISHING,
    LURING,
    HARASSMENT
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
    val score: Float,          // Combined score [0.0, 1.0]
    val signals: List<SignalMatch>,
    val explanation: String    // Human-readable explanation for the flag
)

interface PatternMatcher {
    val category: HarmCategory
    fun analyze(text: String): DetectionResult
}
