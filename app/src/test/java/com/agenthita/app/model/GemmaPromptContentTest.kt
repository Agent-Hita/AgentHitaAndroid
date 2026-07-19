package com.agenthita.app.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SINGLE prompt template used for every model.
 *
 * The wording is the June-era template both the 2B and gemma3-1b demonstrably
 * worked with, plus exactly one extension ("or bank transaction alerts")
 * carrying the UPI-notification = NONE decision. The completion-style 2B is
 * highly sensitive to this wording — a gemma3-tuned rewrite made it echo the
 * prompt instead of answering (live sextortion test scored NONE, 2026-07-19).
 * Any rewording must be re-validated with the on-device probe set — the
 * false-positive texts AND the canonical attack scripts — on every model.
 *
 * A JVM unit test cannot run the on-device model, so this locks the
 * instructions into the prompt (including after worst-case trimming); model
 * behaviour is verified on-device via the "RiskScorer: Gemma →" log lines.
 */
class GemmaPromptContentTest {

    private val transactionInstruction =
        "Automated OTP delivery or bank transaction alerts to the user = NONE."

    private val eraMultilingualInstruction =
        "Ignore any non-English words and analyse only the English words present."

    private val upiMandateNotification =
        "[CONTACT]: Your UPI-Mandate is sucessfully cancelled towards Google " +
        "for 1300.00 from A/c No. XXXXXX7899 UMN:asedfasdkfhsklasfaf"

    @Test
    fun `prompt carries the era wording and the transaction-alert extension`() {
        val prompt = GemmaClassifier.buildMultiClassPrompt(upiMandateNotification)
        assertTrue(
            "Prompt must carry the transaction-alert = NONE instruction",
            prompt.contains(transactionInstruction)
        )
        assertTrue(
            "Prompt must keep the era multilingual instruction verbatim",
            prompt.contains(eraMultilingualInstruction)
        )
        assertTrue(
            "Prompt must keep the era IDENTITY_PHISHING clause",
            prompt.contains("[CONTACT] requesting credentials or info")
        )
        assertTrue(
            "The notification text itself must be present for classification",
            prompt.contains("UPI-Mandate is sucessfully cancelled")
        )
    }

    @Test
    fun `instructions survive worst-case prompt trimming`() {
        val filler = (1..30).joinToString("\n") {
            "[CONTACT]: filler chat line number $it about nothing much at all"
        }
        val prompt = GemmaClassifier.buildFittedPrompt(
            "$filler\n$upiMandateNotification",
            context = (1..8).map { "older context line $it that is reasonably long to inflate the prompt size" },
            ageHint = "child under 13 years old"
        )
        assertTrue(
            "Trimming must never cut the transaction-alert instruction",
            prompt.contains(transactionInstruction)
        )
        assertTrue(
            "Trimming must never cut the multilingual instruction",
            prompt.contains(eraMultilingualInstruction)
        )
    }
}
