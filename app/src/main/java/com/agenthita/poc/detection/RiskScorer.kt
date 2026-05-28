package com.agenthita.poc.detection

import com.agenthita.poc.model.OnDeviceClassifier

/**
 * Orchestrates the two-layer detection pipeline:
 *   Layer 1 — Rule-based pattern matching (fast, explainable, always runs)
 *   Layer 2 — On-device ML classifier boost (adjusts rule score when model is loaded)
 *
 * Returns only results with RiskLevel > NONE.
 */
class RiskScorer(private val classifier: OnDeviceClassifier) {

    private val detectors: List<PatternMatcher> = listOf(
        SextortionDetector(),
        FinancialScamDetector(),
        GroomingDetector(),
        RomanceScamDetector(),
        IdentityPhishingDetector(),
        LuringDetector(),
        HarassmentDetector(),
        DisappearingMessageDetector()
    )

    /**
     * Score [text] with optional [context] — recent messages from the same conversation.
     * Context is prepended so detectors can catch patterns that span multiple messages
     * (e.g. trust-building followed by a threat, or escalating financial pressure).
     */
    fun score(text: String, context: List<String> = emptyList()): List<DetectionResult> {
        if (text.isBlank() || text.length < 10) return emptyList()

        val fullText = if (context.isEmpty()) text
                       else (context + text).joinToString(" ")

        return detectors
            .map { it.analyze(fullText) }
            .map { result ->
                if (result.riskLevel == RiskLevel.NONE) return@map result
                // Blend ML confidence (30% weight) into the rule-based score
                val mlBoost = classifier.getBoost(text, result.category)
                val boostedScore = (result.score + mlBoost * 0.3f).coerceIn(0f, 1f)
                result.copy(
                    score = boostedScore,
                    riskLevel = scoreToRiskLevel(boostedScore)
                )
            }
            .filter { it.riskLevel != RiskLevel.NONE }
    }

    /** Returns the single highest-risk result from a scored set. */
    fun highestRisk(results: List<DetectionResult>): DetectionResult? =
        results.maxByOrNull { it.score }

    private fun scoreToRiskLevel(score: Float) = when {
        score >= 0.55f -> RiskLevel.HIGH
        score >= 0.28f -> RiskLevel.MEDIUM
        score >= 0.12f -> RiskLevel.LOW
        else           -> RiskLevel.NONE
    }
}
