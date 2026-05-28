package com.agenthita.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.agenthita.app.HitaApplication
import com.agenthita.app.alert.GuardianAlertSender
import com.agenthita.app.alert.LocalNotificationManager
import com.agenthita.app.consent.AntiCoercionMonitor
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.detection.ConversationBuffer
import com.agenthita.app.detection.RiskLevel
import com.agenthita.app.detection.RiskScorer
import com.agenthita.app.model.OnDeviceClassifier
import com.agenthita.app.storage.RiskEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Core service: captures notifications from messaging apps and runs them through
 * the detection pipeline entirely on-device.
 *
 * Permission required: android.permission.BIND_NOTIFICATION_LISTENER_SERVICE
 * User must grant access via Settings > Apps > Special app access > Notification access.
 *
 * Alert ordering (anti-coercion safeguard #6):
 *   1. User receives local notification
 *   2. Guardian alert dispatched (only if user was already notified)
 */
class HitaNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // riskScorer starts with a stub classifier and upgrades to Gemma once loaded
    private lateinit var riskScorer: RiskScorer
    private val conversationBuffer = ConversationBuffer(maxMessages = 5)
    private lateinit var localNotificationManager: LocalNotificationManager
    private lateinit var guardianAlertSender: GuardianAlertSender
    private lateinit var riskEventStore: RiskEventStore
    private lateinit var consentManager: ConsentManager
    private lateinit var antiCoercionMonitor: AntiCoercionMonitor
    private lateinit var classifier: com.agenthita.app.model.OnDeviceClassifier

    private fun packageToAppName(pkg: String) = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b"          -> "WhatsApp"
        "com.instagram.android"                      -> "Instagram"
        "com.facebook.orca"                          -> "Messenger"
        "org.telegram.messenger"                     -> "Telegram"
        "com.snapchat.android"                       -> "Snapchat"
        "com.discord"                                -> "Discord"
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.android.mms"                            -> "Messages"
        else                                         -> pkg
    }

    // Messaging apps that carry interpersonal communication
    private val targetPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.facebook.orca",              // Messenger
        "org.telegram.messenger",
        "com.snapchat.android",
        "com.discord",
        "com.google.android.apps.messaging", // Google Messages (SMS/RCS)
        "com.samsung.android.messaging",
        "com.android.messaging",          // AOSP / emulator default SMS app
        "com.android.mms"                 // Legacy AOSP MMS app
    )

    override fun onCreate() {
        super.onCreate()
        val app = application as HitaApplication

        // Initialise everything that is lightweight synchronously so the
        // service is ready to intercept notifications immediately
        classifier               = OnDeviceClassifier()           // stub — no model yet
        riskScorer               = RiskScorer(classifier)
        localNotificationManager = LocalNotificationManager(this)
        consentManager           = ConsentManager(this)
        guardianAlertSender      = GuardianAlertSender(this, consentManager)
        riskEventStore           = RiskEventStore(app.database.riskEventDao())
        antiCoercionMonitor      = AntiCoercionMonitor(this, consentManager)

        android.util.Log.i("HitaNotificationListener", "Service created — monitoring active (rules-only mode)")
        localNotificationManager.showStatusIndicator()

        // Load Gemma in the background — service keeps running in rules-only
        // mode until the model is ready, then upgrades automatically
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                android.util.Log.i("HitaNotificationListener", "Loading Gemma model in background…")
                val gemmaClassifier = OnDeviceClassifier(this@HitaNotificationListener)
                if (gemmaClassifier.isLoaded) {
                    classifier.upgrade(gemmaClassifier)
                    android.util.Log.i("HitaNotificationListener", "Gemma ready — switching to ML-assisted mode")
                } else {
                    android.util.Log.i("HitaNotificationListener", "Gemma not available — staying in rules-only mode")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        localNotificationManager.dismissStatusIndicator()
    }

    /**
     * Extracts concatenated message text from MessagingStyle notifications.
     * Modern SMS and chat apps (WhatsApp, Google Messages, etc.) use this style.
     * Falls back to empty string if not a MessagingStyle notification.
     */
    private fun extractMessagingStyleText(extras: android.os.Bundle): String {
        val messages = extras.getParcelableArray("android.messages") ?: return ""
        // Only score the latest message — joining all history causes duplication
        // and inflates scores with repeated content
        return messages.lastOrNull()
            ?.let { (it as? android.os.Bundle)?.getCharSequence("text")?.toString() }
            ?: ""
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        android.util.Log.d("HitaNotificationListener", "Notification received from: ${sbn.packageName}")
        if (!consentManager.isOnboardingComplete) {
            android.util.Log.d("HitaNotificationListener", "Skipping — onboarding not complete")
            return
        }
        if (sbn.packageName !in targetPackages) {
            android.util.Log.d("HitaNotificationListener", "Skipping — package not in target list")
            return
        }
        if (sbn.isOngoing) return // Skip system/ongoing notifications

        val extras = sbn.notification.extras
        val title   = extras.getString("android.title") ?: ""
        val text    = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

        // MessagingStyle notifications (used by most modern SMS/chat apps) store messages
        // in android.messages as a Parcelable array of bundles, each with a "text" key
        val messagingText = extractMessagingStyleText(extras)

        val messageText = when {
            messagingText.isNotBlank() -> messagingText
            bigText.isNotBlank()       -> bigText
            else                       -> text
        }

        android.util.Log.d("HitaNotificationListener", "Extracted text: \"$messageText\"")
        if (messageText.isBlank()) return

        // Safe exit check — if user is looking for help, suppress analysis and surface resources
        val safeExit = antiCoercionMonitor.checkForSafeExitIntent(messageText)
        if (safeExit.shouldSuppressAnalysis) return

        // Contact identifier: use notification title (usually sender name), fall back to package
        val contactIdentifier = title.ifBlank { sbn.packageName }

        // Buffer message and build context window before scoring
        conversationBuffer.add(sbn.packageName, contactIdentifier, messageText)
        val context = conversationBuffer.getContext(sbn.packageName, contactIdentifier)

        serviceScope.launch {
            android.util.Log.d("HitaNotificationListener", "Scoring message: \"$messageText\" (context: ${context.size - 1} prior messages)")
            val results = riskScorer.score(messageText, context)
            val topResult = riskScorer.highestRisk(results)
            android.util.Log.d("HitaNotificationListener", "Top result: $topResult")
            topResult ?: return@launch

            // Step 1: Notify user first (always, unconditionally)
            if (topResult.riskLevel >= RiskLevel.MEDIUM) {
                localNotificationManager.showWarning(topResult)
            }

            // Step 2: Persist event locally (encrypted, no raw content)
            val eventId = riskEventStore.save(
                appPackage = sbn.packageName,
                contactIdentifier = contactIdentifier,
                result = topResult
            )

            // Step 3: Guardian alert — only HIGH risk, only after user has been notified
            if (topResult.riskLevel == RiskLevel.HIGH) {
                val contactHash = riskEventStore.sha256(contactIdentifier)
                val isFirstAlert = !riskEventStore.hasAlertSentForContact(contactHash)
                guardianAlertSender.sendIfConfigured(
                    result       = topResult,
                    eventId      = eventId,
                    appName      = packageToAppName(sbn.packageName),
                    contactHash  = contactHash,
                    isFirstAlert = isFirstAlert
                )
                riskEventStore.markAlertSent(eventId)
            }

        }
    }
}
