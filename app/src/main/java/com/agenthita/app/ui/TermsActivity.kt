package com.agenthita.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityTermsBinding

class TermsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTermsBinding
    private lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentManager = ConsentManager(this)
        binding = ActivityTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        binding.btnReadPolicy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.agenthita.org/privacy.html")))
        }

        binding.btnAgree.setOnClickListener {
            consentManager.hasAcceptedTerms = true
            consentManager.acceptedTermsVersion = ConsentManager.CURRENT_TERMS_VERSION
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
    }
}
