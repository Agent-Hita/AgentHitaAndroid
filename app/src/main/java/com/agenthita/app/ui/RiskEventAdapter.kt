package com.agenthita.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import androidx.core.graphics.drawable.DrawableCompat
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.agenthita.app.databinding.ItemRiskEventBinding
import com.agenthita.app.storage.RiskEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class RiskEventAdapter(
    private val onItemClick: (RiskEvent) -> Unit = {}
) : ListAdapter<RiskEvent, RiskEventAdapter.ViewHolder>(DIFF) {

    var nameMap: Map<String, String> = emptyMap()

    inner class ViewHolder(private val b: ItemRiskEventBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(event: RiskEvent) {
            b.root.setOnClickListener { onItemClick(event) }
            val color = event.riskLevel.toColor()
            b.viewRiskBar.setBackgroundColor(color)
            val displayName = nameMap[event.contactHash]
            b.tvSenderAvatar.text = displayName?.toInitials() ?: event.contactHash.take(2).uppercase()
            DrawableCompat.setTint(
                b.tvSenderAvatar.background.mutate(),
                event.contactHash.toAvatarColor()
            )
            b.tvCategory.text = event.harmCategory.toCategoryLabel()
            b.tvSignals.text = event.signals.toSignalSummary()
            b.tvRiskLevel.text = event.riskLevel
            b.tvRiskLevel.setTextColor(color)
            b.tvExplanation.text = event.explanation
            b.tvTime.text = event.timestampMs.toFullDateTime()
            b.tvApp.text = event.appPackage.toAppName()
            b.tvFalsePositiveBadge.visibility =
                if (event.feedbackState == "FALSE_POSITIVE") android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemRiskEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RiskEvent>() {
            override fun areItemsTheSame(a: RiskEvent, b: RiskEvent) = a.id == b.id
            override fun areContentsTheSame(a: RiskEvent, b: RiskEvent) = a == b
        }
    }
}

private fun String.toInitials(): String {
    val parts = trim().split(Regex("\\s+"))
    return if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}".uppercase()
    else take(2).uppercase()
}

private fun String.toColor() = when (this) {
    "HIGH"   -> Color.parseColor("#EF4444")
    "MEDIUM" -> Color.parseColor("#F59E0B")
    "LOW"    -> Color.parseColor("#10B981")
    else     -> Color.GRAY
}

private val AVATAR_COLORS = intArrayOf(
    0xFF5B8DEF.toInt(), 0xFF9D7CFF.toInt(), 0xFF10B981.toInt(),
    0xFFF59E0B.toInt(), 0xFFEF6C8A.toInt(), 0xFF6AC6FF.toInt(),
    0xFF7C6FCD.toInt(), 0xFF34D399.toInt()
)

private fun String.toAvatarColor(): Int {
    val index = (this.hashCode() and 0x7FFFFFFF) % AVATAR_COLORS.size
    return AVATAR_COLORS[index]
}

private fun String.toSignalSummary(): String {
    if (isBlank()) return ""
    val signals = split(",")
        .map { it.trim().replace("_", " ").lowercase().replaceFirstChar { c -> c.uppercase() } }
        .filter { it.isNotEmpty() }
        .take(3)
    return if (signals.isEmpty()) "" else "· ${signals.joinToString(" · ")}"
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

private fun Long.toFullDateTime(): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().also { it.timeInMillis = this }
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val pattern = if (sameYear) "MMM d · h:mm a" else "MMM d, yyyy · h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))
}

private fun String.toAppName() = when (this) {
    "com.whatsapp"                       -> "WhatsApp"
    "com.whatsapp.w4b"                   -> "WhatsApp Business"
    "com.instagram.android"             -> "Instagram"
    "com.facebook.orca"                  -> "Messenger"
    "org.telegram.messenger"             -> "Telegram"
    "com.snapchat.android"               -> "Snapchat"
    "com.discord"                        -> "Discord"
    "com.google.android.apps.messaging" -> "Messages"
    "com.samsung.android.messaging"     -> "Messages"
    "com.android.messaging"             -> "Messages"
    "com.android.mms"                   -> "Messages"
    else                                -> "Messages"
}
