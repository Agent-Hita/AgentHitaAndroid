package com.agenthita.app.detection

/**
 * Detects identity phishing: harvesting OTPs, passwords, personal identifiers,
 * or financial credentials via social engineering or fake security alerts.
 */
class IdentityPhishingDetector : PatternMatcher {

    override val category = HarmCategory.IDENTITY_PHISHING

    // Explicit demands for a code — always a phishing signal, even when the same
    // window also contains a legitimate OTP-delivery message.
    private val codeDemandSignals = listOf(
        "share the code", "send me the code", "give me the code",
        "what is the code", "read me the code", "tell me the code",
        "forward the code", "share the otp", "send me the otp",
        "give me the otp", "what is the otp", "read me the otp",
        "tell me the otp", "forward the otp", "send the otp", "share your otp"
    )

    // Generic OTP vocabulary — legitimate OTP deliveries (bank/app messages that
    // carry the actual code) use these same words, so they only count as a
    // code_request outside recognised delivery lines.
    private val codeMentionSignals = listOf(
        "verification code", "verify your code", "otp", "one-time password",
        "one time password", "confirmation code", "security code",
        "the code we sent", "6-digit code", "6 digit code",
        "enter the code", "the sms code", "text message code",
        "code sent to your phone", "authentication code", "2fa code",
        "two factor code"
    )

    // A one-time code as it appears in messages: 4–8 digits, or 3+3 split by a
    // dash/space (WhatsApp style "482-913"). Word boundaries keep 10-digit phone
    // numbers and longer IDs from matching.
    private val otpCodeRegex = Regex("""\b\d{3}[-\s]\d{3}\b|\b\d{4,8}\b""")

    // Phrasing of an automated OTP delivery. Checked together with otpCodeRegex on
    // incoming lines: "Your OTP is 482913, valid for 10 min. Do not share."
    // (Contractions are already normalized, so "don't share" arrives as "do not share".)
    private val otpDeliveryPhrases = listOf(
        "is your otp", "your otp is", "otp for", "otp is", "is the otp",
        "is your one time password", "is your one-time password",
        "is your verification code", "verification code is",
        "is your code", "your code is", "use code",
        "do not share", "never share", "valid for", "expires in", "will expire"
    )

    // Keywords that mark digits in a [USER] message as a one-time code being shared.
    private val otpShareKeywords = listOf(
        "otp", "one time password", "one-time password", "verification code",
        "security code", "authentication code", "confirmation code",
        "2fa code", "login code", "code is"
    )

    // Codes people legitimately share in chat — postal PINs (6 digits in India),
    // coupons, tracking numbers. Suppresses otp_shared, not the request signals.
    private val benignCodeContexts = listOf(
        "pin code", "pincode", "postal code", "zip code", "area code",
        "promo code", "coupon code", "discount code", "referral code",
        "tracking", "order number"
    )

    // A safety warning about codes/credentials, not a request for them:
    // "Do not share your OTP/CVV with anyone", "Bank staff will never ask for
    // your password". (Contractions are normalized before matching.)
    private val shareWarningPhrases = listOf(
        "do not share", "never share", "not to share", "should not share",
        "avoid sharing", "will never ask", "would never ask",
        "do not disclose", "never disclose"
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

    // Phrases that indicate the sender is PROVIDING personal information, not requesting it.
    // When present (and no code/credential request signals also fire), personalInfoSignals
    // are suppressed — "here is your SSN" must not flag the same as "send me your SSN".
    private val providingContextSignals = listOf(
        "here is", "here's", "here are",
        "i am sharing", "i am sending",
        "i have sent", "i have attached", "i have included", "i have forwarded",
        "please find",
        "for your reference", "for your information", "fyi",
        "as requested", "as discussed",
        "attached is", "attaching",
        "i am providing", "i am giving you"
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

        // Lines carry the [USER]:/[CONTACT]: direction prefixes added by the service.
        // Incoming OTP deliveries (real code + delivery phrasing) and share-warnings
        // ("do not share your OTP with anyone") reuse the same vocabulary as harvest
        // requests, so those lines are excluded from request matching — merely
        // receiving an OTP or a safety warning must never flag.
        val lines = lower.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val requestText = lines
            .filterNot { isIncomingOtpDelivery(it) || isShareWarning(it) }
            .joinToString("\n")

        codeDemandSignals.forEach { signal ->
            if (requestText.contains(normalizeContractions(signal))) matches.add(SignalMatch("code_request", signal, 0.9f))
        }
        codeMentionSignals.forEach { signal ->
            if (requestText.contains(normalizeContractions(signal))) matches.add(SignalMatch("code_request", signal, 0.9f))
        }
        credentialRequestSignals.forEach { signal ->
            if (requestText.contains(normalizeContractions(signal))) matches.add(SignalMatch("credential_request", signal, 0.9f))
        }
        // Suppress personal-info signals when the sender is clearly providing information
        // rather than requesting it, and no explicit code/credential request also fired.
        // A scam message that says "here is your SSN, now send me your password" still
        // triggers because hasRequestSignals catches the credential request.
        val isProvidingContext = providingContextSignals.any { lower.contains(it) }
        val hasRequestSignals = matches.any { it.signal == "code_request" || it.signal == "credential_request" }
        if (!isProvidingContext || hasRequestSignals) {
            personalInfoSignals.forEach { signal ->
                if (requestText.contains(normalizeContractions(signal))) matches.add(SignalMatch("personal_info_request", signal, 0.8f))
            }
        }
        urgencySignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("urgency", signal, 0.5f))
        }
        fakeSenderSignals.forEach { signal ->
            if (lower.contains(normalizeContractions(signal))) matches.add(SignalMatch("fake_sender", signal, 0.6f))
        }

        // A [USER] line carrying an actual one-time code is the moment the scam
        // completes — flag the share itself, not just the request that preceded it.
        // This includes the user forwarding a delivery message verbatim.
        val hasCodeRequest = matches.any { it.signal == "code_request" }
        if (lines.any { isUserOtpShare(it, hasCodeRequest) }) {
            matches.add(SignalMatch("otp_shared", "one-time passcode shared by user", 0.9f))
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

    /**
     * True for an incoming (non-[USER]) line that is a legitimate automated OTP
     * delivery: it contains the code itself plus delivery phrasing. Lines are
     * lowercase and contraction-normalized by the time this runs.
     */
    private fun isIncomingOtpDelivery(line: String): Boolean =
        !line.startsWith("[user]:") &&
        otpCodeRegex.containsMatchIn(line) &&
        otpDeliveryPhrases.any { line.contains(it) }

    /** True for a line that warns against sharing codes/credentials rather than requesting them. */
    private fun isShareWarning(line: String): Boolean =
        shareWarningPhrases.any { line.contains(it) }

    /**
     * True for a [USER] line that shares an actual one-time code — either the
     * message names it as an OTP/verification code, or it is essentially just the
     * bare digits while a code request appears elsewhere in the same window.
     */
    private fun isUserOtpShare(line: String, requestElsewhere: Boolean): Boolean {
        if (!line.startsWith("[user]:")) return false
        val body = line.removePrefix("[user]:").trim()
        if (!otpCodeRegex.containsMatchIn(body)) return false
        if (benignCodeContexts.any { body.contains(it) }) return false
        if (otpShareKeywords.any { body.contains(it) }) return true
        return requestElsewhere && body.length <= 24
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Any direct request for a code or credential — or the user actually
        // handing a code over — is immediately high-signal
        val directHarvestBonus = if (
            uniqueTypes.contains("code_request") || uniqueTypes.contains("credential_request") ||
            uniqueTypes.contains("otp_shared")
        ) 0.25f else 0f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        // A code actually leaving the user's device is the completion of the scam —
        // floor at 0.87 so it clears the HIGH threshold (0.85) even for adults.
        val otpSharedFloor = if (uniqueTypes.contains("otp_shared")) 0.87f else 0f
        return (rawScore + directHarvestBonus).coerceAtLeast(otpSharedFloor).coerceIn(0f, 1f)
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
        if (types.contains("otp_shared")) {
            return "A one-time passcode (OTP) appears to have been shared in this conversation. " +
                   "Legitimate services never ask anyone to share an OTP — sharing one can let a " +
                   "scammer take over accounts or approve payments."
        }
        return "Detected identity phishing indicators: ${types.joinToString(", ")}. " +
               "This message appears designed to harvest personal credentials or sensitive information."
    }
}
