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
import com.agenthita.app.telemetry.TelemetryManager

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

        if (!consentManager.hasAcceptedTerms) {
            startActivity(Intent(this, TermsActivity::class.java))
            finish()
            return
        }

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
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val componentName = "$packageName/.service.HitaAccessibilityService"
                val args = Bundle().apply {
                    putString(":settings:fragment_args_key", componentName)
                }
                putExtra(":settings:show_fragment_args", args)
                putExtra(":settings:fragment_args_key", componentName)
            }
            startActivity(intent)
        }

        binding.btnContinue.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                consentManager.isOnboardingComplete = true
                consentManager.consentTimestampMs = System.currentTimeMillis()
                TelemetryManager.get(this).track("permission_notifications_granted")
                TelemetryManager.get(this).flush()
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
        binding.btnContinue.isEnabled = isAccessibilityServiceEnabled()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
