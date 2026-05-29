package com.agenthita.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.consent.UserCategory
import com.agenthita.app.databinding.ActivityGuardianSetupBinding

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

            if (binding.switchAlerts.isChecked) {
                if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    binding.etGuardianEmail.error = "Please enter a valid email address"
                    return@setOnClickListener
                }
                consentManager.guardianEmail = email
                consentManager.isGuardianAlertsEnabled = true
            } else {
                consentManager.isGuardianAlertsEnabled = false
            }
            saveAgeCategory()
            goToDashboard()
        }
    }

    private fun saveAgeCategory() {
        consentManager.userCategory = when {
            binding.cbUnder13.isChecked -> UserCategory.CHILD
            binding.cbUnder21.isChecked -> UserCategory.ADOLESCENT
            else                        -> UserCategory.SELF_PROTECTING_ADULT
        }
    }

    private fun goToDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
