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

    // Tracks when the last warning was posted so we know whether to cancel-first.
    // Volatile: showWarning can be called from IO coroutines, dismissWarning from main.
    @Volatile private var lastWarningTimeMs = 0L

    /**
     * Shows a safety warning to the user — always fires BEFORE any guardian alert.
     * Anti-coercion requirement: user is always first to know (consent.html safeguard #6).
     *
     * If more than SESSION_TIMEOUT_MS has elapsed since the last warning (e.g. user left
     * the app and came back), the old notification is cancelled first so this one appears
     * as a fresh heads-up banner rather than a silent update.
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

        val now = System.currentTimeMillis()
        // Cancel the existing notification if enough time has passed — this forces
        // the OS to treat the next notify() as a new notification with full heads-up.
        if (now - lastWarningTimeMs > SESSION_TIMEOUT_MS) {
            notificationManager.cancel(NOTIFICATION_ID_WARNING)
        }
        lastWarningTimeMs = now

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
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_WARNING, notification)
    }

    /**
     * Dismisses the current warning notification and resets the session timer so the
     * next detection always shows a fresh heads-up. Call this when a conversation
     * session ends (user navigates back to chat list or switches conversations).
     */
    fun dismissWarning() {
        notificationManager.cancel(NOTIFICATION_ID_WARNING)
        lastWarningTimeMs = 0L
    }

    /**
     * Persistent low-priority notification shown whenever the listener service is running.
     * Non-dismissable — satisfies the anti-coercion transparency requirement:
     * the monitored person always knows Agent Hita is active (consent.html safeguard #1).
     *
     * Also used as the foreground-service notification via [buildStatusNotification] so that
     * startForeground() can pin the process and prevent Samsung's FreecessController from
     * freezing the accessibility service between events.
     */
    fun showStatusIndicator() {
        notificationManager.notify(NOTIFICATION_ID_STATUS, buildStatusNotification())
    }

    fun buildStatusNotification(): android.app.Notification {
        val intent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, HitaApplication.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_hita_shield)
            .setContentTitle("Agent Hita is active")
            .setContentText("Monitoring for harmful patterns. Tap to manage settings.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setNumber(0)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun dismissStatusIndicator() {
        notificationManager.cancel(NOTIFICATION_ID_STATUS)
    }

    /**
     * Notifies the user that the other party turned on disappearing messages.
     * This is a structural signal (not content-based) so it gets its own fixed
     * notification ID — it does not replace the content-warning notification.
     */
    fun showDisappearingMessagesWarning() {
        val intent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, HitaApplication.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_hita_shield)
            .setContentTitle("Agent Hita: Disappearing messages turned on")
            .setContentText("The other person enabled disappearing messages — this is often a sign of secrecy or hidden intent. Tap to learn more.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "The other person enabled disappearing messages — this is often a sign of secrecy or hidden intent. " +
                "Screenshot the conversation now if you need to preserve evidence."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID_DISAPPEARING, notification)
    }

    companion object {
        private const val NOTIFICATION_ID_STATUS       = 1000
        private const val NOTIFICATION_ID_WARNING      = 1001
        private const val NOTIFICATION_ID_DISAPPEARING = 1002
        // After this gap, the next warning cancels the old notification so it
        // shows as a fresh heads-up rather than a silent update.
        private const val SESSION_TIMEOUT_MS           = 5 * 60 * 1000L  // 5 minutes
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
        HarmCategory.HARASSMENT             -> "harassment, threats, or stalking"
        HarmCategory.DISAPPEARING_MESSAGES  -> "disappearing messages (secrecy signal)"
    }
