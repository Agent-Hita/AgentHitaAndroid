package com.agenthita.app.telemetry

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.agenthita.app.config.RemoteConfig
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
            if (enoughEvents && enoughTime) {
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
            sendBatch(batch)
        }
    }

    private fun sendBatch(events: List<AggregatedEvent>) {
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
            conn.setRequestProperty("X-API-Key", RemoteConfig.apiKey)
            conn.doOutput = true
            conn.connectTimeout = RemoteConfig.connectTimeoutMs
            conn.readTimeout    = RemoteConfig.readTimeoutMs

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.d(TAG, "Telemetry batch sent: ${events.size} events")
            } else {
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

        @Volatile
        private var instance: TelemetryManager? = null

        fun get(context: Context): TelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
