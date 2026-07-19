package com.agenthita.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthita.app.consent.UserCategory
import com.agenthita.app.detection.Classifier
import com.agenthita.app.detection.RiskLevel
import com.agenthita.app.detection.RiskScorer
import com.agenthita.app.model.GemmaClassifier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device model comparison suite — runs the REAL loaded model (whichever
 * .bin/.task is in app storage) through the full GemmaClassifier + RiskScorer
 * pipeline against a fixed probe set:
 *
 *  - fp-* probes: false positives observed live on 2026-07-18/19 — must stay
 *    silent (pipeline < MEDIUM).
 *  - tp-* probes: canonical attack scripts from ChildAlertInvariantsTest and
 *    the launch test plan — must alert (pipeline >= MEDIUM).
 *
 * Run (device with the model under test in app filesDir):
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.agenthita.app.ModelComparisonSuite
 *
 * Emits one machine-readable logcat line per probe (tag ModelComparison):
 *   COMPARE|probe=<id>|gemma=<CAT:SEV|NONE>|pipeline=<LEVEL>|expected=<ALERT|SILENT>|result=<PASS|FAIL>
 * plus SUITE| (model info) and SUMMARY| (pass counts) lines. Probes are
 * report-only — the test itself fails only if no model is loaded — so a
 * model that fails probes still yields the complete comparison table.
 */
@RunWith(AndroidJUnit4::class)
class ModelComparisonSuite {

    private data class Probe(
        val id: String,
        val text: String,
        val category: UserCategory,
        val expectAlert: Boolean
    )

    private val probes = listOf(
        // ── False positives observed live — must stay silent ─────────────────
        Probe("fp-telugu-outgoing",
            "[USER]: Aa phone charge ayipoyindi padma.",
            UserCategory.VULNERABLE_ADULT, false),
        Probe("fp-telugu-thanks-incoming",
            "[CONTACT]: Tq pinni chala thanks",
            UserCategory.VULNERABLE_ADULT, false),
        Probe("fp-birthday-outgoing",
            "[USER]: Happy Birthday Shravani\n[USER]: Congratulations Sravani",
            UserCategory.VULNERABLE_ADULT, false),
        Probe("fp-upi-notification",
            "[CONTACT]: Your UPI-Mandate is sucessfully cancelled towards Google " +
            "for 1300.00 from A/c No. XXXXXX7899 UMN:asedfasdkfhsklasfaf",
            UserCategory.VULNERABLE_ADULT, false),
        Probe("fp-payment-mention",
            "[CONTACT]: Paid to Driver Balaraju today for 7 hours",
            UserCategory.VULNERABLE_ADULT, false),
        Probe("fp-contact-card",
            "[CONTACT]: +91 91549 98185 Mr. Akshay BMS Executive",
            UserCategory.VULNERABLE_ADULT, false),

        // ── Canonical attacks — must alert ───────────────────────────────────
        Probe("tp-otp-explicit-child",
            "[CONTACT]: I am calling from your bank. Share the otp you received right now.",
            UserCategory.CHILD, true),
        Probe("tp-otp-pronoun-child",
            "[CONTACT]: Dear Customer, your OTP for verifying Express Delivery is 482913. Do not share it with anyone.\n" +
            "[USER]: What is this?\n" +
            "[CONTACT]: You will get an otp. Share that.",
            UserCategory.CHILD, true),
        Probe("tp-sextortion-child",
            "[CONTACT]: Send money now. I know your parents are rich.\n" +
            "[CONTACT]: Else I will share your pictures with everyone you know.",
            UserCategory.CHILD, true),
        Probe("tp-credentials-child",
            "[CONTACT]: Your account will be deleted today. Verify your account immediately.\n" +
            "[CONTACT]: Send me your username and password to keep it safe.",
            UserCategory.CHILD, true),
        // The adult tier relies on Gemma corroboration (rules alone score 0.667
        // vs the 0.70 MEDIUM bar) — this is where a blind model fails silently.
        Probe("tp-sextortion-adult",
            "[CONTACT]: Send money now. I know your parents are rich.\n" +
            "[CONTACT]: Else I will share your pictures with everyone you know.",
            UserCategory.VULNERABLE_ADULT, true)
    )

    private fun ageHintFor(category: UserCategory): String? = when (category) {
        UserCategory.CHILD -> "child under 13 years old"
        else               -> null
    }

    @Test
    fun runComparison() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFiles = ctx.filesDir.listFiles()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".task") }
            ?.joinToString(",") { "${it.name}(${it.length() / 1_000_000}MB)" } ?: "none"

        val gemma = GemmaClassifier(ctx)
        android.util.Log.i(TAG, "SUITE|model=$modelFiles|loaded=${gemma.isLoaded}")
        assertTrue(
            "A model must be present and loaded in app storage to run the comparison (found: $modelFiles)",
            gemma.isLoaded
        )

        val adapter = object : Classifier {
            override val isLoaded get() = gemma.isLoaded
            override fun classify(text: String, context: List<String>, ageHint: String?) =
                gemma.classifyMessage(text, context, ageHint)
        }

        var pass = 0
        for (p in probes) {
            val t0 = System.currentTimeMillis()
            // Direct model verdict first; RiskScorer's identical call inside
            // score() then hits the classifier's single-entry cache, so each
            // probe costs one real inference.
            val verdict = adapter.classify(p.text, emptyList(), ageHintFor(p.category))
            val level = RiskScorer(adapter, p.category).score(p.text)
                .maxOfOrNull { it.riskLevel } ?: RiskLevel.NONE
            val ms = System.currentTimeMillis() - t0

            val alerted = level >= RiskLevel.MEDIUM
            val ok = alerted == p.expectAlert
            if (ok) pass++
            android.util.Log.i(TAG,
                "COMPARE|probe=${p.id}" +
                "|gemma=${verdict?.let { "${it.first}:${it.second}" } ?: "NONE"}" +
                "|pipeline=$level" +
                "|expected=${if (p.expectAlert) "ALERT" else "SILENT"}" +
                "|result=${if (ok) "PASS" else "FAIL"}" +
                "|ms=$ms")
        }
        android.util.Log.i(TAG, "SUMMARY|pass=$pass|total=${probes.size}|model=$modelFiles")
    }

    private companion object {
        const val TAG = "ModelComparison"
    }
}
