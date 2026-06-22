package com.agenthita.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.databinding.ActivityFeedbackBinding
import com.agenthita.app.security.DeviceTokenManager
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class FeedbackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeedbackBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tvWebsite.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.agenthita.org")))
        }

        binding.btnSubmit.setOnClickListener {
            val rating = binding.ratingBar.rating.toInt()
            if (rating == 0) {
                Toast.makeText(this, "Please select a star rating", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = binding.etFeedback.text?.toString()?.trim()
                ?.ifEmpty { "No additional comments" } ?: "No additional comments"
            submitFeedback(rating, text)
        }
    }

    private fun submitFeedback(rating: Int, text: String) {
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Submitting…"

        lifecycleScope.launch {
            val token = DeviceTokenManager.getToken(this@FeedbackActivity)
            val error = withContext(Dispatchers.IO) { postFeedback(rating, text, token) }

            if (error == null) {
                binding.btnSubmit.visibility = View.GONE
                binding.layoutSuccess.visibility = View.VISIBLE
                binding.ratingBar.setIsIndicator(true)
                binding.etFeedback.isEnabled = false
            } else {
                android.util.Log.w("FeedbackActivity", "Feedback submission failed: $error")
                TelemetryManager.get(this@FeedbackActivity).track("feedback_submit_failed")
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "Submit Feedback"
                Toast.makeText(this@FeedbackActivity, "Something went wrong. Please try again later.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Returns null on success, or an error message string to show the user on failure. */
    private fun postFeedback(rating: Int, text: String, token: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("userId", getOrCreateUserId())
                put("text", text)
                put("rating", rating)
                put("consentVersion", CONSENT_VERSION)
            }

            val url = URL(RemoteConfig.feedbackEndpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Device-Token", token)
            connection.doOutput = true
            connection.connectTimeout = RemoteConfig.connectTimeoutMs
            connection.readTimeout    = RemoteConfig.readTimeoutMs

            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.disconnect()
                return null
            }

            // Read error body and extract "message" if present
            val errorBody = connection.errorStream?.bufferedReader()?.readText()
            connection.disconnect()
            val serverMessage = errorBody?.let {
                runCatching { JSONObject(it).getString("message") }.getOrNull()
            }
            serverMessage ?: "Could not submit — please try again later"
        } catch (e: Exception) {
            android.util.Log.w("FeedbackActivity", "Feedback submission failed: ${e.message}")
            "Could not submit — please try again later"
        }
    }

    private fun getOrCreateUserId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_ID, null) ?: UUID.randomUUID().toString().also { uuid ->
            prefs.edit().putString(KEY_USER_ID, uuid).apply()
        }
    }

    companion object {
        private const val CONSENT_VERSION = "1.0"
        private const val PREFS_NAME      = "hita_feedback_prefs"
        private const val KEY_USER_ID     = "anonymous_user_id"
    }
}
