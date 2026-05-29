package com.agenthita.app.consent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages all consent state for Agent Hita.
 * Backed by EncryptedSharedPreferences — values are encrypted at rest.
 */
class ConsentManager(context: Context) {

    private val prefs: SharedPreferences = buildEncryptedPrefs(context)

    // --- Onboarding & consent record ---

    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    /** Unix timestamp (ms) when the user completed the consent ceremony. */
    var consentTimestampMs: Long
        get() = prefs.getLong(KEY_CONSENT_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_CONSENT_TIMESTAMP, value).apply()

    var userCategory: UserCategory
        get() = UserCategory.valueOf(
            prefs.getString(KEY_USER_CATEGORY, UserCategory.SELF_PROTECTING_ADULT.name)!!
        )
        set(value) = prefs.edit().putString(KEY_USER_CATEGORY, value.name).apply()

    // --- Guardian alert configuration ---

    var guardianEmail: String?
        get() = prefs.getString(KEY_GUARDIAN_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_GUARDIAN_EMAIL, value).apply()

    var isGuardianAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARDIAN_ALERTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GUARDIAN_ALERTS_ENABLED, value).apply()

    // --- Autonomy prompts (consent.html safeguard #4) ---
    // Every 90 days, the monitored user is asked privately whether they still want guardian alerts.
    // Their response is NOT shared with the guardian.

    var lastAutonomyPromptMs: Long
        get() = prefs.getLong(KEY_LAST_AUTONOMY_PROMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTONOMY_PROMPT, value).apply()

    fun isDueForAutonomyPrompt(): Boolean {
        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - lastAutonomyPromptMs > ninetyDaysMs
    }

    /** True once the user has tapped "I Agree" on the T&C screen. */
    var hasAcceptedTerms: Boolean
        get() = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).apply()

    /** Version of the Terms & Conditions the user accepted. */
    var acceptedTermsVersion: String
        get() = prefs.getString(KEY_ACCEPTED_TERMS_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCEPTED_TERMS_VERSION, value).apply()

    /** Stable anonymous identifier generated once on first install. */
    val userId: String
        get() {
            val stored = prefs.getString(KEY_USER_ID, null)
            if (stored != null) return stored
            val generated = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, generated).apply()
            return generated
        }

    /** Version of the consent document accepted by the user. */
    var consentVersion: String
        get() = prefs.getString(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)!!
        set(value) = prefs.edit().putString(KEY_CONSENT_VERSION, value).apply()

    /** Wipes all consent state — used when user revokes or uninstalls. */
    fun clearAllConsent() = prefs.edit().clear().apply()

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "hita_consent_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to plain prefs if encrypted storage fails (e.g. keystore unavailable)
            android.util.Log.e("ConsentManager", "EncryptedSharedPreferences failed, using plain prefs", e)
            context.getSharedPreferences("hita_consent_prefs_plain", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val KEY_ONBOARDING_COMPLETE    = "onboarding_complete"
        private const val KEY_CONSENT_TIMESTAMP      = "consent_timestamp"
        private const val KEY_USER_CATEGORY          = "user_category"
        private const val KEY_GUARDIAN_EMAIL         = "guardian_email"
        private const val KEY_GUARDIAN_ALERTS_ENABLED = "guardian_alerts_enabled"
        private const val KEY_LAST_AUTONOMY_PROMPT   = "last_autonomy_prompt"
        private const val KEY_USER_ID                = "user_id"
        private const val KEY_CONSENT_VERSION        = "consent_version"
        private const val KEY_TERMS_ACCEPTED         = "terms_accepted"
        private const val KEY_ACCEPTED_TERMS_VERSION = "accepted_terms_version"

        const val CURRENT_CONSENT_VERSION = "1.0"
        const val CURRENT_TERMS_VERSION   = "1.0"
    }
}

enum class UserCategory {
    SELF_PROTECTING_ADULT,
    VULNERABLE_ADULT,
    ADOLESCENT,
    CHILD
}
