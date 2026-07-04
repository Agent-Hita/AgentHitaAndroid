package com.agenthita.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.DrawableCompat
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.HitaApplication
import com.agenthita.app.R
import com.agenthita.app.alert.FalsePositiveSender
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityEventDetailBinding
import com.agenthita.app.storage.ContactNameDao
import com.agenthita.app.storage.RiskEvent
import com.agenthita.app.storage.RiskEventStore
import com.agenthita.app.telemetry.TelemetryManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EventDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventDetailBinding
    private lateinit var store: RiskEventStore
    private lateinit var userCategory: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (eventId == -1L) { finish(); return }

        val app = application as HitaApplication
        store = RiskEventStore(app.database.riskEventDao())
        userCategory = ConsentManager(applicationContext).userCategory.name
        val contactNameDao: ContactNameDao = app.database.contactNameDao()

        lifecycleScope.launch {
            val event = withContext(Dispatchers.IO) { store.getById(eventId) }
            if (event == null) { finish(); return@launch }
            val displayName = withContext(Dispatchers.IO) { contactNameDao.getName(event.contactHash) }
            bindEvent(event, displayName)
        }
    }

    private fun bindEvent(event: RiskEvent, displayName: String? = null) {
        val riskColor = when (event.riskLevel) {
            "HIGH"   -> Color.parseColor("#EF4444")
            "MEDIUM" -> Color.parseColor("#F59E0B")
            "LOW"    -> Color.parseColor("#10B981")
            else     -> Color.GRAY
        }

        // Risk badge
        val badgeDrawable = (binding.tvRiskBadge.background as? GradientDrawable)
            ?: GradientDrawable().also {
                it.shape = GradientDrawable.RECTANGLE
                it.cornerRadius = 8f * resources.displayMetrics.density
            }
        badgeDrawable.setColor(riskColor)
        binding.tvRiskBadge.background = badgeDrawable
        binding.tvRiskBadge.text = "${event.riskLevel} RISK"

        // False-alarm flag button — only for HIGH and MEDIUM, hidden if already reported
        val showFlag = event.riskLevel in setOf("HIGH", "MEDIUM")
        if (showFlag) {
            if (event.feedbackState == "FALSE_POSITIVE") {
                showReportedState()
            } else {
                binding.btnFalsePositive.visibility = View.VISIBLE
                binding.btnFalsePositive.setOnClickListener {
                    showConsentDialog(event)
                }
            }
        }

        // Sender section
        binding.tvDetailSenderAvatar.text = displayName?.toInitials() ?: event.contactHash.take(2).uppercase()
        DrawableCompat.setTint(
            binding.tvDetailSenderAvatar.background.mutate(),
            event.contactHash.toAvatarColor()
        )
        binding.tvDetailApp.text = event.appPackage.toAppName()
        binding.tvDetailIdentity.text = displayName ?: "Unknown contact"

        // Detection section
        binding.tvDetailCategory.text = event.harmCategory.toCategoryLabel()
        binding.tvDetailScore.text = "${"%.0f".format(event.score * 100)}% confidence"
        binding.tvDetailTime.text = event.timestampMs.toFullDateTime()
        binding.tvDetailGuardian.text = if (event.guardianAlertSent) "Alert sent" else "Not notified"

        // Signals chips
        event.signals.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { signal ->
                val chip = Chip(this)
                chip.text = signal.toSignalLabel()
                chip.isClickable = false
                chip.isCheckable = false
                chip.chipStrokeWidth = 0f
                val bgColor = signal.toSignalColor()
                chip.setChipBackgroundColor(
                    android.content.res.ColorStateList.valueOf(bgColor)
                )
                chip.setTextColor(Color.WHITE)
                chip.textSize = 13f
                binding.chipGroupSignals.addView(chip)
            }

        // Explanation
        binding.tvDetailExplanation.text = event.explanation

        // AI safety analysis
        binding.tvGemmaAnalysis.text = event.gemmaAnalysis ?: "Generating analysis..."
        Linkify.addLinks(binding.tvGemmaAnalysis, Linkify.WEB_URLS)
        binding.tvGemmaAnalysis.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun showConsentDialog(event: RiskEvent) {
        val view = layoutInflater.inflate(R.layout.dialog_false_positive_consent, null)

        val tvPreview   = view.findViewById<android.widget.TextView>(R.id.tv_consent_preview)
        val scrollView  = view.findViewById<android.widget.ScrollView>(R.id.scroll_consent)
        val tvScrollHint = view.findViewById<android.widget.TextView>(R.id.tv_scroll_hint)
        val btnShare    = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share_report)
        val btnCancel   = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)

        tvPreview.text = FalsePositiveSender.buildConsentPreview(event, userCategory)

        val dialog = AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setView(view)
            .create()

        fun onScrolled() {
            if (!scrollView.canScrollVertically(1)) tvScrollHint.visibility = View.GONE
        }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> onScrolled() }
        scrollView.post { onScrolled() }

        btnShare.setOnClickListener  { dialog.dismiss(); submitFalsePositive(event) }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun submitFalsePositive(event: RiskEvent) {
        binding.btnFalsePositive.isEnabled = false
        binding.btnFalsePositive.text = "Sending…"

        lifecycleScope.launch {
            val error = FalsePositiveSender.send(this@EventDetailActivity, event)
            if (error == null) {
                withContext(Dispatchers.IO) { store.markFalsePositive(event.id) }
                TelemetryManager.get(this@EventDetailActivity).track("false_positive_reported")
                showReportedState()
            } else {
                binding.btnFalsePositive.isEnabled = true
                binding.btnFalsePositive.text = "Flag as false alarm"
                Toast.makeText(this@EventDetailActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showReportedState() {
        binding.btnFalsePositive.visibility = View.VISIBLE
        binding.btnFalsePositive.isEnabled = false
        binding.btnFalsePositive.text = "Reported — thank you"
    }

    private fun String.toInitials(): String {
        val parts = trim().split(Regex("\\s+"))
        return if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}".uppercase()
        else take(2).uppercase()
    }

    private fun String.toAppName() = when (this) {
        "com.whatsapp"                        -> "WhatsApp"
        "com.whatsapp.w4b"                    -> "WhatsApp Business"
        "com.instagram.android"               -> "Instagram"
        "com.facebook.orca"                   -> "Messenger"
        "org.telegram.messenger"              -> "Telegram"
        "com.snapchat.android"                -> "Snapchat"
        "com.discord"                         -> "Discord"
        "com.google.android.apps.messaging"   -> "Messages (Google)"
        "com.samsung.android.messaging"       -> "Messages (Samsung)"
        "com.android.messaging"               -> "Messages"
        "com.android.mms"                     -> "Messages"
        else                                  -> this
    }

    private fun String.toCategoryLabel() = when (this) {
        "SEXTORTION"            -> "Sexual Manipulation"
        "FINANCIAL_SCAM"        -> "Financial Scam"
        "GROOMING"              -> "Grooming"
        "ROMANCE_SCAM"          -> "Romance Scam"
        "IDENTITY_PHISHING"     -> "Identity Phishing"
        "LURING"                -> "Luring / Fake Offer"
        "HARASSMENT"            -> "Harassment"
        "DISAPPEARING_MESSAGES" -> "Disappearing Messages"
        else                    -> this
    }

    private fun String.toSignalLabel() = when (this) {
        "age_probing"        -> "Age probing"
        "trust_building"     -> "Trust building"
        "isolation"          -> "Isolation tactic"
        "boundary_testing"   -> "Boundary testing"
        "escalation"         -> "Escalation"
        "secrecy"            -> "Secrecy demand"
        "photo_request"      -> "Photo request"
        "location_request"   -> "Location request"
        "urgency"            -> "Urgency pressure"
        "financial_pressure" -> "Financial pressure"
        "romance_bait"       -> "Romance bait"
        "threat"             -> "Threat"
        "identity_harvest"   -> "Identity harvesting"
        "fake_offer"         -> "Fake offer"
        "ai_analysis"        -> "AI analysis"
        "activation_confirmed" -> "Messages set to disappear"
        "activation_request"   -> "Disappearing mode requested"
        "view_once"            -> "View-once request"
        "hiding_intent"        -> "Hiding intent"
        else                 -> replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun String.toSignalColor(): Int = when (this) {
        "escalation",
        "boundary_testing",
        "threat",
        "photo_request",
        "location_request",
        "activation_confirmed" -> Color.parseColor("#EF4444")
        "isolation",
        "secrecy",
        "urgency",
        "financial_pressure",
        "activation_request",
        "view_once"            -> Color.parseColor("#F59E0B")
        else                   -> Color.parseColor("#5B8DEF")
    }

    private fun String.toAvatarColor(): Int {
        val colors = intArrayOf(
            0xFF5B8DEF.toInt(), 0xFF9D7CFF.toInt(), 0xFF10B981.toInt(),
            0xFFF59E0B.toInt(), 0xFFEF6C8A.toInt(), 0xFF6AC6FF.toInt(),
            0xFF7C6FCD.toInt(), 0xFF34D399.toInt()
        )
        return colors[(this.hashCode() and 0x7FFFFFFF) % colors.size]
    }

    private fun Long.toFullDateTime(): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().also { it.timeInMillis = this }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val pattern = if (sameYear) "MMM d · h:mm a" else "MMM d, yyyy · h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
    }
}
