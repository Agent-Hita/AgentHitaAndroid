package com.agenthita.poc.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.drawable.DrawableCompat
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.poc.HitaApplication
import com.agenthita.poc.databinding.ActivityEventDetailBinding
import com.agenthita.poc.storage.RiskEvent
import com.agenthita.poc.storage.RiskEventStore
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
        binding.tvDetailHash.text = "…${event.contactHash.takeLast(16)}"

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
                chip.text = signal.replace("_", " ").replaceFirstChar { it.uppercase() }
                chip.isClickable = false
                chip.setChipBackgroundColorResource(android.R.color.transparent)
                chip.setChipStrokeColorResource(android.R.color.darker_gray)
                chip.chipStrokeWidth = 1f
                chip.setTextColor(Color.parseColor("#3A4A6B"))
                binding.chipGroupSignals.addView(chip)
            }

        // Explanation
        binding.tvDetailExplanation.text = event.explanation
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
