package com.agenthita.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.R
import com.agenthita.app.alert.GuardianAlertDecision
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.security.DeviceTokenManager
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
        consentManager.monitoredUserName?.let { binding.etMonitoredName.setText(it) }
        consentManager.guardianEmail?.let { binding.etGuardianEmail.setText(it) }
        binding.switchAlerts.isChecked = consentManager.isGuardianAlertsEnabled
        updateAlertsLabel(binding.switchAlerts.isChecked)
        binding.switchAlerts.setOnCheckedChangeListener { _, isChecked ->
            updateAlertsLabel(isChecked)
        }

        // Pre-fill RadioGroup from saved category
        val radioId = when (consentManager.userCategory) {
            UserCategory.CHILD               -> R.id.rb_under_13
            UserCategory.ADOLESCENT          -> R.id.rb_under_21
            UserCategory.VULNERABLE_ADULT    -> R.id.rb_vulnerable_adult
            UserCategory.SELF_PROTECTING_ADULT -> R.id.rb_self
        }
        binding.rgUserCategory.check(radioId)

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
            val alertsEnabled   = emailValid && binding.switchAlerts.isChecked

            if (emailValid) {
                consentManager.guardianEmail = email
            }
            consentManager.isGuardianAlertsEnabled = alertsEnabled
            if (!emailValid) binding.switchAlerts.isChecked = false

            notifyGuardianChange(previousEmail, wasEnabled, email.ifEmpty { null }, alertsEnabled)
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
        GuardianAlertDecision.computeChanges(previousEmail, wasEnabled, newEmail, isNowEnabled)
            .forEach { postGuardianConfig(it.email, it.action) }
    }

    private fun postGuardianConfig(email: String, action: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = DeviceTokenManager.getToken(this@GuardianSetupActivity)
                val payload = JSONObject().apply {
                    put("deviceId",     consentManager.userId)
                    put("guardianEmail", email)
                    put("action", action)
                }
                val conn = URL(RemoteConfig.guardianConfigEndpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Device-Token", token)
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
        consentManager.userCategory = when (binding.rgUserCategory.checkedRadioButtonId) {
            R.id.rb_under_13        -> UserCategory.CHILD
            R.id.rb_under_21        -> UserCategory.ADOLESCENT
            R.id.rb_vulnerable_adult -> UserCategory.VULNERABLE_ADULT
            else                    -> UserCategory.SELF_PROTECTING_ADULT
        }
        consentManager.monitoredUserName = binding.etMonitoredName.text?.toString()?.trim()
    }

    private fun updateAlertsLabel(isChecked: Boolean) {
        binding.tvAlertsLabel.text = GuardianAlertDecision.alertsLabel(isChecked)
    }

    companion object {
        private const val TAG = "GuardianSetupActivity"
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
