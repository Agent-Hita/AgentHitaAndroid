package com.agenthita.app.detection

import com.agenthita.app.consent.UserCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for OTP direction handling through [RiskScorer]:
 * a received OTP (bank/app delivery) must never alert, while the user sharing
 * an OTP — in the same window, or as a bare code after a code request in prior
 * context — must flag IDENTITY_PHISHING as HIGH, including for adults.
 */
class OtpSharingTest {

    private class FakeClassifier : Classifier {
        override val isLoaded = false
        override fun classify(text: String, context: List<String>, ageHint: String?) = null
    }

    private fun adultScorer() =
        RiskScorer(FakeClassifier(), UserCategory.SELF_PROTECTING_ADULT)

    private fun List<DetectionResult>.otpSharedResult() =
        firstOrNull {
            it.category == HarmCategory.IDENTITY_PHISHING &&
            it.signals.any { s -> s.signal == "otp_shared" }
        }

    // ── Received OTP must not alert ───────────────────────────────────────────

    @Test
    fun `received bank OTP delivery produces no alert for adult`() {
        val results = adultScorer().score(
            "[CONTACT]: 482913 is your OTP for HDFC Bank NetBanking. Do not share it with anyone."
        )
        assertTrue("Received OTP must not produce any alert", results.isEmpty())
    }

    @Test
    fun `received OTP with validity phrasing produces no alert for adult`() {
        val results = adultScorer().score(
            "[CONTACT]: Your OTP is 4821, valid for 10 minutes."
        )
        assertTrue("Received OTP must not produce any alert", results.isEmpty())
    }

    // ── User sharing an OTP must flag HIGH even for adults ───────────────────

    @Test
    fun `user sharing OTP with keyword flags HIGH for adult`() {
        val results = adultScorer().score("[USER]: otp is 482913 use it now")
        val result = results.otpSharedResult()
        assertNotNull("User OTP share must flag", result)
        assertEquals(RiskLevel.HIGH, result!!.riskLevel)
    }

    @Test
    fun `code request and bare code reply in same window flags HIGH for adult`() {
        val results = adultScorer().score(
            "[CONTACT]: share the otp you got from the bank\n[USER]: 482913"
        )
        val result = results.otpSharedResult()
        assertNotNull("In-window OTP handover must flag", result)
        assertEquals(RiskLevel.HIGH, result!!.riskLevel)
    }

    @Test
    fun `bare code reply after code request in prior context flags HIGH for adult`() {
        val context = listOf(
            "[CONTACT]: i am calling from your bank, share the otp you received"
        )
        val results = adultScorer().score("[USER]: 482913 ok", context)
        val result = results.otpSharedResult()
        assertNotNull("Cross-window OTP handover must flag", result)
        assertEquals(RiskLevel.HIGH, result!!.riskLevel)
    }

    // ── Benign numeric shares must not trigger the boost ─────────────────────

    @Test
    fun `bare number with no code request in context does not flag`() {
        val context = listOf("[CONTACT]: what time works for you?")
        val results = adultScorer().score("[USER]: 482913 ok", context)
        assertNull(results.otpSharedResult())
    }

    @Test
    fun `postal pin code after code request in context does not flag`() {
        val context = listOf("[CONTACT]: what is the code for your area?")
        val results = adultScorer().score("[USER]: 560001 is my pin code", context)
        assertNull(results.otpSharedResult())
    }

    // ── Anaphoric demands ("share that") count with OTP context ──────────────

    private fun List<DetectionResult>.codeRequestResult() =
        firstOrNull {
            it.category == HarmCategory.IDENTITY_PHISHING &&
            it.signals.any { s -> s.signal == "code_request" }
        }

    @Test
    fun `pronoun demand with otp context flags code request`() {
        val results = adultScorer().score(
            "[CONTACT]: You will get an otp. Share that."
        )
        assertNotNull("Pronoun demand after OTP mention must flag", results.codeRequestResult())
    }

    @Test
    fun `pronoun demand referring to delivered code flags even with delivery line present`() {
        val results = adultScorer().score(
            "[CONTACT]: Your OTP is 482913, valid for 10 minutes.\n[CONTACT]: Send it to me now"
        )
        assertNotNull("Demand for a delivered code must flag", results.codeRequestResult())
    }

    @Test
    fun `pronoun demand without any code context does not flag`() {
        val results = adultScorer().score(
            "[CONTACT]: I took a photo of the sunset. Share that."
        )
        assertNull(results.codeRequestResult())
    }
}
