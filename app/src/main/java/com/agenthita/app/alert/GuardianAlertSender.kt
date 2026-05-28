package com.agenthita.app.alert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agenthita.app.BuildConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.detection.DetectionResult
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dispatches Tier 1 guardian alerts via a WorkManager background job.
 *
 * Tier 1 disclosure (consent.html): category + severity + approximate time + recommended action.
 * Explicitly excluded: message content, contact name/number, conversation history,
 * screenshots, precise timestamps.
 */
class GuardianAlertSender(
    private val context: Context,
    private val consentManager: ConsentManager
) {
    fun sendIfConfigured(
        result: DetectionResult,
        eventId: Long,
        appName: String = "",
        contactHash: String = "",
        isFirstAlert: Boolean = true
    ) {
        val guardianEmail = consentManager.guardianEmail ?: return
        if (!consentManager.isGuardianAlertsEnabled) return

        val data = Data.Builder()
            .putString(KEY_GUARDIAN_EMAIL, guardianEmail)
            .putString(KEY_CATEGORY, result.category.name)
            .putString(KEY_RISK_LEVEL, result.riskLevel.name)
            .putLong(KEY_EVENT_ID, eventId)
            .putString(KEY_APP_NAME, appName)
            .putBoolean(KEY_IS_FIRST_ALERT, isFirstAlert)
            .build()

        val request = OneTimeWorkRequestBuilder<GuardianAlertWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val KEY_GUARDIAN_EMAIL = "guardian_email"
        const val KEY_CATEGORY       = "category"
        const val KEY_RISK_LEVEL     = "risk_level"
        const val KEY_EVENT_ID       = "event_id"
        const val KEY_APP_NAME       = "app_name"
        const val KEY_IS_FIRST_ALERT = "is_first_alert"
    }
}

class GuardianAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardianEmail = inputData.getString(GuardianAlertSender.KEY_GUARDIAN_EMAIL)
            ?: return Result.failure()
        val categoryName = inputData.getString(GuardianAlertSender.KEY_CATEGORY)
            ?: return Result.failure()
        val riskLevel    = inputData.getString(GuardianAlertSender.KEY_RISK_LEVEL) ?: "HIGH"
        val eventId      = inputData.getLong(GuardianAlertSender.KEY_EVENT_ID, -1L)
        val appName      = inputData.getString(GuardianAlertSender.KEY_APP_NAME) ?: ""
        val isFirstAlert = inputData.getBoolean(GuardianAlertSender.KEY_IS_FIRST_ALERT, true)

        val success = postToAlertService(guardianEmail, categoryName, riskLevel, eventId, appName, isFirstAlert)
        return if (success) Result.success() else Result.retry()
    }

    private fun postToAlertService(
        guardianEmail: String,
        category: String,
        riskLevel: String,
        eventId: Long,
        appName: String,
        isFirstAlert: Boolean
    ): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("guardianEmail", guardianEmail)
                put("category", category)
                put("riskLevel", riskLevel)
                put("eventId", eventId)
                put("appName", appName)
                put("firstAlert", isFirstAlert)
            }

            val url = URL("${BuildConfig.ALERT_API_URL}/guardian")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Api-Key", BuildConfig.FEEDBACK_API_KEY)
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            android.util.Log.w("GuardianAlertWorker", "Alert POST failed: ${e.message}")
            false
        }
    }
}
