package com.agenthita.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinancialScamDetectorTest {

    private lateinit var detector: FinancialScamDetector

    @Before
    fun setUp() {
        detector = FinancialScamDetector()
    }

    // ── False-positive regression ─────────────────────────────────────────────
    // A bare URL must not flag a legitimate message. Previously "https://" was a
    // standalone suspicious_link signal with weight 0.6, enough to cross the
    // child MEDIUM threshold (0.18) on its own.

    @Test
    fun `legitimate yoga invite with URL scores NONE`() {
        val result = detector.analyze(
            "I am sending you a special invitation to join FREE YOGA 14-day online " +
            "next batch 22-june with saurabh bothra govt-certified yoga trainer IIT graduate " +
            "| 14+years pf experience Click below to join for free " +
            "https://habit.yoga/profdrtvsrinivasd86b12da regards, prof dr t v srinivas"
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `message with only a bare https URL scores NONE`() {
        val result = detector.analyze("Check out our new article: https://example.com/blog/post-1")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `message with only a bare http URL scores NONE`() {
        val result = detector.analyze("Our website is http://mysite.org for more info")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `event invite with URL scores NONE`() {
        val result = detector.analyze(
            "Join our free webinar on Thursday! Register here: https://zoom.us/webinar/register/abc123"
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    // ── False-positive: numeric IDs in document-sharing context ──────────────
    // Tax IDs, EINs, and SSNs share the phone-number regex format. They must not
    // fire phone_number_demand when the message is clearly a document share.

    @Test
    fun `parent sharing EIN in tax document scores NONE`() {
        val result = detector.analyze(
            "Here is your tax document. The employer identification number is 12-3456789."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `please find attached with numeric ID scores NONE`() {
        val result = detector.analyze(
            "Please find attached the completed form. Reference number: 123456789."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `as requested SSN share scores NONE`() {
        val result = detector.analyze(
            "As requested, here is the social security number: 123-456-789 for your records."
        )
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    // Providing context does NOT suppress when authority/urgency also fires
    @Test
    fun `here is with IRS authority still scores HIGH`() {
        val result = detector.analyze(
            "Here is the IRS notice. You owe back taxes. Pay immediately via gift card or face arrest."
        )
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `here is with urgency phone demand still fires`() {
        val result = detector.analyze(
            "Here is the situation — your account will be closed. Call us at 800-555-0199 immediately."
        )
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "phone_number_demand" })
    }

    // ── Suspicious link signals that MUST still fire ──────────────────────────

    @Test
    fun `url shortener bit dot ly scores at least LOW`() {
        val result = detector.analyze("Click this link to claim your package: bit.ly/trackpkg99")
        assertTrue(result.riskLevel >= RiskLevel.LOW)
        assertTrue(result.signals.any { it.signal == "suspicious_link" })
    }

    @Test
    fun `url shortener tinyurl scores at least LOW`() {
        val result = detector.analyze("Your account requires verification: tinyurl.com/verifyacc")
        assertTrue(result.riskLevel >= RiskLevel.LOW)
        assertTrue(result.signals.any { it.signal == "suspicious_link" })
    }

    @Test
    fun `payment path in url scores at least LOW`() {
        val result = detector.analyze("Complete your outstanding balance: mybank.com/payment/now")
        assertTrue(result.riskLevel >= RiskLevel.LOW)
        assertTrue(result.signals.any { it.signal == "suspicious_link" })
    }

    @Test
    fun `verify path in url scores at least LOW`() {
        val result = detector.analyze("Your account has been locked. Verify now: secure-login.com/verify")
        assertTrue(result.riskLevel >= RiskLevel.LOW)
    }

    @Test
    fun `click here phrase scores at least LOW`() {
        val result = detector.analyze("Your payment is overdue. Click here to settle your account immediately.")
        assertTrue(result.riskLevel >= RiskLevel.LOW)
        assertTrue(result.signals.any { it.signal == "suspicious_link" })
    }

    // ── Prize scam ────────────────────────────────────────────────────────────

    @Test
    fun `prize scam claim your prize scores at least MEDIUM`() {
        val result = detector.analyze("Congratulations! You've won. Claim your prize by sending gift cards worth $500.")
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "prize_scam" })
        assertTrue(result.signals.any { it.signal == "payment_method" })
    }

    @Test
    fun `lottery winner message scores at least MEDIUM`() {
        val result = detector.analyze("You are the lucky winner of our lottery. Send your bank account details to collect.")
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
    }

    // ── Authority + payment combo ─────────────────────────────────────────────

    @Test
    fun `irs impersonation with payment scores HIGH`() {
        val result = detector.analyze(
            "This is the IRS. You owe back taxes. Pay immediately using Google Play card " +
            "or a warrant will be issued for your arrest."
        )
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.signals.any { it.signal == "authority_claim" })
        assertTrue(result.signals.any { it.signal == "payment_method" })
    }

    @Test
    fun `bank impersonation account locked scores at least MEDIUM`() {
        val result = detector.analyze(
            "Your account has been locked due to suspicious activity. " +
            "Please call us immediately to verify your bank account details."
        )
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "authority_claim" })
    }

    // ── Urgency signals ───────────────────────────────────────────────────────

    @Test
    fun `urgent payment demand scores at least MEDIUM`() {
        val result = detector.analyze(
            "Final notice: your payment is overdue. Failure to pay immediately will result in late fees and penalties."
        )
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "urgency" })
        assertTrue(result.signals.any { it.signal == "payment_method" })
    }

    // ── Isolation signals ─────────────────────────────────────────────────────

    @Test
    fun `isolation demand with payment scores at least MEDIUM`() {
        val result = detector.analyze(
            "Do not tell your family about this. Send the money via wire transfer right now."
        )
        assertTrue(result.riskLevel >= RiskLevel.MEDIUM)
        assertTrue(result.signals.any { it.signal == "isolation" })
        assertTrue(result.signals.any { it.signal == "payment_method" })
    }

    // ── Clean messages → NONE ─────────────────────────────────────────────────

    @Test
    fun `normal greeting scores NONE`() {
        val result = detector.analyze("Hey! Are you free this weekend? We should catch up.")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    @Test
    fun `shopping discussion scores NONE`() {
        val result = detector.analyze("I ordered the shoes online, they should arrive by Thursday.")
        assertEquals(RiskLevel.NONE, result.riskLevel)
    }

    // ── Category and signal metadata ──────────────────────────────────────────

    @Test
    fun `category is always FINANCIAL_SCAM`() {
        val result = detector.analyze("You've won a prize! Claim your reward now.")
        assertEquals(HarmCategory.FINANCIAL_SCAM, result.category)
    }

    @Test
    fun `score is within 0 to 1 range`() {
        val result = detector.analyze(
            "IRS: You owe taxes. Pay immediately via gift card or face arrest. " +
            "Do not tell anyone. Call us at 800-555-0199 right now."
        )
        assertTrue(result.score in 0f..1f)
    }
}
