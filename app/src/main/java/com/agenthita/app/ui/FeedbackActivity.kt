package com.agenthita.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.BuildConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityFeedbackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
            submitFeedback(rating, binding.etFeedback.text?.toString()?.trim() ?: "")
        }
    }

    private fun submitFeedback(rating: Int, feedbackText: String) {
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Submitting…"

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { postFeedback(rating, feedbackText) }

            if (success) {
                binding.btnSubmit.visibility = View.GONE
                binding.layoutSuccess.visibility = View.VISIBLE
            } else {
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "Submit Feedback"
                Toast.makeText(
                    this@FeedbackActivity,
                    "Could not submit — please try again later",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun postFeedback(rating: Int, feedbackText: String): Boolean {
        return try {
            val consent = ConsentManager(applicationContext)
            val payload = JSONObject().apply {
                put("rating", rating)
                put("text", feedbackText)
                put("userId", consent.userId)
                put("consentVersion", consent.consentVersion)
            }

            val url = URL(BuildConfig.FEEDBACK_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Api-Key", BuildConfig.FEEDBACK_API_KEY)
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            android.util.Log.w("FeedbackActivity", "Feedback submission failed: ${e.message}")
            false
        }
    }

}
