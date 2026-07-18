package com.agenthita.app.model

import com.agenthita.app.detection.HarmCategory
import com.agenthita.app.detection.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [GemmaClassifier.parseMultiClassResponse]. Inputs are uppercase,
 * mirroring the call site (the raw model response is uppercased before parsing).
 *
 * The critical property: an explicit NONE verdict TERMINATES the scan. The
 * previous parser skipped NONE and kept scanning, so a chatty safe answer that
 * mentioned the word "phishing" ("NONE. THIS IS A BANK ALERT, NOT PHISHING.")
 * was parsed as IDENTITY_PHISHING, and "NONE (NOT HIGH RISK)" as severity HIGH
 * — manufacturing alerts out of safe verdicts.
 */
class GemmaResponseParserTest {

    private fun parse(response: String) = GemmaClassifier.parseMultiClassResponse(response)

    // ── Well-formed answers ──────────────────────────────────────────────────

    @Test
    fun `fill-in format parses category and severity`() {
        assertEquals(
            HarmCategory.GROOMING to RiskLevel.HIGH,
            parse("HARM TYPE: GROOMING\nSEVERITY: HIGH")
        )
    }

    @Test
    fun `bare category defaults to MEDIUM severity`() {
        assertEquals(
            HarmCategory.IDENTITY_PHISHING to RiskLevel.MEDIUM,
            parse("IDENTITY_PHISHING")
        )
    }

    @Test
    fun `explicit NONE verdict is safe`() {
        assertNull(parse("HARM TYPE: NONE\nSEVERITY: NONE"))
    }

    // ── NONE must terminate the scan (regression) ────────────────────────────

    @Test
    fun `rambling NONE answer mentioning phishing is safe`() {
        assertNull(parse("HARM TYPE: NONE. THIS IS A BANK ALERT, NOT PHISHING."))
    }

    @Test
    fun `NONE severity with trailing HIGH mention is safe`() {
        assertNull(parse("HARM TYPE: NONE\nSEVERITY: NONE (NOT HIGH RISK)"))
    }

    @Test
    fun `category with explicit NONE severity is safe`() {
        assertNull(parse("HARM TYPE: GROOMING\nSEVERITY: NONE"))
    }

    // ── Garbage stays safe ───────────────────────────────────────────────────

    @Test
    fun `invented category word is safe`() {
        assertNull(parse("SCAMMING"))
    }

    @Test
    fun `echoed template without an answer is safe`() {
        assertNull(parse("CATEGORY SEVERITY: HIGH"))
    }

    @Test
    fun `echoed valid-types list does not pollute the answer`() {
        // Gemma sometimes echoes the prompt (which lists every category) before
        // answering; the parser must anchor to the LAST "HARM TYPE" label.
        assertNull(
            parse(
                "VALID HARM TYPES: SEXTORTION FINANCIAL_SCAM GROOMING ROMANCE_SCAM " +
                "IDENTITY_PHISHING LURING HARASSMENT NONE\nHARM TYPE: NONE\nSEVERITY: NONE"
            )
        )
    }
}
