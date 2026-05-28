package com.agenthita.poc.detection

/**
 * Detects luring patterns: fake job/modelling offers used to harvest personal
 * information, photos, or lure victims to unsafe locations. Common entry point
 * for trafficking and exploitation.
 */
class LuringDetector : PatternMatcher {

    override val category = HarmCategory.LURING

    private val opportunityBaitSignals = listOf(
        "great opportunity", "exclusive opportunity", "amazing opportunity",
        "we found your profile", "we came across your profile",
        "you've been selected", "you have been chosen", "you were recommended",
        "perfect for you", "you'd be perfect for this", "ideal candidate",
        "work from home", "work from anywhere", "earn from home",
        "high-paying job", "high paying job", "well-paying position",
        "no experience needed", "no experience required", "training provided",
        "part time work", "flexible hours", "set your own hours"
    )

    private val appearanceFocusSignals = listOf(
        "you're very attractive", "you have the right look",
        "you have a great look", "you have a model's look",
        "modeling opportunity", "modelling opportunity",
        "acting opportunity", "acting job", "film opportunity",
        "influencer program", "brand ambassador", "content creator opportunity",
        "photoshoot", "photo shoot", "we'd like to photograph you",
        "talent agency", "casting call", "audition opportunity",
        "promotional work", "promo work", "glamour work"
    )

    private val travelLureSignals = listOf(
        "all expenses paid", "expenses covered", "fully paid",
        "free accommodation", "accommodation included",
        "flights included", "flights covered", "we'll cover your travel",
        "travel with us", "come to our office", "meet us at",
        "come for an interview", "in-person meeting required",
        "we'll pick you up", "i'll pick you up", "i can come get you",
        "meet me somewhere private", "somewhere quiet", "somewhere discreet"
    )

    private val infoHarvestSignals = listOf(
        "send your photos", "send full body photo", "send a photo of yourself",
        "send us your pictures", "we need your photos first",
        "send your id", "copy of your id", "passport copy",
        "send your personal details", "send your home address",
        "your address for our records", "bank details for payment",
        "national insurance", "social security number",
        "we need to verify your identity", "identity check required"
    )

    private val tooGoodToBetrueSignals = listOf(
        "earn thousands", "make thousands a week",
        "earn 5000", "earn $5000", "make 5000 a week",
        "easy money", "quick money", "fast cash",
        "no skills required", "anyone can do this",
        "guaranteed income", "guaranteed earnings",
        "we pay cash", "cash in hand", "paid daily",
        "double your income", "ten times your salary"
    )

    override fun analyze(text: String): DetectionResult {
        val lower = normalizeContractions(text.lowercase())
        val matches = mutableListOf<SignalMatch>()

        opportunityBaitSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("opportunity_bait", signal, 0.5f))
        }
        appearanceFocusSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("appearance_focus", signal, 0.7f))
        }
        travelLureSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("travel_lure", signal, 0.8f))
        }
        infoHarvestSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("info_harvest", signal, 0.8f))
        }
        tooGoodToBetrueSignals.forEach { signal ->
            if (lower.contains(signal)) matches.add(SignalMatch("too_good_to_be_true", signal, 0.5f))
        }

        val score = computeScore(matches)
        return DetectionResult(
            category = HarmCategory.LURING,
            riskLevel = scoreToRiskLevel(score),
            score = score,
            signals = matches,
            explanation = buildExplanation(matches)
        )
    }

    private fun computeScore(matches: List<SignalMatch>): Float {
        if (matches.isEmpty()) return 0f
        val uniqueTypes = matches.map { it.signal }.toSet()
        // Requesting travel + personal info is the most dangerous combo
        val comboBonus = if (
            uniqueTypes.contains("travel_lure") && uniqueTypes.contains("info_harvest")
        ) 0.3f else if (
            uniqueTypes.contains("appearance_focus") && uniqueTypes.contains("info_harvest")
        ) 0.2f else 0f
        val rawScore = matches.sumOf { it.weight.toDouble() }.toFloat() / 3f
        return (rawScore + comboBonus).coerceIn(0f, 1f)
    }

    private fun scoreToRiskLevel(score: Float) = when {
        score >= 0.55f -> RiskLevel.HIGH
        score >= 0.28f -> RiskLevel.MEDIUM
        score >= 0.12f -> RiskLevel.LOW
        else           -> RiskLevel.NONE
    }

    private fun normalizeContractions(text: String) = text
        .replace("i'll", "i will")
        .replace("we'll", "we will")
        .replace("don't", "do not")
        .replace("won't", "will not")
        .replace("you'll", "you will")
        .replace("i've", "i have")
        .replace("i'm", "i am")
        .replace("you'd", "you would")

    private fun buildExplanation(matches: List<SignalMatch>): String {
        if (matches.isEmpty()) return "No luring signals detected."
        val types = matches.map { it.signal }.toSet()
        return "Detected luring indicators: ${types.joinToString(", ")}. " +
               "This message shows patterns associated with fake job or modelling offers used to exploit or traffic victims."
    }
}
