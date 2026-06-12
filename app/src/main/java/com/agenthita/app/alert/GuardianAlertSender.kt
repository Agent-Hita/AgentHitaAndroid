package com.agenthita.app.alert

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.detection.DetectionResult
import com.agenthita.app.detection.HarmCategory
import com.agenthita.app.storage.HitaDatabase
import com.agenthita.app.telemetry.TelemetryManager
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dispatches Tier 1 guardian alerts via WorkManager.
 *
 * Throttle behaviour (window = RemoteConfig.guardianAlertThrottleMs, default 24 h):
 *   - First HIGH event for a contact → sent immediately; throttle window starts.
 *   - Further HIGH events within the window → accumulated as unsent DB records;
 *     a single deferred WorkManager job (unique per contact, KEEP policy) is
 *     scheduled to fire when the window expires.
 *   - Deferred job sends one aggregated alert covering all accumulated events.
 *
 * Returns true from sendIfConfigured when an immediate alert is enqueued so the
 * caller can mark the event as sent. Returns false when throttled (event stays
 * unsent in the DB until the deferred job picks it up).
 *
 * Tier 1 disclosure (consent.html): category + severity + approximate time +
 * recommended action. Explicitly excluded: message content, contact name/number,
 * conversation history, screenshots, precise timestamps.
 */
class GuardianAlertSender(
    private val context: Context,
    private val consentManager: ConsentManager
) {
    /**
     * @return true  — immediate alert enqueued; caller should mark the event sent.
     *         false — throttled; event left unsent for the deferred aggregated job.
     */
    fun sendIfConfigured(
        result: DetectionResult,
        eventId: Long,
        packageName: String,
        contactHash: String
    ): Boolean {
        val guardianEmail = consentManager.guardianEmail
        if (guardianEmail == null) {
            android.util.Log.d(TAG, "Skipped: no guardian email configured")
            TelemetryManager.get(context).track("guardian_alert_skipped_no_email")
            return false
        }
        if (!consentManager.isGuardianAlertsEnabled) {
            android.util.Log.d(TAG, "Skipped: guardian alerts disabled in settings")
            TelemetryManager.get(context).track("guardian_alert_skipped_disabled")
            return false
        }

        val throttle = createThrottle()
        val nowMs = System.currentTimeMillis()
        val throttleMs = RemoteConfig.guardianAlertThrottleMs

        return if (throttle.shouldSendNow(contactHash, nowMs, throttleMs)) {
            throttle.recordAlertSent(contactHash, nowMs)

            val data = Data.Builder()
                .putString(KEY_DEVICE_ID, consentManager.userId)
                .putString(KEY_GUARDIAN_EMAIL, guardianEmail)
                .putString(KEY_CATEGORY, result.category.name)
                .putString(KEY_RISK_LEVEL, result.riskLevel.name)
                .putLong(KEY_EVENT_ID, eventId)
                .putString(KEY_PACKAGE_NAME, packageName)
                .putString(KEY_CONTACT_HASH, contactHash)
                .build()

            val request = OneTimeWorkRequestBuilder<GuardianAlertWorker>()
                .setInputData(data)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            android.util.Log.i(TAG, "Guardian alert enqueued immediately: category=${result.category} level=${result.riskLevel} eventId=$eventId to=$guardianEmail")
            true
        } else {
            val delayMs = throttle.remainingMs(contactHash, nowMs, throttleMs)

            val data = Data.Builder()
                .putBoolean(KEY_IS_AGGREGATED, true)
                .putString(KEY_DEVICE_ID, consentManager.userId)
                .putString(KEY_GUARDIAN_EMAIL, guardianEmail)
                .putString(KEY_CONTACT_HASH, contactHash)
                .build()

            val request = OneTimeWorkRequestBuilder<GuardianAlertWorker>()
                .setInputData(data)
                .setConstraints(networkConstraints())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // KEEP: the first throttled event schedules the aggregated send at the
            // correct expiry time. Subsequent events within the same window are
            // accumulated in the DB; the existing job picks them all up.
            WorkManager.getInstance(context).enqueueUniqueWork(
                aggregateWorkName(contactHash),
                ExistingWorkPolicy.KEEP,
                request
            )
            android.util.Log.i(TAG, "Guardian alert throttled — deferred aggregated send in ${delayMs}ms for contact=$contactHash")
            false
        }
    }

    private fun createThrottle(): GuardianAlertThrottle {
        val prefs = context.getSharedPreferences(THROTTLE_PREFS, Context.MODE_PRIVATE)
        return GuardianAlertThrottle(
            store  = { key -> if (prefs.contains(key)) prefs.getLong(key, 0L) else null },
            save   = { key, value -> prefs.edit().putLong(key, value).apply() },
            remove = { key -> prefs.edit().remove(key).apply() }
        )
    }

    companion object {
        private const val TAG               = "GuardianAlertSender"
        internal const val THROTTLE_PREFS   = "hita_guardian_throttle"

        const val KEY_DEVICE_ID             = "device_id"
        const val KEY_GUARDIAN_EMAIL        = "guardian_email"
        const val KEY_CATEGORY              = "category"
        const val KEY_RISK_LEVEL            = "risk_level"
        const val KEY_EVENT_ID              = "event_id"
        const val KEY_PACKAGE_NAME          = "package_name"
        const val KEY_CONTACT_HASH          = "contact_hash"
        const val KEY_IS_AGGREGATED         = "is_aggregated"

        fun aggregateWorkName(contactHash: String) = "guardian_aggregate_$contactHash"

        fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}

class GuardianAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG          = "GuardianAlertWorker"
        private const val MAX_ATTEMPTS = 5

        private val APP_NAMES = mapOf(
            "com.instagram.android"              to "Instagram",
            "com.whatsapp"                       to "WhatsApp",
            "com.whatsapp.w4b"                   to "WhatsApp Business",
            "com.google.android.apps.messaging"  to "Google Messages",
            "com.samsung.android.messaging"      to "Samsung Messages",
            "com.android.messaging"              to "Messages",
            "com.android.mms"                    to "Messages"
        )

        private val WHAT_YOU_CAN_DO = mapOf(
            "SEXTORTION" to listOf(
                "Do not comply with any demands — paying rarely stops the threats.",
                "Preserve all evidence: screenshots, usernames, and message threads.",
                "Report to the FBI's IC3 at ic3.gov and your local police.",
                "Contact the Cyber Civil Rights Initiative crisis helpline for immediate support.",
                "Block the person on all platforms after reporting."
            ),
            "FINANCIAL_SCAM" to listOf(
                "Do not send any money or gift cards under any circumstance.",
                "Do not share bank account details, wire transfer info, or crypto wallet addresses.",
                "Report the scam to the FTC at ReportFraud.ftc.gov.",
                "If money has already been sent, contact your bank immediately to attempt a reversal.",
                "Block and report the account on the platform where contact was made."
            ),
            "GROOMING" to listOf(
                "Do not allow private or in-person meetings with this contact.",
                "Review and restrict the child's privacy settings on all social platforms.",
                "Save all evidence and report to the NCMEC CyberTipline at missingkids.org.",
                "Contact local law enforcement — grooming is a criminal offence.",
                "Seek guidance from a child safety professional or counsellor."
            ),
            "ROMANCE_SCAM" to listOf(
                "Do not send money, gifts, or financial details to someone you haven't met in person.",
                "Reverse-image-search any photos the contact has shared.",
                "Report to the FTC at ReportFraud.ftc.gov and the FBI's IC3 at ic3.gov.",
                "Talk to a trusted friend or family member before making any decisions.",
                "Block the account once you have preserved evidence."
            ),
            "IDENTITY_PHISHING" to listOf(
                "Do not click any links or provide personal information in response to the messages.",
                "Change passwords on any accounts that may have been compromised.",
                "Enable two-factor authentication on all important accounts.",
                "Report identity theft to IdentityTheft.gov for a personalised recovery plan.",
                "Monitor credit reports for any unauthorised activity."
            ),
            "LURING" to listOf(
                "Do not agree to meet this person in real life under any circumstances.",
                "Keep a record of all communications and report to local law enforcement.",
                "Report the account to the platform and to the FBI's IC3 at ic3.gov.",
                "Discuss online safety boundaries openly with the person you are protecting.",
                "Consider temporarily restricting the device or account until the situation is resolved."
            ),
            "HARASSMENT" to listOf(
                "Document everything — screenshots with timestamps are important evidence.",
                "Block the sender on the platform immediately.",
                "Report the harassment to the platform and, if threats are involved, to police.",
                "Visit StopBullying.gov for resources on cyberbullying and reporting options.",
                "Reach out to the Crisis Text Line (text HOME to 741741) for emotional support."
            ),
            "DISAPPEARING_MESSAGES" to listOf(
                "Take screenshots of the conversation immediately before the messages disappear.",
                "Disappearing messages are often used to hide harmful or coercive behaviour — stay alert.",
                "Do not share personal information, photos, or videos in this conversation.",
                "If the conversation involves a minor, report the contact to the NCMEC CyberTipline.",
                "Consider ending the conversation and blocking the contact if something feels wrong."
            )
        )
    }

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) {
            android.util.Log.e(TAG, "Guardian alert abandoned after $MAX_ATTEMPTS attempts")
            TelemetryManager.get(applicationContext).track("guardian_alert_abandoned")
            return Result.failure()
        }

        val isAggregated = inputData.getBoolean(GuardianAlertSender.KEY_IS_AGGREGATED, false)
        return if (isAggregated) doAggregatedWork() else doImmediateWork()
    }

    private suspend fun doImmediateWork(): Result {
        val deviceId     = inputData.getString(GuardianAlertSender.KEY_DEVICE_ID) ?: return Result.failure()
        val guardianEmail = inputData.getString(GuardianAlertSender.KEY_GUARDIAN_EMAIL)
            ?: return Result.failure()
        val categoryName = inputData.getString(GuardianAlertSender.KEY_CATEGORY)
            ?: return Result.failure()
        val riskLevel    = inputData.getString(GuardianAlertSender.KEY_RISK_LEVEL) ?: "HIGH"
        val category     = HarmCategory.valueOf(categoryName)
        val eventId      = inputData.getLong(GuardianAlertSender.KEY_EVENT_ID, -1L)
        val packageName  = inputData.getString(GuardianAlertSender.KEY_PACKAGE_NAME) ?: ""
        val contactHash  = inputData.getString(GuardianAlertSender.KEY_CONTACT_HASH) ?: ""

        val appName = APP_NAMES[packageName] ?: packageName

        val isFirstAlert = try {
            val dao = HitaDatabase.getInstance(applicationContext).riskEventDao()
            val previous = dao.getEventsForContact(contactHash)
            previous.count { it.guardianAlertSent && it.id != eventId } == 0
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not query previous alerts: ${e.message}")
            true
        }

        return try {
            sendAlert(
                deviceId     = deviceId,
                to           = guardianEmail,
                category     = category,
                riskLevel    = riskLevel,
                eventId      = eventId,
                appName      = appName,
                isFirstAlert = isFirstAlert
            )
            android.util.Log.i(TAG, "Guardian alert sent: category=$categoryName level=$riskLevel eventId=$eventId")
            TelemetryManager.get(applicationContext).track("guardian_alert_sent")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Guardian alert send failed (will retry): ${e.message}")
            TelemetryManager.get(applicationContext).track("guardian_alert_failed")
            Result.retry()
        }
    }

    private suspend fun doAggregatedWork(): Result {
        val deviceId = inputData.getString(GuardianAlertSender.KEY_DEVICE_ID) ?: return Result.failure()
        val guardianEmail = inputData.getString(GuardianAlertSender.KEY_GUARDIAN_EMAIL)
            ?: return Result.failure()
        val contactHash = inputData.getString(GuardianAlertSender.KEY_CONTACT_HASH)
            ?: return Result.failure()

        val dao = try {
            HitaDatabase.getInstance(applicationContext).riskEventDao()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Aggregated alert: cannot open DB: ${e.message}")
            return Result.retry()
        }

        val unsentEvents = dao.getUnsentEventsForContact(contactHash)
        if (unsentEvents.isEmpty()) {
            android.util.Log.i(TAG, "Aggregated alert: no unsent events for contact=$contactHash — skipping")
            return Result.success()
        }

        val topEvent = unsentEvents.maxByOrNull { it.score } ?: return Result.success()
        val category = HarmCategory.valueOf(topEvent.harmCategory)
        val appName  = APP_NAMES[topEvent.appPackage] ?: topEvent.appPackage

        return try {
            sendAlert(
                deviceId     = deviceId,
                to           = guardianEmail,
                category     = category,
                riskLevel    = topEvent.riskLevel,
                eventId      = topEvent.id,
                appName      = appName,
                isFirstAlert = false,
                isAggregated = true,
                eventCount   = unsentEvents.size
            )
            dao.markAllUnsentAlertsSent(contactHash)

            val prefs = applicationContext.getSharedPreferences(GuardianAlertSender.THROTTLE_PREFS, android.content.Context.MODE_PRIVATE)
            GuardianAlertThrottle(
                store  = { key -> if (prefs.contains(key)) prefs.getLong(key, 0L) else null },
                save   = { key, value -> prefs.edit().putLong(key, value).apply() },
                remove = { key -> prefs.edit().remove(key).apply() }
            ).recordAlertSent(contactHash, System.currentTimeMillis())

            android.util.Log.i(TAG, "Aggregated alert sent: ${unsentEvents.size} events for contact=$contactHash topCategory=${category.name}")
            TelemetryManager.get(applicationContext).track("guardian_alert_aggregated_sent")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Aggregated alert send failed (will retry): ${e.message}")
            TelemetryManager.get(applicationContext).track("guardian_alert_failed")
            Result.retry()
        }
    }

    /**
     * POSTs alert data to the backend, which sends the email via AWS SES.
     * Credentials never leave the server — the app only passes the alert payload.
     */
    private fun sendAlert(
        deviceId: String,
        to: String,
        category: HarmCategory,
        riskLevel: String,
        eventId: Long,
        appName: String,
        isFirstAlert: Boolean,
        isAggregated: Boolean = false,
        eventCount: Int = 1
    ) {
        val whatYouCanDo = WHAT_YOU_CAN_DO[category.name] ?: emptyList()

        val payload = JSONObject().apply {
            put("deviceId",      deviceId)
            put("guardianEmail", to)
            put("category",      category.name)
            put("riskLevel",     riskLevel)
            put("eventId",       eventId)
            put("appName",       appName)
            put("isFirstAlert",  isFirstAlert)
            put("isAggregated",  isAggregated)
            if (isAggregated) put("eventCount", eventCount)
            put("whatYouCanDo",  org.json.JSONArray(whatYouCanDo))
        }

        val conn = URL(RemoteConfig.alertEndpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-API-Key", RemoteConfig.apiKey)
        conn.doOutput = true
        conn.connectTimeout = RemoteConfig.connectTimeoutMs
        conn.readTimeout    = RemoteConfig.readTimeoutMs

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val body = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
            conn.disconnect()
            throw RuntimeException("Alert endpoint returned HTTP $code: $body")
        }
        conn.disconnect()
    }
}
