package com.agenthita.app.alert

import android.content.Context
import android.util.Log
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.security.DeviceTokenManager
import com.agenthita.app.storage.RiskEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FalsePositiveSender {

    private const val TAG             = "FalsePositiveSender"
    private const val CONSENT_VERSION = "1.0"

    /** Returns null on success, or a user-facing error string on failure. */
    suspend fun send(context: Context, event: RiskEvent): String? = withContext(Dispatchers.IO) {
        try {
            val token        = DeviceTokenManager.getToken(context.applicationContext)
            val userCategory = ConsentManager(context.applicationContext).userCategory.name
            val signals      = event.signals.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val payload = JSONObject().apply {
                put("category",        event.harmCategory)
                put("riskLevel",       event.riskLevel)
                put("score",           (event.score * 100).toInt() / 100.0)
                put("signalTypes",     JSONArray(signals))
                put("detectedByGemma", signals.contains("ai_analysis"))
                put("userCategory",    userCategory)
                put("sourceApp",       event.appPackage)
                put("consentVersion",  CONSENT_VERSION)
            }

            val conn = URL(RemoteConfig.falseFeedbackEndpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Device-Token", token)
            conn.connectTimeout = RemoteConfig.connectTimeoutMs
            conn.readTimeout    = RemoteConfig.readTimeoutMs
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            val code = conn.responseCode
            if (code == 401) DeviceTokenManager.invalidate(context.applicationContext)
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                conn.disconnect()
                Log.w(TAG, "HTTP $code: $body")
                return@withContext "Could not send — please try again later"
            }
            conn.disconnect()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
            "Could not send — check your connection and try again"
        }
    }

    /**
     * Builds the human-readable data preview shown in the consent dialog before the user
     * confirms. Exactly mirrors what [send] will POST — no surprises.
     */
    fun buildConsentPreview(event: RiskEvent, userCategory: String): String {
        val signalList = event.signals.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } }
            .ifEmpty { "None recorded" }

        return buildString {
            appendLine("Agent Hita will share the following data about this alert to help")
            appendLine("improve detection accuracy. No message content or contact")
            appendLine("information is included.\n")
            appendLine("  Alert type:         ${event.harmCategory.toCategoryLabel()}")
            appendLine("  Risk level:         ${event.riskLevel.lowercase().replaceFirstChar { it.uppercase() }}")
            appendLine("  Confidence score:   ${"%.0f".format(event.score * 100)}%")
            appendLine("  Signal patterns:    $signalList")
            appendLine("  Source app:         ${event.appPackage.toAppLabel()}")
            append("  Protection profile: ${userCategory.toProfileLabel()}")
        }
    }

    private fun String.toCategoryLabel() = when (this) {
        "SEXTORTION"            -> "Sexual Manipulation"
        "FINANCIAL_SCAM"        -> "Financial Scam"
        "GROOMING"              -> "Grooming"
        "ROMANCE_SCAM"          -> "Romance Scam"
        "IDENTITY_PHISHING"     -> "Identity Phishing"
        "LURING"                -> "Luring / Fake Offer"
        "HARASSMENT"            -> "Harassment"
        "DISAPPEARING_MESSAGES" -> "Disappearing Messages"
        else                    -> this
    }

    private fun String.toAppLabel() = when (this) {
        "com.whatsapp"                      -> "WhatsApp"
        "com.whatsapp.w4b"                  -> "WhatsApp Business"
        "com.instagram.android"             -> "Instagram"
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.android.mms"                   -> "Messages"
        else                                -> this
    }

    private fun String.toProfileLabel() = when (this) {
        "CHILD"             -> "Child (under 13)"
        "ADOLESCENT"        -> "Adolescent (13–17)"
        "VULNERABLE_ADULT"  -> "Vulnerable adult"
        else                -> "Self-protecting adult"
    }
}
