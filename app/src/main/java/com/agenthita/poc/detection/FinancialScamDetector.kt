package com.agenthita.poc.detection

/**
 * Detects financial scam patterns: urgency + payment method + authority claim + isolation.
 * Covers romance scams, elder fraud, IRS/bank/government impersonation, crypto coercion,
 * phishing links, and fake bill/debt collection messages.
 */
class FinancialScamDetector : PatternMatcher {

    override val category = HarmCategory.FINANCIAL_SCAM

    private val urgencySignals = listOf(
        "right now", "immediately", "today only", "expires today",
        "limited time", "act now", "urgent", "time sensitive",
        "within 24 hours", "by end of day", "asap", "as soon as possible",
        "do not delay", "hurry", "last chance", "before it is too late",
        "account will be closed", "final notice", "immediate action",
        "action required", "respond immediately", "failure to respond",
        "your account will be", "must be paid", "pay immediately",
        "overdue", "past due", "payment overdue"
    )

    private val paymentSignals = listOf(
        // Explicit payment methods
        "gift card", "gift cards", "google play card", "apple gift card",
        "itunes card", "steam card", "crypto", "bitcoin", "ethereum",
        "wire transfer", "western union", "moneygram", "money gram",
        "zelle", "cash app", "venmo", "paypal", "send money",
        "send me the money", "send the money", "wire funds", "transfer funds",
        "bank transfer", "routing number", "pay me",
        // Bill / balance language
        "pay your bill", "pay the bill", "your bill", "bill payment",
        "outstanding balance", "amount due", "amount owed",
        "make a payment", "complete payment", "payment required",
        "pay now", "pay online", "settle your", "clear your balance",
        "click to pay", "tap to pay", "unpaid balance", "balance due",
        "your payment", "process your payment", "submit payment"
    )

    private val authoritySignals = listOf(
        // Federal agencies
        "irs", "internal revenue service", "social security administration",
        "medicare", "medicaid", "fbi", "federal bureau", "dea ", "dhs ",
        "department of homeland", "customs and border",
        // Generic government impersonation
        "department of", "dept of", "dept.", "division of", "bureau of",
        "ministry of", "office of", "commissioner of",
        "government agency", "federal agency", "state agency",
        "official notice", "government notice", "legal notice",
        "licensing", "license board", "license renewal",
        "tax authority", "revenue service", "tax office",
        // Financial institution impersonation
        "your bank", "your account is", "account suspended",
        "account locked", "account has been", "bank security",
        // Legal threat language
        "police", "warrant", "arrest", "legal action",
        "you owe", "back taxes", "debt collector",
        "collection agency", "lawsuit filed", "court order",
        "failure to comply", "law enforcement"
    )

    private val isolationSignals = listOf(
        "do not tell your family", "do not tell anyone",
        "keep this between us", "do not discuss this with anyone",
        "this is confidential", "do not inform anyone",
        "do not mention this to", "your family will not understand",
        "they will only make it worse", "do not contact your bank",
        "do not speak to anyone"
    )

    // URLs or link patterns commonly used in phishing / smishing
    private val suspiciousLinkSignals = listOf(
        "http://", "https://",
        "click here", "click the link", "tap the link",
        "follow this link", "visit this link", "open this link",
        ".com/payment", ".net/payment", ".org/payment",
        ".com/pay", ".net/pay", ".com/verify", ".com/account",
        ".com/secure", ".com/update", "bit.ly/", "tinyurl.com/",
        "t.co/", "goo.gl/"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        urgencySignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("urgency", signal, 0.5f))
        }
        paymentSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("payment_method", signal, 0.8f))
        }
        authoritySignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("authority_claim", signal, 0.7f))
        }
        isolationSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("isolation", signal, 0.6f))
        }
        suspiciousLinkSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("suspicious_link", signal, 0.6f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.FINANCIAL_SCAM,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()

        // Authority + payment = canonical scam combo
        val comboBonus = if (
            uniqueTypes.contains("authority_claim") && uniqueTypes.contains("payment_method")
        ) 0.3f else 0f

        // Suspicious link + authority or payment = phishing/smishing pattern
        val linkBonus = if (
            uniqueTypes.contains("suspicious_link") &&
            (uniqueTypes.contains("authority_claim") || uniqueTypes.contains("payment_method"))
        ) 0.2f else 0f

        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + comboBonus + linkBonus).coerceIn(0f, 1f)
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

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No financial scam signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected financial scam indicators: ${types.joinToString(", ")}. " +
               "This conversation shows patterns associated with scams or financial coercion."
    }
}
