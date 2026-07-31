package com.agenthita.app.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityNotificationSettingsBinding

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding
    private lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
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

        binding.switchHighRiskOnly.isChecked = consentManager.notifyOnlyHighRiskEnabled
        updateHighRiskOnlyLabel(binding.switchHighRiskOnly.isChecked)
        binding.switchHighRiskOnly.setOnCheckedChangeListener { _, isChecked ->
            consentManager.notifyOnlyHighRiskEnabled = isChecked
            updateHighRiskOnlyLabel(isChecked)
        }
    }

    private fun updateHighRiskOnlyLabel(isChecked: Boolean) {
        binding.tvHighRiskOnlyLabel.text =
            if (isChecked) "Notify for high-risk only (ON)" else "Notify for high-risk only (OFF)"
    }
}
