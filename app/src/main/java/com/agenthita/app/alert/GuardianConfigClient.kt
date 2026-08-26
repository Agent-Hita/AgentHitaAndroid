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

/**
 * Shared client for the backend's /guardian/configure endpoint — used whenever
 * the guardian relationship changes ("ADDED"/"REMOVED"), regardless of what
 * triggered the change (an explicit Guardian Setup save, or this device
 * detecting that monitoring stopped on its own). Kept in one place so the two
 * call sites can never silently drift apart on the request shape.
 */
object GuardianConfigClient {

    private const val TAG = "GuardianConfigClient"

    /** @return true if the backend accepted the request (2xx response). */
    suspend fun postGuardianConfig(
        context: Context,
        consentManager: ConsentManager,
        email: String,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
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
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Guardian config notification failed ($action): ${e.message}")
            false
        }
    }
}
