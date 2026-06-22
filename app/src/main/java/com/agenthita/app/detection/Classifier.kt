package com.agenthita.app.detection

/**
 * Minimal interface over the on-device ML classifier consumed by [RiskScorer].
 * Extracted so [RiskScorer] can be unit-tested with a pure-JVM fake.
 */
interface Classifier {
    val isLoaded: Boolean
    fun classify(text: String, context: List<String>, ageHint: String?): Pair<HarmCategory, RiskLevel>?
}
