package com.agenthita.app.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agenthita.app.BuildConfig
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages per-device authentication tokens.
 *
 * Registration flow (with Play Integrity):
 *   1. Warm up StandardIntegrityManager (done once at app start via warmUp())
 *   2. At registration time, request an integrity token with requestHash = deviceId
 *   3. POST {deviceId, integrityToken} to /device/register
 *   4. Backend verifies the token with Google and issues a session token
 *
 * If the Play Integrity API is unavailable (emulator, pre-Play device, CLOUD_PROJECT_NUMBER=0),
 * registration proceeds without an integrity token — the backend accepts this in dev mode.
 */
private class RegistrationRateLimitedException : Exception()

object DeviceTokenManager {

    private const val TAG        = "DeviceTokenManager"
    private const val PREFS_FILE = "hita_device_token"
    private const val KEY_TOKEN  = "device_token"

    private const val INITIAL_BACKOFF_MS = 2L  * 60 * 1_000  // 2 min
    private const val MAX_BACKOFF_MS     = 60L * 60 * 1_000  // 60 min

    @Volatile private var cached: String? = null
    private val registrationMutex = Mutex()

    @Volatile private var nextRegistrationAttemptMs = 0L
    @Volatile private var registrationBackoffMs     = INITIAL_BACKOFF_MS

    /** Warmed-up integrity manager — null until warmUp() succeeds. */
    @Volatile private var integrityManager: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call once from Application.onCreate() to pre-warm the integrity token provider
     * so the first registration request isn't delayed by the warm-up round trip.
     */
    suspend fun warmUp(context: Context) {
        if (BuildConfig.CLOUD_PROJECT_NUMBER == 0L) {
            Log.w(TAG, "CLOUD_PROJECT_NUMBER not set — Play Integrity warm-up skipped")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
                integrityManager = manager.prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(BuildConfig.CLOUD_PROJECT_NUMBER)
                        .build()
                ).await()
                Log.i(TAG, "Play Integrity warm-up complete")
            } catch (e: Exception) {
                Log.w(TAG, "Play Integrity warm-up failed (will retry at registration): ${e.message}")
            }
        }
    }

    /**
     * Returns the device token, registering with the backend if needed.
     * Safe to call from any coroutine context.
     */
    suspend fun getToken(context: Context): String {
        cached?.let { return it }

        val prefs = buildEncryptedPrefs(context.applicationContext)
        val stored = prefs.getString(KEY_TOKEN, null)
        if (!stored.isNullOrBlank()) {
            cached = stored
            return stored
        }

        return registrationMutex.withLock {
            // Re-check after acquiring lock — another coroutine may have registered while we waited.
            cached?.let { return@withLock it }
            val stored2 = prefs.getString(KEY_TOKEN, null)
            if (!stored2.isNullOrBlank()) {
                cached = stored2
                return@withLock stored2
            }
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                if (now < nextRegistrationAttemptMs) {
                    val waitSec = (nextRegistrationAttemptMs - now) / 1_000
                    Log.d(TAG, "Registration skipped — rate-limit backoff active for ${waitSec}s more")
                    return@withContext ""
                }
                try {
                    val token = register(context.applicationContext)
                    nextRegistrationAttemptMs = 0L
                    registrationBackoffMs = INITIAL_BACKOFF_MS
                    prefs.edit().putString(KEY_TOKEN, token).apply()
                    cached = token
                    Log.i(TAG, "Device registered successfully")
                    token
                } catch (e: RegistrationRateLimitedException) {
                    val backoff = registrationBackoffMs
                    nextRegistrationAttemptMs = System.currentTimeMillis() + backoff
                    registrationBackoffMs = minOf(backoff * 2, MAX_BACKOFF_MS)
                    Log.w(TAG, "Registration rate-limited — backing off ${backoff / 60_000}min")
                    ""
                } catch (e: Exception) {
                    Log.e(TAG, "Device registration failed: ${e.message}", e)
                    ""
                }
            }
        }
    }

    /**
     * Returns the stored token without triggering registration. Returns null if the
     * device has not yet registered successfully. Use this when registration as a
     * side-effect would be wrong (e.g. telemetry flushes, where a missing token
     * should silently skip the network call rather than kick off a retry loop).
     */
    fun getCachedToken(context: Context): String? {
        cached?.let { return it }
        val prefs = buildEncryptedPrefs(context.applicationContext)
        val stored = prefs.getString(KEY_TOKEN, null)
        if (!stored.isNullOrBlank()) {
            cached = stored
            return stored
        }
        return null
    }

    fun invalidate(context: Context, rejectedToken: String? = null) {
        // If a specific token is given, only invalidate if it still matches what's stored —
        // prevents in-flight requests with stale tokens from wiping a freshly registered one.
        if (rejectedToken != null && cached != null && cached != rejectedToken) return
        cached = null
        try {
            val prefs = buildEncryptedPrefs(context.applicationContext)
            if (rejectedToken != null) {
                val stored = prefs.getString(KEY_TOKEN, null)
                if (stored != null && stored != rejectedToken) return
            }
            prefs.edit().remove(KEY_TOKEN).apply()
            Log.i(TAG, "Device token invalidated — will re-register on next request")
        } catch (e: Exception) {
            Log.w(TAG, "Token invalidation failed: ${e.message}")
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun register(context: Context): String {
        val deviceId      = ConsentManager(context.applicationContext).userId
        val integrityToken = requestIntegrityToken(context, deviceId)

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            if (!integrityToken.isNullOrBlank()) {
                put("integrityToken", integrityToken)
            }
        }

        val conn = URL(RemoteConfig.deviceRegisterEndpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = RemoteConfig.connectTimeoutMs
        conn.readTimeout    = RemoteConfig.readTimeoutMs
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code == 429 || code == 403) {
            conn.disconnect()
            throw RegistrationRateLimitedException()
        }
        if (code !in 200..299) {
            val body = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
            conn.disconnect()
            throw RuntimeException("Device register endpoint returned HTTP $code: $body")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        return JSONObject(responseBody).getString("token")
    }

    /**
     * Requests a Play Integrity token using the deviceId as the requestHash.
     * Returns null if the API is unavailable — registration will proceed without it.
     */
    private suspend fun requestIntegrityToken(context: Context, deviceId: String): String? {
        if (BuildConfig.CLOUD_PROJECT_NUMBER == 0L) return null

        return try {
            // Use the warmed-up provider if available, otherwise warm up now
            val provider = integrityManager ?: run {
                val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
                manager.prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(BuildConfig.CLOUD_PROJECT_NUMBER)
                        .build()
                ).await().also { integrityManager = it }
            }

            provider.request(
                StandardIntegrityTokenRequest.builder()
                    .setRequestHash(deviceId)
                    .build()
            ).await().token()
        } catch (e: Exception) {
            Log.w(TAG, "Play Integrity token request failed: ${e.message}")
            null
        }
    }

    private fun buildEncryptedPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
