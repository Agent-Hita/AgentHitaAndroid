package com.agenthita.app.detection

import com.agenthita.app.consent.UserCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the tiered credential-share policy through [RiskScorer]:
 *
 *  - [USER] sharing a high-sensitivity credential (card number, SSN,
 *    password/PIN/CVV) UNPROMPTED → MEDIUM for every category: a user-facing
 *    warning, never a guardian email. The request may have happened on a voice
 *    call we cannot see, so no visible request is required to warn.
 *  - The same share WITH a visible request (same window or prior context) →
 *    HIGH: request + handover is the completion of the scam, like an OTP.
 *  - Benign credential shares (wifi/streaming/door) and [CONTACT] self-shares
 *    stay NONE.
 */
class CredentialShareTest {

    private class FakeClassifier : Classifier {
        override val isLoaded = false
        override fun classify(text: String, context: List<String>, ageHint: String?) = null
    }

    private fun scorer(category: UserCategory) = RiskScorer(FakeClassifier(), category)

    private fun List<DetectionResult>.credentialShared() =
        firstOrNull {
            it.category == HarmCategory.IDENTITY_PHISHING &&
            it.signals.any { s -> s.signal == "credential_shared" }
        }

    // ── Unprompted share → MEDIUM warning, never HIGH ────────────────────────

    @Test
    fun `unprompted card number share warns at MEDIUM for adult`() {
        val results = scorer(UserCategory.SELF_PROTECTING_ADULT)
            .score("[USER]: use my card 4532 7712 0034 9821 for the booking")
        val result = results.credentialShared()
        assertNotNull("Unprompted card share must warn", result)
        assertEquals(RiskLevel.MEDIUM, result!!.riskLevel)
    }

    @Test
    fun `unprompted password share warns at MEDIUM for vulnerable adult`() {
        val results = scorer(UserCategory.VULNERABLE_ADULT)
            .score("[USER]: my password is sunset99 please handle it")
        val result = results.credentialShared()
        assertNotNull("Unprompted password share must warn", result)
        assertEquals(RiskLevel.MEDIUM, result!!.riskLevel)
    }

    @Test
    fun `unprompted card share stays below HIGH for child`() {
        // MEDIUM = local warning; HIGH would email the guardian about what may
        // be a benign transfer — the costlier error when nothing was requested.
        val results = scorer(UserCategory.CHILD)
            .score("[USER]: my card number is 4532 7712 0034 9821 okay")
        val result = results.credentialShared()
        assertNotNull("Unprompted card share must warn for child", result)
        assertEquals(RiskLevel.MEDIUM, result!!.riskLevel)
    }

    // ── Requested share → HIGH for everyone ──────────────────────────────────

    @Test
    fun `card share after in-window request flags HIGH for adult`() {
        val results = scorer(UserCategory.SELF_PROTECTING_ADULT).score(
            "[CONTACT]: to reverse the charge send me your card number and cvv\n" +
            "[USER]: 4532 7712 0034 9821 cvv 344"
        )
        val result = results.credentialShared()
        assertNotNull("Requested card share must flag", result)
        assertEquals(RiskLevel.HIGH, result!!.riskLevel)
    }

    @Test
    fun `card share after request in prior context flags HIGH for child`() {
        val context = listOf(
            "[CONTACT]: i am from your bank, send me your card number and cvv to stop the fraud"
        )
        val results = scorer(UserCategory.CHILD)
            .score("[USER]: 4532 7712 0034 9821 please help", context)
        val result = results.credentialShared()
        assertNotNull("Cross-window requested card share must flag", result)
        assertEquals(RiskLevel.HIGH, result!!.riskLevel)
    }

    // ── Benign shares stay silent ────────────────────────────────────────────

    @Test
    fun `wifi password share produces no credential signal`() {
        val results = scorer(UserCategory.CHILD)
            .score("[USER]: the wifi password is sunshine123 enjoy")
        assertTrue("Wifi password must not warn", results.credentialShared() == null)
    }

    @Test
    fun `postal pin code share produces no credential signal`() {
        val results = scorer(UserCategory.SELF_PROTECTING_ADULT)
            .score("[USER]: pin code 600042 for the delivery address")
        assertTrue("Postal PIN must not warn", results.credentialShared() == null)
    }

    @Test
    fun `contact sharing their own card produces no credential signal`() {
        val results = scorer(UserCategory.VULNERABLE_ADULT)
            .score("[CONTACT]: my card number is 4532 7712 0034 9821, pay the deposit there")
        assertTrue("Contact self-share must not fire credential_shared",
            results.credentialShared() == null)
    }
}
