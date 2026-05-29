package com.agenthita.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import androidx.core.graphics.drawable.DrawableCompat
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.HitaApplication
import com.agenthita.app.databinding.ActivityEventDetailBinding
import com.agenthita.app.storage.RiskEvent
import com.agenthita.app.storage.RiskEventStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (eventId == -1L) { finish(); return }

        val app = application as HitaApplication
        val store = RiskEventStore(app.database.riskEventDao())

        lifecycleScope.launch {
            val event = withContext(Dispatchers.IO) { store.getById(eventId) }
            if (event == null) { finish(); return@launch }
            bindEvent(event)
        }
    }

    private fun bindEvent(event: RiskEvent) {
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

        // Sender section
        binding.tvDetailSenderAvatar.text = event.contactHash.take(2).uppercase()
        DrawableCompat.setTint(
            binding.tvDetailSenderAvatar.background.mutate(),
            event.contactHash.toAvatarColor()
        )
        binding.tvDetailApp.text = event.appPackage.toAppName()
        binding.tvDetailIdentity.text = "Protected (privacy by design)"

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

        // AI safety analysis (Gemma if loaded, rules-based fallback otherwise)
        binding.tvGemmaAnalysis.text = event.gemmaAnalysis
            ?: "Generating analysis..."
        Linkify.addLinks(binding.tvGemmaAnalysis, Linkify.WEB_URLS)
        binding.tvGemmaAnalysis.movementMethod = LinkMovementMethod.getInstance()
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
        "SEXTORTION"        -> "Sexual Manipulation"
        "FINANCIAL_SCAM"    -> "Financial Scam"
        "GROOMING"          -> "Grooming"
        "ROMANCE_SCAM"      -> "Romance Scam"
        "IDENTITY_PHISHING" -> "Identity Phishing"
        "LURING"            -> "Luring / Fake Offer"
        "HARASSMENT"        -> "Harassment"
        else                -> this
    }

    /** Maps a signal type key to a human-readable label. */
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
        else                 -> replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    /**
     * Maps a signal type to a severity color.
     * Escalation / boundary testing / threat → danger red
     * Isolation / secrecy / urgency           → warning amber
     * Everything else                          → info blue
     */
    private fun String.toSignalColor(): Int = when (this) {
        "escalation",
        "boundary_testing",
        "threat",
        "photo_request",
        "location_request"  -> Color.parseColor("#EF4444") // danger
        "isolation",
        "secrecy",
        "urgency",
        "financial_pressure" -> Color.parseColor("#F59E0B") // warning
        else                 -> Color.parseColor("#5B8DEF") // info blue
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
