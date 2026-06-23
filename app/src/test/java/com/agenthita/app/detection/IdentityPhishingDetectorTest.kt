package com.agenthita.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IdentityPhishingDetectorTest {

    private lateinit var detector: IdentityPhishingDetector

    @Before
    fun setUp() {
        detector = IdentityPhishingDetector()
    }

    // ── True positives — code/credential requests ─────────────────────────────

    @Test
    fun `OTP request scores at least MEDIUM`() {
        val result = detector.analyze("Please share the OTP we just sent to your phone to verify your account.")
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "code_request" })
    }

    @Test
    fun `password request scores at least MEDIUM`() {
        val result = detector.analyze("Enter your password and username to continue.")
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "credential_request" })
    }

    @Test
    fun `SSN request with urgency scores HIGH`() {
        // Multiple signals: personal_info_request + urgency + credential_request pushes over HIGH threshold
        val result = detector.analyze(
            "Your account will be suspended. Action required immediately — " +
            "confirm your account password and provide your social security number."
        )
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `fake bank security alert with credential request scores HIGH`() {
        val result = detector.analyze(
            "From your bank security team: unusual activity detected. " +
            "Please confirm your account details and password immediately."
        )
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `two factor code request scores at least MEDIUM`() {
        val result = detector.analyze("Can you read me the 2fa code from your phone?")
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "code_request" })
    }

    // ── False-positive regressions — information sharing ─────────────────────

    @Test
    fun `parent sharing tax document with SSN scores NONE`() {
        val result = detector.analyze(
            "Here is your tax document. Your social security number is 123-45-6789. " +
            "Please keep this safe."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `here is the tax and identification number scores NONE`() {
        val result = detector.analyze(
            "Here is the tax and identification number for the form."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `parent forwarding passport number scores NONE`() {
        val result = detector.analyze(
            "I've attached your travel docs. Your passport number is AB1234567 — please verify."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `FYI sharing national ID scores NONE`() {
        val result = detector.analyze("FYI here is your national ID number as requested.")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `please find attached with account number scores NONE`() {
        val result = detector.analyze(
            "Please find the attached form. The bank account number shown is for refund purposes."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    // ── Providing context does NOT suppress when request signals also fire ────

    @Test
    fun `here is prefix does not suppress when credential request also present`() {
        val result = detector.analyze(
            "Here is the link. Now enter your password and username to complete verification."
        )
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "credential_request" })
    }

    @Test
    fun `providing context does not suppress OTP request`() {
        val result = detector.analyze(
            "I am sending you the form. Can you also share the OTP from your phone?"
        )
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "code_request" })
    }

    // ── Clean text → NONE ─────────────────────────────────────────────────────

    @Test
    fun `normal message scores NONE`() {
        val result = detector.analyze("Hey! How is your day going? Did you finish the project?")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `category is always IDENTITY_PHISHING`() {
        val result = detector.analyze("Send me the verification code you received.")
        assertEquals(HarmCategory.IDENTITY_PHISHING, result.category)
    }

    @Test
    fun `score is within 0 to 1 range`() {
        val result = detector.analyze(
            "URGENT: Your account has been compromised. Provide your password and OTP immediately."
        )
        assertTrue(result.score in 0f..1f)
    }
}
