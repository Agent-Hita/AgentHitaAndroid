package com.agenthita.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.consent.UserCategory
import com.agenthita.app.databinding.ActivityGuardianSetupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GuardianSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSetupBinding
    private lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        consentManager = ConsentManager(this)

        // Pre-fill if re-entering setup
        consentManager.guardianEmail?.let { binding.etGuardianEmail.setText(it) }
        binding.switchAlerts.isChecked = consentManager.isGuardianAlertsEnabled

        // Pre-fill age checkboxes from saved category
        when (consentManager.userCategory) {
            UserCategory.CHILD      -> binding.cbUnder13.isChecked = true
            UserCategory.ADOLESCENT -> binding.cbUnder21.isChecked = true
            else                    -> {}
        }
        // Mutually exclusive: selecting one clears the other
        binding.cbUnder13.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.cbUnder21.isChecked = false
        }
        binding.cbUnder21.setOnCheckedChangeListener { _, checked ->
            if (checked) binding.cbUnder13.isChecked = false
        }

        binding.btnSkip.setOnClickListener {
            saveAgeCategory()
            goToDashboard()
        }

        binding.btnSave.setOnClickListener {
            val email = binding.etGuardianEmail.text.toString().trim()
            val emailValid = email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()

            if (email.isNotEmpty() && !emailValid) {
                binding.etGuardianEmail.error = "Please enter a valid email address"
                return@setOnClickListener
            }

            val previousEmail   = consentManager.guardianEmail
            val wasEnabled      = consentManager.isGuardianAlertsEnabled

            if (emailValid) {
                consentManager.guardianEmail = email
                consentManager.isGuardianAlertsEnabled = true
                binding.switchAlerts.isChecked = true
            } else {
                consentManager.isGuardianAlertsEnabled = false
                binding.switchAlerts.isChecked = false
            }

            notifyGuardianChange(previousEmail, wasEnabled, email.ifEmpty { null }, emailValid)
            saveAgeCategory()
            goToDashboard()
        }
    }

    private fun notifyGuardianChange(
        previousEmail: String?,
        wasEnabled: Boolean,
        newEmail: String?,
        isNowEnabled: Boolean
    ) {
        // REMOVED: alerts were active and are now being disabled or the email changed
        if (wasEnabled && previousEmail != null && (!isNowEnabled || previousEmail != newEmail)) {
            postGuardianConfig(previousEmail, "REMOVED")
        }
        // ADDED: valid email configured that is new, changed, or re-enabled
        if (isNowEnabled && newEmail != null && (!wasEnabled || previousEmail != newEmail)) {
            postGuardianConfig(newEmail, "ADDED")
        }
    }

    private fun postGuardianConfig(email: String, action: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("deviceId",     consentManager.userId)
                    put("guardianEmail", email)
                    put("action", action)
                }
                val conn = URL(RemoteConfig.guardianConfigEndpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", RemoteConfig.apiKey)
                conn.doOutput = true
                conn.connectTimeout = RemoteConfig.connectTimeoutMs
                conn.readTimeout    = RemoteConfig.readTimeoutMs
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Guardian config notification failed ($action): ${e.message}")
            }
        }
    }

    private fun saveAgeCategory() {
        consentManager.userCategory = when {
            binding.cbUnder13.isChecked -> UserCategory.CHILD
            binding.cbUnder21.isChecked -> UserCategory.ADOLESCENT
            else                        -> UserCategory.SELF_PROTECTING_ADULT
        }
    }

    companion object {
        private const val TAG = "GuardianSetupActivity"
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
