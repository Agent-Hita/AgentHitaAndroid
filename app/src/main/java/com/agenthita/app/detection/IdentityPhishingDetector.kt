package com.agenthita.app.detection

/**
 * Detects identity phishing: harvesting OTPs, passwords, personal identifiers,
 * or financial credentials via social engineering or fake security alerts.
 */
class IdentityPhishingDetector : PatternMatcher {

    override val category = HarmCategory.IDENTITY_PHISHING

    private val codeRequestSignals = listOf(
        "verification code", "verify your code", "otp", "one-time password",
        "one time password", "confirmation code", "security code",
        "the code we sent", "6-digit code", "6 digit code",
        "enter the code", "share the code", "send me the code",
        "what is the code", "give me the code", "read me the code",
        "the sms code", "text message code", "code sent to your phone",
        "authentication code", "2fa code", "two factor code"
    )

    private val credentialRequestSignals = listOf(
        "your password", "your pin", "your login",
        "account details", "username and password", "sign in details",
        "login credentials", "your account password", "reset your password",
        "enter your password", "provide your password", "confirm your password",
        "your passcode", "your access code", "your security pin"
    )

    private val personalInfoSignals = listOf(
        "social security", "ssn", "social security number",
        "date of birth", "your date of birth", "your dob",
        "your address", "home address", "full address",
        "bank account number", "account number", "routing number",
        "credit card number", "card number", "card details",
        "cvv", "expiry date", "expiration date", "card expiry",
        "full name and address", "national id", "passport number",
        "driver's license", "drivers license", "mother's maiden name",
        "security question", "identity verification"
    )

    private val urgencySignals = listOf(
        "verify now", "confirm immediately", "your account will be suspended",
        "login attempt detected", "unusual activity detected",
        "suspicious activity", "unauthorized access", "account locked",
        "account will be closed", "immediate verification required",
        "respond within", "failure to verify", "account at risk",
        "security alert", "your account has been compromised",
        "action required immediately", "your account has been flagged"
    )

    private val fakeSenderSignals = listOf(
        "from your bank", "bank security team", "fraud department",
        "from google", "google security", "google account team",
        "from apple", "apple support", "apple id team",
        "from amazon", "amazon security", "amazon account",
        "from paypal", "paypal security", "paypal support",
        "from microsoft", "microsoft support", "windows security",
        "your service provider", "technical support", "customer support team",
        "official security notice", "automated security system"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        codeRequestSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("code_request", signal, 0.9f))
        }
        credentialRequestSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("credential_request", signal, 0.9f))
        }
        personalInfoSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("personal_info_request", signal, 0.8f))
        }
        urgencySignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("urgency", signal, 0.5f))
        }
        fakeSenderSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("fake_sender", signal, 0.6f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.IDENTITY_PHISHING,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Any direct request for a code or credential is immediately high-signal
        val directHarvestBonus = if (
            uniqueTypes.contains("code_request") || uniqueTypes.contains("credential_request")
        ) 0.25f else 0f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + directHarvestBonus).coerceIn(0f, 1f)
    }

    private fun scoreToRiskLevel(score: Float) = when {
        score >= 0.55f -> RiskLevel.HIGH
        score >= 0.28f -> RiskLevel.MEDIUM
        score >= 0.12f -> RiskLevel.LOW
        else           -> RiskLevel.NONE
    }

    private fun normalizeContractions(text: String) = text
        .replace("don't", "do not")
        .replace("won't", "will not")
        .replace("can't", "cannot")
        .replace("you'll", "you will")
        .replace("i've", "i have")
        .replace("i'm", "i am")
        .replace("your'e", "you are")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No phishing signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected identity phishing indicators: ${types.joinToString(", ")}. " +
               "This message appears designed to harvest personal credentials or sensitive information."
    }
}
