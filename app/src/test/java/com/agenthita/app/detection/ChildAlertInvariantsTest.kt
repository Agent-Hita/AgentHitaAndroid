package com.agenthita.app.detection

import com.agenthita.app.consent.UserCategory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Child-protection invariants: canonical attack conversations that must ALWAYS
 * score at least MEDIUM (the local-notification bar) for a CHILD user, with
 * rules only — no Gemma. If a detection change breaks one of these, a child
 * stops being alerted for a known attack; that is never an acceptable
 * side-effect, so these tests must be fixed by strengthening detection, not by
 * weakening the expectation.
 *
 * Also locks in the category-provider behaviour: the accessibility service
 * connects before guardian setup stores the category, so the scorer must pick
 * up a category change without being reconstructed (regression test for the
 * stale-snapshot bug where child users kept adult thresholds until reboot).
 */
class ChildAlertInvariantsTest {

    private class FakeClassifier : Classifier {
        override val isLoaded = false
        override fun classify(text: String, context: List<String>, ageHint: String?) = null
    }

    private fun childScorer() = RiskScorer(FakeClassifier(), UserCategory.CHILD)

    private fun List<DetectionResult>.maxLevel(): RiskLevel =
        maxOfOrNull { it.riskLevel } ?: RiskLevel.NONE

    private fun assertChildAlerts(name: String, conversation: String) {
        val level = childScorer().score(conversation).maxLevel()
        assertTrue(
            "$name must alert (>= MEDIUM) for a CHILD user but was $level",
            level >= RiskLevel.MEDIUM
        )
    }

    // ── Canonical attacks that must alert for a child ────────────────────────

    @Test
    fun `otp demand with pronoun alerts for child`() = assertChildAlerts(
        "OTP pronoun demand",
        "[CONTACT]: Dear Customer, your OTP for verifying Express Delivery is 482913. Do not share it with anyone.\n" +
        "[USER]: What is this?\n" +
        "[CONTACT]: You will get an otp. Share that."
    )

    @Test
    fun `otp demand with explicit phrase alerts for child`() = assertChildAlerts(
        "OTP explicit demand",
        "[CONTACT]: I am calling from your bank. Share the otp you received right now."
    )

    @Test
    fun `sextortion threat alerts for child`() = assertChildAlerts(
        "Sextortion threat",
        "[CONTACT]: Send money now. I know your parents are rich.\n" +
        "[CONTACT]: Else I will share your pictures with everyone you know."
    )

    @Test
    fun `credential harvesting alerts for child`() = assertChildAlerts(
        "Credential harvesting",
        "[CONTACT]: Your account will be deleted today. Verify your account immediately.\n" +
        "[CONTACT]: Send me your username and password to keep it safe."
    )

    // ── Category must be read live, never snapshotted ─────────────────────────

    @Test
    fun `scorer picks up category change without reconstruction`() {
        // Simulates the real service lifecycle: the accessibility service creates
        // the scorer at connect time (before guardian setup), then the user picks
        // CHILD on the next screen.
        var category = UserCategory.SELF_PROTECTING_ADULT
        val scorer = RiskScorer(FakeClassifier()) { category }

        val borderline =
            "[CONTACT]: Dear Customer, your OTP for verifying Express Delivery is 482913. Do not share it with anyone.\n" +
            "[USER]: What is this?\n" +
            "[CONTACT]: You will get an otp. Share that."

        category = UserCategory.CHILD
        val childLevel = scorer.score(borderline).maxLevel()
        assertTrue(
            "After the category changes to CHILD, the same scorer must apply child " +
            "thresholds (>= MEDIUM) but was $childLevel",
            childLevel >= RiskLevel.MEDIUM
        )
    }
}
