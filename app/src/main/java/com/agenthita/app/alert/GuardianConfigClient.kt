package com.agenthita.app.alert

import android.content.Context
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.security.DeviceTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** One registered guardian address and whether it's confirmed yet. */
data class GuardianEmailStatus(val email: String, val confirmed: Boolean)

/**
 * @param success true if the backend accepted the request (2xx response).
 * @param pendingConfirmation for action="ADDED" on a 2xx response, whether any of the
 *   requested address(es) still need the guardian to click a confirmation email before
 *   real alerts reach them. Null for action="REMOVED" (the backend doesn't return this
 *   for removal) or when the request failed.
 */
data class GuardianConfigResult(val success: Boolean, val pendingConfirmation: Boolean? = null)

/**
 * Shared client for the backend's /guardian/configure and /guardian/status endpoints —
 * used whenever the guardian relationship changes ("ADDED"/"REMOVED"), regardless of what
 * triggered the change (an explicit Guardian Setup save, or this device detecting that
 * monitoring stopped on its own), and to check per-address confirmation status. Kept in
 * one place so call sites can never silently drift apart on the request/response shape.
 */
object GuardianConfigClient {

    private const val TAG = "GuardianConfigClient"

    suspend fun postGuardianConfig(
        context: Context,
        consentManager: ConsentManager,
        email: String,
        action: String
    ): GuardianConfigResult = withContext(Dispatchers.IO) {
        try {
            val token = DeviceTokenManager.getToken(context)
            val payload = JSONObject().apply {
                put("deviceId", consentManager.userId)
                put("guardianEmail", email)
                put("action", action)
            }
            val conn = URL(RemoteConfig.guardianConfigEndpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Token", token)
            conn.doOutput = true
            conn.connectTimeout = RemoteConfig.connectTimeoutMs
            conn.readTimeout = RemoteConfig.readTimeoutMs
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext GuardianConfigResult(success = false)
            }
            val pendingConfirmation = if (action == "ADDED") {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).optBoolean("pendingConfirmation", false)
            } else null
            conn.disconnect()
            GuardianConfigResult(success = true, pendingConfirmation = pendingConfirmation)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Guardian config notification failed ($action): ${e.message}")
            GuardianConfigResult(success = false)
        }
    }

    /**
     * Per-address confirmed/pending status for this device's registered guardian
     * email(s) — lets the app show accurate state instead of assuming confirmed the
     * instant Save is tapped, since confirmation happens out-of-band (the guardian
     * clicking a link in their own inbox). Returns null on any failure (no network,
     * no token yet, non-2xx) — callers should just leave the status UI hidden.
     */
    suspend fun fetchGuardianStatus(context: Context): List<GuardianEmailStatus>? = withContext(Dispatchers.IO) {
        try {
            val token = DeviceTokenManager.getCachedToken(context) ?: return@withContext null
            val conn = URL(RemoteConfig.guardianStatusEndpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Device-Token", token)
            conn.connectTimeout = RemoteConfig.connectTimeoutMs
            conn.readTimeout = RemoteConfig.readTimeoutMs
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val guardians = JSONObject(body).getJSONArray("guardians")
            (0 until guardians.length()).map { i ->
                val entry = guardians.getJSONObject(i)
                GuardianEmailStatus(entry.getString("email"), entry.getBoolean("confirmed"))
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Guardian status fetch failed: ${e.message}")
            null
        }
    }
}
