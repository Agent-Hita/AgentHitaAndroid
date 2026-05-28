package com.agenthita.poc.consent

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Implements the anti-coercion design patterns specified in consent.html.
 *
 * Safeguard #3 — Safe Exit Pathway:
 *   If a monitored user searches for escape/help terms, surface resources locally
 *   WITHOUT logging the search or triggering any guardian alert.
 *
 * Safeguard #4 — Periodic Autonomy Prompts:
 *   Every 90 days, check whether the user still consents to guardian alerts.
 *   Response is never shared with the guardian.
 */
class AntiCoercionMonitor(
    private val context: Context,
    private val consentManager: ConsentManager
) {
    /**
     * Call this before passing any text to the detection pipeline.
     * If the query matches safe-exit terms, suppress analysis and return help resources.
     */
    fun checkForSafeExitIntent(query: String): SafeExitResult {
        val lower = query.lowercase()
        val isSafeExitQuery = safeExitTerms.any { lower.contains(it) }
        return if (isSafeExitQuery) {
            SafeExitResult(shouldSuppressAnalysis = true, resources = helpResources)
        } else {
            SafeExitResult(shouldSuppressAnalysis = false)
        }
    }

    /** Returns true if it's time to show the 90-day private autonomy check-in. */
    fun shouldShowAutonomyPrompt(): Boolean =
        consentManager.isGuardianAlertsEnabled && consentManager.isDueForAutonomyPrompt()

    fun recordAutonomyPromptShown() {
        consentManager.lastAutonomyPromptMs = System.currentTimeMillis()
    }

    /** Opens a help URL without triggering any detection or guardian notification. */
    fun openHelpResource(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // Terms that indicate the user may be looking for help or trying to escape monitoring.
    // Keeping this list conservative — false positives here mean suppressed detection,
    // which is safer than false negatives (alerting the guardian about a distress search).
    private val safeExitTerms = listOf(
        "am i being tracked", "am i being monitored", "how to remove monitoring",
        "domestic abuse help", "how to remove agent hita", "is my phone monitored",
        "national domestic violence", "abuse hotline", "how to get help",
        "stalkerware", "spyware on my phone", "coercive control",
        "my partner is controlling me", "i feel unsafe"
    )

    private val helpResources = listOf(
        HelpResource("National Domestic Violence Hotline", "https://www.thehotline.org"),
        HelpResource("Crisis Text Line", "https://www.crisistextline.org"),
        HelpResource("Cyber Civil Rights Initiative", "https://cybercivilrights.org"),
        HelpResource("FBI IC3 Sextortion Resources", "https://www.ic3.gov"),
        HelpResource("NCMEC CyberTipline", "https://www.missingkids.org/gethelpnow/cybertipline")
    )
}

data class SafeExitResult(
    val shouldSuppressAnalysis: Boolean,
    val resources: List<HelpResource> = emptyList()
)

data class HelpResource(val name: String, val url: String)
