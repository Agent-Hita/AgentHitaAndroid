package com.agenthita.app.alert

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.agenthita.app.HitaApplication
import com.agenthita.app.R
import com.agenthita.app.detection.DetectionResult
import com.agenthita.app.detection.HarmCategory
import com.agenthita.app.detection.RiskLevel
import com.agenthita.app.ui.DashboardActivity

class LocalNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Shows a safety warning to the user — always fires BEFORE any guardian alert.
     * Anti-coercion requirement: user is always first to know (consent.html safeguard #6).
     */
    fun showWarning(result: DetectionResult) {
        val (title, body) = when (result.riskLevel) {
            RiskLevel.HIGH -> Pair(
                "⚠️ Agent Hita: High-risk pattern detected",
                "Someone in this conversation is using tactics commonly seen in ${result.category.displayName}. Tap to learn more."
            )
            RiskLevel.MEDIUM -> Pair(
                "⚠️ Agent Hita: Warning signs detected",
                "This conversation has some warning signs. Tap to learn more."
            )
            else -> return
        }

        val intent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, HitaApplication.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_hita_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_WARNING, notification)
    }

    /**
     * Persistent low-priority notification shown whenever the listener service is running.
     * Non-dismissable — satisfies the anti-coercion transparency requirement:
     * the monitored person always knows Agent Hita is active (consent.html safeguard #1).
     */
    fun showStatusIndicator() {
        val notification = NotificationCompat.Builder(context, HitaApplication.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_hita_shield)
            .setContentTitle("Agent Hita is active")
            .setContentText("Monitoring for harmful patterns. Tap to manage settings.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_STATUS, notification)
    }

    fun dismissStatusIndicator() {
        notificationManager.cancel(NOTIFICATION_ID_STATUS)
    }

    companion object {
        private const val NOTIFICATION_ID_STATUS  = 1000
        private const val NOTIFICATION_ID_WARNING = 1001
    }
}

private val HarmCategory.displayName: String
    get() = when (this) {
        HarmCategory.SEXTORTION        -> "sexual manipulation or sextortion"
        HarmCategory.FINANCIAL_SCAM    -> "financial scams or coercion"
        HarmCategory.GROOMING          -> "predatory grooming behaviour"
        HarmCategory.ROMANCE_SCAM      -> "romance scam or fake relationship"
        HarmCategory.IDENTITY_PHISHING -> "identity phishing or credential theft"
        HarmCategory.LURING            -> "luring via fake job or offer"
        HarmCategory.HARASSMENT        -> "harassment, threats, or stalking"
        HarmCategory.DISAPPEARING_MESSAGES -> "disappearing or ephemeral message activity"
    }
