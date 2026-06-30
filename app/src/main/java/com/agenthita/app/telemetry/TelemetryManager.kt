package com.agenthita.app.telemetry

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.security.DeviceTokenManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Batches privacy-safe app events and sends them to the EC2 /telemetry endpoint,
 * which aggregates and publishes them to CloudWatch as custom metrics.
 *
 * Privacy contract:
 *  - No raw message text, contact names, or user identifiers are ever recorded.
 *  - Only aggregate event counts and numeric durations are sent.
 *
 * Usage:
 *   TelemetryManager.get(context).track("analysis_completed")
 *   TelemetryManager.get(context).track("analysis_duration_ms", durationMs.toDouble())
 *   TelemetryManager.get(context).flush()   // call on service stop / periodic
 */
class TelemetryManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Aggregated counts: eventName → (sum, count, firstTimestamp)
    private val pendingEvents = mutableMapOf<String, AggregatedEvent>()
    private val lock = Any()
    private val lastFlushMs = AtomicLong(0L)

    // ── Public API ────────────────────────────────────────────────────────────

    fun track(eventName: String, value: Double = 1.0) {
        require(eventName in ALLOWED_EVENT_NAMES) {
            "Unknown telemetry event '$eventName' — add it to ALLOWED_EVENT_NAMES first"
        }
        synchronized(lock) {
            val existing = pendingEvents[eventName]
            pendingEvents[eventName] = if (existing == null) {
                AggregatedEvent(eventName, value, 1, Instant.now().toString())
            } else {
                existing.copy(sum = existing.sum + value, count = existing.count + 1)
            }
            val nowMs = System.currentTimeMillis()
            val enoughEvents = pendingEvents.size >= RemoteConfig.telemetryFlushThreshold
            val enoughTime   = nowMs - lastFlushMs.get() >= RemoteConfig.telemetryFlushIntervalMs
            if (enoughEvents || (enoughTime && pendingEvents.isNotEmpty())) {
                lastFlushMs.set(nowMs)
                flushAsync()
            }
        }
    }

    fun flush() {
        lastFlushMs.set(System.currentTimeMillis())
        flushAsync()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun flushAsync() {
        val batch: List<AggregatedEvent>
        synchronized(lock) {
            if (pendingEvents.isEmpty()) return
            batch = pendingEvents.values.toList()
            pendingEvents.clear()
        }

        scope.launch {
            val token = DeviceTokenManager.getCachedToken(context) ?: run {
                Log.d(TAG, "Telemetry flush deferred — device not yet registered, re-queuing ${batch.size} events")
                synchronized(lock) {
                    for (event in batch) {
                        val existing = pendingEvents[event.name]
                        pendingEvents[event.name] = if (existing == null) event
                            else existing.copy(sum = existing.sum + event.sum, count = existing.count + event.count)
                    }
                }
                return@launch
            }
            sendBatch(batch, token)
        }
    }

    private fun sendBatch(events: List<AggregatedEvent>, token: String) {
        try {
            val payload = JSONObject().apply {
                put("appVersion",     RemoteConfig.appVersion)
                put("androidVersion", Build.VERSION.RELEASE)
                put("deviceManufacturer", Build.MANUFACTURER.take(64))
                put("buildType",      RemoteConfig.buildType)
                put("country",        getCountry())
                put("events", JSONArray().also { arr ->
                    events.forEach { e ->
                        arr.put(JSONObject().apply {
                            put("eventName", e.name)
                            put("value",     e.sum)
                            put("count",     e.count)
                            put("timestamp", e.firstTimestamp)
                        })
                    }
                })
            }

            val url = URL(RemoteConfig.telemetryEndpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Device-Token", token)
            conn.doOutput = true
            conn.connectTimeout = RemoteConfig.connectTimeoutMs
            conn.readTimeout    = RemoteConfig.readTimeoutMs

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.d(TAG, "Telemetry batch sent: ${events.size} events")
            } else {
                if (code == 401) DeviceTokenManager.invalidate(context, token)
                Log.w(TAG, "Telemetry batch rejected: HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry batch failed: ${e.message}")
        }
    }

    /**
     * ISO 3166-1 alpha-2 country code.
     * Prefers the SIM's network country (most reliable); falls back to the device locale.
     * Returns "unknown" if neither is available.
     */
    private fun getCountry(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simCountry = tm.simCountryIso?.uppercase()?.takeIf { it.length == 2 }
            simCountry ?: Locale.getDefault().country.uppercase().takeIf { it.length == 2 } ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ── Data class ────────────────────────────────────────────────────────────

    private data class AggregatedEvent(
        val name: String,
        val sum: Double,
        val count: Int,
        val firstTimestamp: String
    )

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "TelemetryManager"

        // ████████████████████████████████████████████████████████████████████████
        // PRIVACY BOUNDARY — READ BEFORE EDITING
        //
        // This is the exhaustive allowlist of telemetry event names. Every entry
        // must be a short snake_case identifier describing an app action or metric.
        //
        // NEVER add:  message text, contact names, phone numbers, email addresses,
        //             conversation content, user-typed strings, or any data derived
        //             from message content. Doing so would violate the privacy promise
        //             stated in the Play Store listing and the Accessibility declaration.
        //
        // Adding a new call site requires a matching entry here. Omitting it throws
        // IllegalArgumentException at runtime and fails any test that exercises the
        // call path — the build will not pass.
        // ████████████████████████████████████████████████████████████████████████
        internal val ALLOWED_EVENT_NAMES: Set<String> = setOf(
            "app_open",
            "app_close",
            "app_crash",
            "app_startup_ms",
            "service_connect_ms",
            "gemma_load_ms",
            "monitoring_enabled",
            "analysis_started",
            "analysis_completed",
            "analysis_failed",
            "analysis_duration_ms",
            "alert_generated",
            "gemma_load_success",
            "gemma_load_failed",
            "gemma_download_tapped",
            "gemma_download_started",
            "gemma_download_success",
            "gemma_download_duration_ms",
            "gemma_download_failed",
            "gemma_terms_declined",
            "gemma_import_success",
            "gemma_hash_mismatch",
            "gemma_hash_verification_skipped",
            "guardian_alert_sent",
            "guardian_alert_failed",
            "guardian_alert_aggregated_sent",
            "guardian_alert_abandoned",
            "guardian_alert_skipped_no_email",
            "guardian_alert_skipped_disabled",
            "parsing_failed_whatsapp",
            "parsing_failed_instagram",
            "parsing_failed_google_messages",
            "parsing_failed_samsung_messages",
            "parsing_failed_aosp_messages",
            "parsing_failed_aosp_mms",
            "parsing_failed_unknown",
            "accessibility_permission_granted",
            "accessibility_permission_declined",
            "permission_accessibility_denied",
            "feedback_submit_failed",
            "false_positive_reported",
            "service_frozen",
            "hita_terms_accepted",
            "hita_terms_declined",
            "gemma_terms_accepted",
            "event_prune_run",
            "event_prune_failed"
        )

        @Volatile
        private var instance: TelemetryManager? = null

        fun get(context: Context): TelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
