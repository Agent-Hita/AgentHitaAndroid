package com.agenthita.app.detection

import com.agenthita.app.consent.UserCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direction guard for Gemma-only IDENTITY_PHISHING — part of the voice message
 * detection fix (regression 2026-07-18): Gemma deterministically returned
 * IDENTITY_PHISHING HIGH for a harmless outgoing romanised-Telugu message
 * ("Aa phone charge ayipoyindi."), producing a MEDIUM alert with zero rule
 * corroboration. A contact who said nothing in the window cannot be phishing
 * the user, so a pure-Gemma IDENTITY_PHISHING verdict on an outgoing-only
 * window must not alert.
 *
 * The guard must NOT suppress:
 *  - windows containing an incoming [CONTACT] line (Gemma may know better than rules)
 *  - outgoing windows that carry rule signals (OTP/credential handover = HIGH)
 */
class GemmaDirectionGuardTest {

    /** Always-loaded classifier that mislabels everything as IDENTITY_PHISHING HIGH. */
    private class MisfiringClassifier : Classifier {
        override val isLoaded = true
        override fun classify(text: String, context: List<String>, ageHint: String?) =
            HarmCategory.IDENTITY_PHISHING to RiskLevel.HIGH
    }

    private fun scorer(category: UserCategory) = RiskScorer(MisfiringClassifier(), category)

    private fun List<DetectionResult>.maxLevel(): RiskLevel =
        maxOfOrNull { it.riskLevel } ?: RiskLevel.NONE

    @Test
    fun `outgoing-only benign window produces no alert despite Gemma misfire`() {
        // The message from the live false positive (recipient name removed).
        val results = scorer(UserCategory.VULNERABLE_ADULT)
            .score("[USER]: Aa phone charge ayipoyindi.")
        assertTrue(
            "Gemma-only IDENTITY_PHISHING on an outgoing-only window must not alert " +
            "(was ${results.maxLevel()})",
            results.maxLevel() < RiskLevel.MEDIUM
        )
    }

    @Test
    fun `outgoing-only window with prior context still produces no alert`() {
        // Context corroboration must not resurrect a guard-suppressed result.
        val context = listOf("[CONTACT]: hello, did you get my otp message yesterday")
        val results = scorer(UserCategory.VULNERABLE_ADULT)
            .score("[USER]: Aa phone charge ayipoyindi.", context)
        assertTrue(
            "Guard-suppressed result must not be context-escalated (was ${results.maxLevel()})",
            results.maxLevel() < RiskLevel.MEDIUM
        )
    }

    @Test
    fun `window with incoming line keeps the Gemma MEDIUM alert`() {
        val results = scorer(UserCategory.VULNERABLE_ADULT)
            .score("[CONTACT]: hello how are you doing today my friend")
        assertEquals(
            "Incoming windows must keep pure-Gemma MEDIUM (capped) alerts",
            RiskLevel.MEDIUM, results.maxLevel()
        )
    }

    @Test
    fun `outgoing OTP handover still flags HIGH — rules bypass the guard`() {
        val results = scorer(UserCategory.VULNERABLE_ADULT)
            .score("[USER]: otp is 482913 use it now")
        assertEquals(
            "Rule-detected outgoing OTP share must stay HIGH",
            RiskLevel.HIGH, results.maxLevel()
        )
    }
}
