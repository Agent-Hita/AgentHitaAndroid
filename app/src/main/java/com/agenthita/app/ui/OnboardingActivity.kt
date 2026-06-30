package com.agenthita.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agenthita.app.R
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

        // Deep link intents (https://agenthita.org/app/*) open the app as an entry point only.
        // intent.data is deliberately not read — no URI parameters are trusted or processed.
        // If deep link routing ever needs to carry data, validate the URI strictly here before use.

        val routerState = OnboardingRouter.State(
            hasAcceptedHitaTerms    = consentManager.hasAcceptedTerms,
            isOnboardingComplete    = consentManager.isOnboardingComplete,
            isGuardianSetupComplete = consentManager.isGuardianSetupComplete,
            hasAcceptedGemmaTerms   = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
                                          .getBoolean("gemma_terms_accepted", false)
        )
        when (OnboardingRouter.nextDestination(routerState)) {
            OnboardingRouter.Destination.HITA_TERMS -> {
                startActivity(Intent(this, TermsActivity::class.java)); finish(); return
            }
            OnboardingRouter.Destination.GUARDIAN_SETUP -> {
                startActivity(Intent(this, GuardianSetupActivity::class.java)); finish(); return
            }
            OnboardingRouter.Destination.GEMMA_TERMS,
            OnboardingRouter.Destination.DASHBOARD -> {
                goToDashboard(); return
            }
            OnboardingRouter.Destination.ACCESSIBILITY_PERMISSION -> Unit // show this screen
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

        binding.btnGrantPermission.setOnClickListener { openAccessibilitySettings() }

        binding.btnContinue.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                consentManager.consentTimestampMs = System.currentTimeMillis()
                TelemetryManager.get(this).track("accessibility_permission_granted")
                TelemetryManager.get(this).flush()
                consentManager.isOnboardingComplete = true
                requestBatteryOptimizationExemption()
                startActivity(Intent(this, GuardianSetupActivity::class.java))
                finish()
            } else {
                binding.tvPermissionWarning.visibility = View.VISIBLE
            }
        }

        binding.btnDecline.setOnClickListener {
            TelemetryManager.get(this).track("accessibility_permission_declined")
            TelemetryManager.get(this).flush()
            AlertDialog.Builder(this, R.style.Dialog_AgentHita)
                .setTitle("Accessibility access required")
                .setMessage("Agent Hita cannot protect you without Accessibility access. You can grant it later from Settings → Accessibility → Agent Hita.")
                .setPositiveButton("Exit") { _, _ -> finish() }
                .setNegativeButton("Grant now") { _, _ -> openAccessibilitySettings() }
                .setCancelable(false)
                .show()
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

    private fun openAccessibilitySettings() {
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
