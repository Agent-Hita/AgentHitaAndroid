package com.agenthita.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.R
import com.agenthita.app.alert.GuardianAlertDecision
import com.agenthita.app.alert.GuardianConfigClient
import com.agenthita.app.alert.GuardianEmailStatus
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.consent.NotificationPreferenceDecision
import com.agenthita.sdk.detection.UserCategory
import com.agenthita.app.databinding.ActivityGuardianSetupBinding
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GuardianSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSetupBinding
    private lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGuardianSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
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

        refreshGuardianStatus()

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
            consentManager.isGuardianSetupComplete = true
            goToDashboard()
        }

        binding.btnSave.setOnClickListener {
            val rawInput = binding.etGuardianEmail.text.toString().trim()
            val parsedEmails = parseGuardianEmails(rawInput)
            val emailValid = rawInput.isNotEmpty() && parsedEmails != null
            val email = parsedEmails?.joinToString(", ") ?: rawInput

            if (rawInput.isNotEmpty() && !emailValid) {
                binding.etGuardianEmail.error = "Enter up to two valid email addresses, separated by a comma"
                return@setOnClickListener
            }

            val previousEmail   = consentManager.guardianEmail
            val wasEnabled      = consentManager.isGuardianAlertsEnabled
            if (GuardianAlertDecision.shouldAutoEnable(previousEmail, emailValid)) binding.switchAlerts.isChecked = true
            val alertsEnabled   = emailValid && binding.switchAlerts.isChecked

            if (emailValid) {
                consentManager.guardianEmail = email
            }
            consentManager.isGuardianAlertsEnabled = alertsEnabled
            if (!emailValid) binding.switchAlerts.isChecked = false

            val notifyDeferred = notifyGuardianChange(previousEmail, wasEnabled, email.ifEmpty { null }, alertsEnabled)
            saveAgeCategory()
            consentManager.isGuardianSetupComplete = true

            // Await (briefly) so we can tell the user whether a confirmation email
            // is now required before we navigate away — the actual backend calls
            // themselves run on GlobalScope below and complete regardless of
            // whether this activity is still around to hear back.
            lifecycleScope.launch {
                val pendingConfirmation = withTimeoutOrNull(GUARDIAN_NOTIFY_TIMEOUT_MS) { notifyDeferred.await() }
                pendingConfirmation?.let { pending ->
                    Toast.makeText(
                        this@GuardianSetupActivity,
                        if (pending) "Confirmation email sent — alerts start once your guardian confirms."
                        else "Guardian alerts are active.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                goToDashboard()
            }
        }
    }

    companion object {
        private const val MAX_GUARDIAN_EMAILS = 2
        private const val GUARDIAN_NOTIFY_TIMEOUT_MS = 4_000L
    }

    /**
     * Fires the backend notification(s) for whatever guardian changes just happened
     * (ADDED/REMOVED, possibly both — e.g. swapping one address for another) on
     * GlobalScope so they always complete even if this Activity doesn't survive to
     * see the result. Returns a Deferred the caller can (optionally, with a
     * timeout) await purely for UI feedback — the network calls aren't gated on it.
     *
     * @return the last ADDED action's pendingConfirmation, or null if nothing was ADDED.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun notifyGuardianChange(
        previousEmail: String?,
        wasEnabled: Boolean,
        newEmail: String?,
        isNowEnabled: Boolean
    ): Deferred<Boolean?> {
        val changes = GuardianAlertDecision.computeChanges(previousEmail, wasEnabled, newEmail, isNowEnabled)
        return GlobalScope.async(Dispatchers.IO) {
            var pendingConfirmation: Boolean? = null
            for (change in changes) {
                val result = GuardianConfigClient.postGuardianConfig(
                    this@GuardianSetupActivity, consentManager, change.email, change.action
                )
                if (change.action == "ADDED") pendingConfirmation = result.pendingConfirmation
            }
            pendingConfirmation
        }
    }

    /** Fetches and renders per-address confirmed/pending status for whatever guardian
     *  email(s) are already saved. No-ops (leaves the status view hidden) if nothing is
     *  configured yet, or the fetch fails — this is a best-effort status check, not a
     *  requirement for Guardian Setup to otherwise function. */
    private fun refreshGuardianStatus() {
        if (consentManager.guardianEmail.isNullOrBlank()) return
        lifecycleScope.launch {
            val statuses = GuardianConfigClient.fetchGuardianStatus(this@GuardianSetupActivity)
            renderGuardianStatus(statuses)
        }
    }

    private fun renderGuardianStatus(statuses: List<GuardianEmailStatus>?) {
        if (statuses.isNullOrEmpty()) {
            binding.tvGuardianStatus.visibility = View.GONE
            return
        }
        binding.tvGuardianStatus.text = statuses.joinToString("\n") { status ->
            if (status.confirmed) "✓ ${status.email} confirmed"
            else "⏳ ${status.email} — check their inbox to confirm"
        }
        binding.tvGuardianStatus.visibility = View.VISIBLE
    }

    /**
     * Splits [raw] on commas/semicolons and validates each part as an email address.
     * Returns null if empty, any part is invalid, or more than [MAX_GUARDIAN_EMAILS] addresses are given.
     */
    private fun parseGuardianEmails(raw: String): List<String>? {
        if (raw.isEmpty()) return null
        val parts = raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_GUARDIAN_EMAILS) return null
        return parts.takeIf { all -> all.all { Patterns.EMAIL_ADDRESS.matcher(it).matches() } }
    }

    private fun saveAgeCategory() {
        val category = when (binding.rgUserCategory.checkedRadioButtonId) {
            R.id.rb_under_13        -> UserCategory.CHILD
            R.id.rb_under_21        -> UserCategory.ADOLESCENT
            R.id.rb_vulnerable_adult -> UserCategory.VULNERABLE_ADULT
            else                    -> UserCategory.SELF_PROTECTING_ADULT
        }
        val previousCategory = consentManager.userCategory
        if (NotificationPreferenceDecision.shouldResetToDefault(
                previousCategory = previousCategory,
                newCategory = category,
                isFirstTimeSetup = !consentManager.isGuardianSetupComplete
            )
        ) {
            consentManager.notifyOnlyHighRiskEnabled =
                NotificationPreferenceDecision.defaultNotifyOnlyHighRisk(category)
        }
        consentManager.userCategory = category
        consentManager.monitoredUserName = binding.etMonitoredName.text?.toString()?.trim()
    }

    private fun updateAlertsLabel(isChecked: Boolean) {
        binding.tvAlertsLabel.text = GuardianAlertDecision.alertsLabel(isChecked)
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
