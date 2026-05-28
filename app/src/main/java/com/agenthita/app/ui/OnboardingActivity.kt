package com.agenthita.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var consentManager: ConsentManager

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether granted or denied, proceed — we degrade gracefully without it
            updateContinueButton()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentManager = ConsentManager(this)

        if (consentManager.isOnboardingComplete) {
            goToDashboard()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        // Request POST_NOTIFICATIONS on Android 13+ (required to show warnings to the user)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnContinue.setOnClickListener {
            if (isNotificationListenerEnabled()) {
                consentManager.isOnboardingComplete = true
                consentManager.consentTimestampMs = System.currentTimeMillis()
                startActivity(Intent(this, GuardianSetupActivity::class.java))
                finish()
            } else {
                binding.tvPermissionWarning.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateContinueButton()
        }
    }

    private fun updateContinueButton() {
        binding.btnContinue.isEnabled = isNotificationListenerEnabled()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
