package com.agenthita.app.alert

import android.content.Context
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.telemetry.TelemetryManager

/**
 * Periodically checks whether the Accessibility Service has been disabled —
 * e.g. via Android's own Settings app, never opening Agent Hita's own UI at
 * all — and notifies the guardian if so.
 *
 * This exists specifically because that path bypasses GuardianSetupActivity's
 * save flow entirely, so nothing there ever runs. A guardian notification that
 * only fires when the monitored person happens to reopen the app is close to
 * useless for exactly the case it matters most: someone disabling monitoring
 * on purpose has no reason to reopen it afterward. Running this as an
 * independent periodic check means the guardian finds out regardless.
 *
 * Deliberately does NOT fire for a temporarily-frozen process (e.g. Samsung's
 * FreecessController killing the service — see HitaAccessibilityService) —
 * that case leaves the service present in ENABLED_ACCESSIBILITY_SERVICES, it's
 * only the process that died, and the existing heartbeat/local-warning path
 * already handles it as a recoverable, non-alarming state. Only a genuine
 * absence from the enabled-services list counts here.
 */
class AccessibilityDisabledCheckWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "accessibility_disabled_check"
        private const val AI_PREFS = "hita_ai_prefs"
        private const val KEY_WAS_ENABLED = "accessibility_was_enabled"
        private const val KEY_NOTIFIED = "accessibility_disabled_notified"
    }

    override suspend fun doWork(): Result {
        val consentManager = ConsentManager(appContext)
        if (!consentManager.isOnboardingComplete) return Result.success()

        val prefs = appContext.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
        val currentlyEnabled = isAccessibilityServiceEnabled(appContext)
        // Default true: an already-onboarded user has necessarily granted this
        // before, so absent any prior recorded state, treat it as having been
        // enabled — the safe default errs toward informing the guardian about
        // a disablement rather than silently missing one that predates this check.
        val wasKnownEnabled = prefs.getBoolean(KEY_WAS_ENABLED, true)
        val alreadyNotified = prefs.getBoolean(KEY_NOTIFIED, false)

        if (AccessibilityDisabledDecision.shouldResetTracking(currentlyEnabled, wasKnownEnabled)) {
            prefs.edit()
                .putBoolean(KEY_WAS_ENABLED, true)
                .putBoolean(KEY_NOTIFIED, false)
                .apply()
            return Result.success()
        }

        val guardianEmail = consentManager.guardianEmail
        val guardianConfigured = guardianEmail != null && consentManager.isGuardianAlertsEnabled

        if (!AccessibilityDisabledDecision.shouldNotifyMonitoringStopped(
                currentlyEnabled = currentlyEnabled,
                wasKnownEnabled = wasKnownEnabled,
                alreadyNotifiedForThisDisablement = alreadyNotified,
                guardianConfigured = guardianConfigured
            )
        ) {
            // Nothing to notify, but still record the current enabled state so a
            // later transition can be detected correctly.
            if (wasKnownEnabled != currentlyEnabled) {
                prefs.edit().putBoolean(KEY_WAS_ENABLED, currentlyEnabled).apply()
            }
            return Result.success()
        }

        val sent = GuardianConfigClient.postGuardianConfig(
            context = appContext,
            consentManager = consentManager,
            email = guardianEmail!!,
            action = "REMOVED"
        )
        return if (sent) {
            prefs.edit()
                .putBoolean(KEY_WAS_ENABLED, false)
                .putBoolean(KEY_NOTIFIED, true)
                .apply()
            TelemetryManager.get(appContext).track("accessibility_disabled_guardian_notified")
            Result.success()
        } else {
            // Leave KEY_WAS_ENABLED as true (unchanged) and KEY_NOTIFIED unset so
            // the next scheduled run retries the notification rather than giving up.
            Result.retry()
        }
    }
}

/** True if this app's accessibility service is currently in the user's enabled-services list. */
internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(context.packageName, ignoreCase = true)
}
