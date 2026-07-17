package com.agenthita.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.agenthita.app.R
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.consent.GemmaTerms
import com.agenthita.app.databinding.ActivityTermsBinding
import com.agenthita.app.telemetry.TelemetryManager

class TermsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTermsBinding
    private lateinit var consentManager: ConsentManager
    private var hasScrolledToBottom = false
    private var agreed = false
    private var declined = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consentManager = ConsentManager(this)
        binding = ActivityTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        setupWebView()

        binding.tvGemmaTermsLink.setOnClickListener { showGemmaTermsDialog() }

        binding.btnAgree.setOnClickListener {
            agreed = true
            // Single explicit acceptance covers both the Agent Hita Terms and the
            // Gemma Model Terms (statement shown above the button). The Gemma
            // provisions bind if/when the user downloads and uses the model.
            TelemetryManager.get(this).track("hita_terms_accepted")
            TelemetryManager.get(this).track("gemma_terms_accepted")
            TelemetryManager.get(this).flush()
            consentManager.hasAcceptedTerms = true
            consentManager.acceptedTermsVersion = ConsentManager.CURRENT_TERMS_VERSION
            getSharedPreferences("hita_ai_prefs", MODE_PRIVATE).edit()
                .putBoolean("gemma_terms_accepted", true)
                .apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }

        binding.btnDecline.setOnClickListener {
            declined = true
            TelemetryManager.get(this).track("hita_terms_declined")
            TelemetryManager.get(this).flush()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!agreed && !declined) {
            TelemetryManager.get(this).track("hita_terms_declined")
            TelemetryManager.get(this).flush()
        }
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.setSupportZoom(false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // Open external https links in the browser; swallow local asset links
                    // that don't resolve (e.g. consent.html, vision.html — not shipped as assets)
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }

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

    /** Read-only view of the Gemma Model Terms, linked from the consent statement. */
    private fun showGemmaTermsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_gemma_terms, null)
        view.findViewById<android.widget.TextView>(R.id.tv_terms_content).text = GemmaTerms.TEXT
        view.findViewById<android.widget.TextView>(R.id.tv_scroll_hint).visibility = View.GONE
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_not_now)
            .visibility = View.GONE
        val btnClose = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_accept_download)
        btnClose.text = "Close"
        btnClose.isEnabled = true
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setView(view)
            .create()
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
