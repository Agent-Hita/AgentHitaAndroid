package com.agenthita.app.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the prompt contract for known Gemma false-positive classes: the
 * template must instruct the model to answer NONE for automated bank
 * transaction alerts (UPI mandates, debit/credit notifications) delivered to
 * the user — e.g. "Your UPI-Mandate is sucessfully cancelled towards Google
 * for 1300.00 from A/c No. XXXXXX7899 UMN:…" must not become an alert.
 *
 * A JVM unit test cannot run the on-device model, so this locks the
 * instruction into the prompt (including after worst-case trimming); the
 * model's actual NONE verdict is verified on-device via the
 * "RiskScorer: Gemma → NONE" log line.
 */
class GemmaPromptContentTest {

    private val transactionInstruction =
        "bank transaction alerts (UPI, mandate, debited, credited, A/c) to the user = NONE"

    private val upiMandateNotification =
        "[CONTACT]: Your UPI-Mandate is sucessfully cancelled towards Google " +
        "for 1300.00 from A/c No. XXXXXX7899 UMN:asedfasdkfhsklasfaf"

    @Test
    fun `prompt instructs NONE for bank transaction alerts`() {
        val prompt = GemmaClassifier.buildMultiClassPrompt(upiMandateNotification)
        assertTrue(
            "Prompt must carry the transaction-alert = NONE instruction",
            prompt.contains(transactionInstruction)
        )
        assertTrue(
            "The notification text itself must be present for classification",
            prompt.contains("UPI-Mandate is sucessfully cancelled")
        )
    }

    @Test
    fun `transaction instruction survives worst-case prompt trimming`() {
        val filler = (1..30).joinToString("\n") {
            "[CONTACT]: filler chat line number $it about nothing much at all"
        }
        val prompt = GemmaClassifier.buildFittedPrompt(
            "$filler\n$upiMandateNotification",
            context = (1..8).map { "older context line $it that is reasonably long to inflate the prompt size" },
            ageHint = "child under 13 years old"
        )
        assertTrue(
            "Trimming must never cut the transaction-alert = NONE instruction",
            prompt.contains(transactionInstruction)
        )
    }
}
