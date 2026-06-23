package com.agenthita.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityTermsBinding
import com.agenthita.app.telemetry.TelemetryManager

class TermsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTermsBinding
    private lateinit var consentManager: ConsentManager
    private var hasScrolledToBottom = false
    private var agreed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentManager = ConsentManager(this)
        binding = ActivityTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        setupWebView()

        binding.btnAgree.setOnClickListener {
            agreed = true
            TelemetryManager.get(this).track("terms_accepted")
            TelemetryManager.get(this).flush()
            consentManager.hasAcceptedTerms = true
            consentManager.acceptedTermsVersion = ConsentManager.CURRENT_TERMS_VERSION
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!agreed) {
            TelemetryManager.get(this).track("terms_declined")
            TelemetryManager.get(this).flush()
        }
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.setSupportZoom(false)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    binding.progressBar.visibility = View.GONE
                    checkScrolledToBottom()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        // On load failure, allow agreement so the user isn't blocked
                        binding.progressBar.visibility = View.GONE
                        enableAgreement()
                    }
                }
            }

            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val contentHeight = contentHeight * scale
                val visibleBottom = scrollY + height
                if (visibleBottom >= contentHeight - 100) {
                    enableAgreement()
                }
            }

            loadUrl("file:///android_asset/privacy.html")
        }
    }

    private fun checkScrolledToBottom() {
        // If the page is short enough to fit without scrolling, enable immediately
        val contentHeight = binding.webView.contentHeight * binding.webView.scale
        if (contentHeight <= binding.webView.height) {
            enableAgreement()
        }
    }

    private fun enableAgreement() {
        if (hasScrolledToBottom) return
        hasScrolledToBottom = true
        binding.tvScrollHint.visibility = View.GONE
        binding.btnAgree.isEnabled = true
    }
}
