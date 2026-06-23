package com.agenthita.app.detection

import com.agenthita.app.consent.UserCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RiskScorer.mergeGemmaResult] behaviour — specifically the adult threshold
 * change that requires a Gemma HIGH verdict before a pure-AI result produces a
 * notification for self-protecting adults.
 */
class RiskScorerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Fake classifier that returns a fixed result, or null when not given one. */
    private class FakeClassifier(
        private val fixedResult: Pair<HarmCategory, RiskLevel>?
    ) : Classifier {
        override val isLoaded = fixedResult != null
        override fun classify(text: String, context: List<String>, ageHint: String?) = fixedResult
    }

    private fun scorer(
        gemmaResult: Pair<HarmCategory, RiskLevel>?,
        userCategory: UserCategory
    ) = RiskScorer(FakeClassifier(gemmaResult), userCategory)

    // Benign text: no rule signals, intentionally vague so only Gemma could flag it
    private val safeText = "Good morning! Happy Yoga Day everyone, see you at the party."

    // ── Pure-Gemma MEDIUM: adult threshold change ─────────────────────────────

    @Test
    fun `pure Gemma MEDIUM on self-protecting adult produces NONE`() {
        val result = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.MEDIUM,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(safeText)

        assertTrue("Expected no results for adult+Gemma-MEDIUM", result.isEmpty())
    }

    @Test
    fun `pure Gemma MEDIUM on vulnerable adult produces MEDIUM`() {
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.MEDIUM,
            userCategory = UserCategory.VULNERABLE_ADULT
        ).score(safeText)

        val grooming = results.find { it.category == HarmCategory.GROOMING }
        assertTrue("Expected MEDIUM result for vulnerable adult", grooming != null)
        assertEquals(RiskLevel.MEDIUM, grooming!!.riskLevel)
    }

    @Test
    fun `pure Gemma MEDIUM on adolescent produces MEDIUM`() {
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.MEDIUM,
            userCategory = UserCategory.ADOLESCENT
        ).score(safeText)

        val grooming = results.find { it.category == HarmCategory.GROOMING }
        assertTrue("Expected MEDIUM result for adolescent", grooming != null)
        assertEquals(RiskLevel.MEDIUM, grooming!!.riskLevel)
    }

    @Test
    fun `pure Gemma MEDIUM on child produces MEDIUM`() {
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.MEDIUM,
            userCategory = UserCategory.CHILD
        ).score(safeText)

        val grooming = results.find { it.category == HarmCategory.GROOMING }
        assertTrue("Expected MEDIUM result for child", grooming != null)
        assertEquals(RiskLevel.MEDIUM, grooming!!.riskLevel)
    }

    // ── Pure-Gemma HIGH: all categories raise to MEDIUM (capped) ─────────────

    @Test
    fun `pure Gemma HIGH on self-protecting adult raises to MEDIUM`() {
        val results = scorer(
            gemmaResult  = HarmCategory.SEXTORTION to RiskLevel.HIGH,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(safeText)

        val sextortion = results.find { it.category == HarmCategory.SEXTORTION }
        assertTrue("Expected a result for adult+Gemma-HIGH", sextortion != null)
        assertEquals(RiskLevel.MEDIUM, sextortion!!.riskLevel)
    }

    @Test
    fun `pure Gemma HIGH on child raises to MEDIUM`() {
        val results = scorer(
            gemmaResult  = HarmCategory.SEXTORTION to RiskLevel.HIGH,
            userCategory = UserCategory.CHILD
        ).score(safeText)

        val sextortion = results.find { it.category == HarmCategory.SEXTORTION }
        assertEquals(RiskLevel.MEDIUM, sextortion!!.riskLevel)
    }

    // ── Gemma NONE: rule result is unchanged ──────────────────────────────────

    @Test
    fun `Gemma NONE leaves rules-only NONE result empty`() {
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.NONE,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(safeText)

        assertTrue("Expected no results when Gemma returns NONE", results.isEmpty())
    }

    // ── No Gemma (stub classifier) ────────────────────────────────────────────

    @Test
    fun `no classifier leaves safe message as NONE`() {
        val results = scorer(
            gemmaResult  = null,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(safeText)

        assertTrue("Expected no results with no classifier on safe message", results.isEmpty())
    }

    // ── Rule + Gemma corroboration paths ──────────────────────────────────────

    @Test
    fun `rules LOW plus Gemma MEDIUM raises to MEDIUM`() {
        // Two boundary signals, same type: (0.8+0.8)/3 = 0.53 → LOW for adult (≥0.40, <0.70)
        val lowRuleText = "Are you home alone? Come over when your parents are not home."
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.MEDIUM,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(lowRuleText)

        val grooming = results.find { it.category == HarmCategory.GROOMING }
        assertTrue("Expected MEDIUM after rule LOW + Gemma MEDIUM", grooming != null)
        assertTrue(grooming!!.riskLevel >= RiskLevel.MEDIUM)
    }

    @Test
    fun `rules LOW plus Gemma HIGH raises to HIGH`() {
        // Two boundary signals, same type: (0.8+0.8)/3 = 0.53 → LOW for adult (≥0.40, <0.70)
        val lowRuleText = "Are you home alone? Come over when your parents are not home."
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.HIGH,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(lowRuleText)

        val grooming = results.find { it.category == HarmCategory.GROOMING }
        assertEquals(RiskLevel.HIGH, grooming!!.riskLevel)
    }

    @Test
    fun `rules MEDIUM plus Gemma HIGH raises to HIGH`() {
        // Isolation + escalation, two arc types: (0.7+0.9)/3 + 0.18 = 0.71 → MEDIUM for adult (≥0.70, <0.85)
        val mediumRuleText = "Don't tell anyone. You'll enjoy it."
        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.HIGH,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(mediumRuleText)

        val grooming = results.find { it.category == HarmCategory.GROOMING }
        assertEquals(RiskLevel.HIGH, grooming!!.riskLevel)
    }

    // ── Adolescent harassment word dampening ─────────────────────────────────

    @Test
    fun `hyperbolic teen gaming language does not flag harassment for adolescent`() {
        // "kill", "destroy", "attack" all appear — all are in the HARASSMENT lexicon —
        // but this is clearly gaming/social banter, not a threat
        val gamingText = "omg we absolutely killed it, destroyed the other team, full attack mode all game"
        val results = scorer(
            gemmaResult  = null,
            userCategory = UserCategory.ADOLESCENT
        ).score(gamingText)

        val harassment = results.find { it.category == HarmCategory.HARASSMENT }
        assertTrue("Gaming banter must not flag harassment for adolescent", harassment == null)
    }

    @Test
    fun `same hyperbolic language still scores for adult (undampened)`() {
        // For adults the full weight applies — enough lexicon hits should reach LOW at minimum.
        // We just verify the dampening isn't applied when it shouldn't be.
        val gamingText = "omg we absolutely killed it, destroyed the other team, full attack mode all game"
        val adultResult = scorer(
            gemmaResult  = null,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(gamingText)
        val adolescentResult = scorer(
            gemmaResult  = null,
            userCategory = UserCategory.ADOLESCENT
        ).score(gamingText)

        // The adult score must be >= the adolescent score (dampening only reduces, never inflates)
        val adultScore = adultResult.find { it.category == HarmCategory.HARASSMENT }?.score ?: 0f
        val adolescentScore = adolescentResult.find { it.category == HarmCategory.HARASSMENT }?.score ?: 0f
        assertTrue("Adult harassment word score must be >= adolescent dampened score", adultScore >= adolescentScore)
    }

    // ── False-positive regression ─────────────────────────────────────────────

    @Test
    fun `yoga day greeting with no Gemma scores NONE for adult`() {
        val message = "Vahini + Phani and Anshu, are giving party to friends. " +
            "Good morning Andii, Happy Yoga Day everyone. " +
            "replied back with Good morning andi. belated happy yoga day"

        val results = scorer(
            gemmaResult  = null,   // rules-only, as if Gemma is not installed
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(message)

        assertTrue("Yoga Day greeting must not flag in rules-only mode", results.isEmpty())
    }

    @Test
    fun `yoga day greeting with Gemma MEDIUM scores NONE for adult`() {
        val message = "Vahini + Phani and Anshu, are giving party to friends. " +
            "Good morning Andii, Happy Yoga Day everyone. " +
            "replied back with Good morning andi. belated happy yoga day"

        val results = scorer(
            gemmaResult  = HarmCategory.GROOMING to RiskLevel.MEDIUM,
            userCategory = UserCategory.SELF_PROTECTING_ADULT
        ).score(message)

        assertTrue("Yoga Day greeting must not flag even if Gemma says MEDIUM for adult", results.isEmpty())
    }
}
