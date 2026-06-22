package com.agenthita.app.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages per-device authentication tokens.
 *
 * On first call to getToken(), registers the device with the backend and stores
 * the returned token in EncryptedSharedPreferences. Subsequent calls return the
 * cached value without network I/O.
 */
object DeviceTokenManager {

    private const val TAG        = "DeviceTokenManager"
    private const val PREFS_FILE = "hita_device_token"
    private const val KEY_TOKEN  = "device_token"

    @Volatile
    private var cached: String? = null

    /**
     * Returns the device token, registering with the backend if needed.
     * Safe to call from any coroutine context — I/O is switched to Dispatchers.IO.
     */
    suspend fun getToken(context: Context): String {
        cached?.let { return it }

        val prefs = buildEncryptedPrefs(context.applicationContext)
        val stored = prefs.getString(KEY_TOKEN, null)
        if (!stored.isNullOrBlank()) {
            cached = stored
            return stored
        }

        return withContext(Dispatchers.IO) {
            try {
                val token = register(context.applicationContext)
                prefs.edit().putString(KEY_TOKEN, token).apply()
                cached = token
                token
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed: ${e.message}", e)
                ""
            }
        }
    }

    /**
     * POSTs the deviceId to the registration endpoint and returns the token.
     * Must be called from an IO dispatcher.
     */
    private fun register(context: Context): String {
        val deviceId = ConsentManager(context.applicationContext).userId
        val payload = JSONObject().apply { put("deviceId", deviceId) }

        val conn = URL(RemoteConfig.deviceRegisterEndpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = RemoteConfig.connectTimeoutMs
        conn.readTimeout    = RemoteConfig.readTimeoutMs
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val body = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
            conn.disconnect()
            throw RuntimeException("Device register endpoint returned HTTP $code: $body")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        return JSONObject(responseBody).getString("token")
    }

    fun invalidate(context: Context) {
        cached = null
        try {
            buildEncryptedPrefs(context.applicationContext).edit().remove(KEY_TOKEN).apply()
            Log.i(TAG, "Device token invalidated — will re-register on next request")
        } catch (e: Exception) {
            Log.w(TAG, "Token invalidation failed: ${e.message}")
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
