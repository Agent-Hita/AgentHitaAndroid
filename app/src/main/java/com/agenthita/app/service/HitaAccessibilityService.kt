package com.agenthita.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agenthita.app.HitaApplication
import com.agenthita.app.alert.GuardianAlertSender
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.alert.LocalNotificationManager
import com.agenthita.app.consent.AntiCoercionMonitor
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.detection.RiskLevel
import com.agenthita.app.detection.RiskScorer
import com.agenthita.app.model.OnDeviceClassifier
import com.agenthita.app.storage.RiskEventStore
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Reads message content from the screen when the user opens a 1-1 conversation
 * in WhatsApp, Instagram DM, or SMS. Uses AccessibilityService to traverse the
 * active window's view hierarchy — no notification required, works even when
 * the app is in the foreground.
 *
 * Privacy: message text is analysed in-memory only. Nothing is written to disk
 * except the scored RiskEvent (category, risk level, score, signal types — no
 * raw message text).
 *
 * Group chats are deliberately ignored: a conversation is only analysed if it
 * cannot be identified as a group (checked via subtitle / member count signals
 * in the view hierarchy).
 */
class HitaAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var classifier: com.agenthita.app.model.OnDeviceClassifier
    private lateinit var riskScorer: RiskScorer
    private lateinit var localNotificationManager: LocalNotificationManager
    private lateinit var guardianAlertSender: GuardianAlertSender
    private lateinit var riskEventStore: RiskEventStore
    private lateinit var consentManager: ConsentManager
    private lateinit var antiCoercionMonitor: AntiCoercionMonitor

    // Persistent dedup: SHA-256(pkg + contact + ALL visible messages) → timestamp seen
    // Survives service restarts. Entries expire after DEDUP_TTL_MS so new messages
    // in the same conversation are eventually re-evaluated.
    private lateinit var dedupPrefs: android.content.SharedPreferences

    companion object {
        const val ACTION_MODEL_AVAILABLE = "com.agenthita.app.MODEL_AVAILABLE"
        private const val TAG                = "HitaAccessibilityService"
        private const val TAG_IG_DIAG        = "HitaIG"
        private const val MIN_MESSAGE_LENGTH = 8
        private const val DEDUP_PREFS        = "hita_dedup"
        private const val AI_PREFS           = "hita_ai_prefs"
        private const val KEY_GEMMA_LOADED   = "gemma_loaded"
        private const val DEDUP_MAX_ENTRIES  = 500
        private const val CONV_KEY_PREFIX    = "last_"
        private const val SEEN_KEY_PREFIX    = "seen_"
        private const val MAX_SEEN_PER_CONV  = 200

        // Text patterns that appear in the chat UI when the other party turns on
        // disappearing messages. WhatsApp shows a system message; Instagram shows a
        // full-screen vanish-mode indicator. Matched case-insensitively against every
        // visible text node in the window.
        private val DISAPPEARING_PATTERNS = listOf(
            Regex("disappearing messages"),
            Regex("messages set to disappear"),
            Regex("turned on disappearing"),
            Regex("vanish mode"),
            Regex("you.re in vanish mode"),
            Regex("swipe up to exit vanish")
        )
    }

    // Tracks the highest-risk event seen while the user is inside a single conversation.
    // Only the first MEDIUM+ detection per session is saved to DB; subsequent detections
    // update the in-memory best result so the guardian alert always reflects the worst seen.
    // convHash → eventId of the one DB record written this session (main-thread only).
    // Cleared on conversation exit so the next visit starts fresh.
    private val sessionRecords     = mutableMapOf<String, Long>()
    // convHashes that have already had a guardian alert sent this session — prevents
    // duplicate alerts when the score improves across multiple messages.
    private val alertedConvHashes             = mutableSetOf<String>()
    // convHashes for which a disappearing-messages alert has already been sent this session.
    private val disappearingAlertedConvHashes = mutableSetOf<String>()
    private var activeConvHash: String? = null

    private val targetPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.android.mms"
    )

    private val modelAvailableReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MODEL_AVAILABLE) return
            android.util.Log.i(TAG, "Model available broadcast received — hot-loading Gemma")
            loadGemmaAsync()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as HitaApplication
        consentManager           = ConsentManager(this)
        classifier               = OnDeviceClassifier()
        riskScorer               = RiskScorer(classifier, consentManager.userCategory)
        localNotificationManager = LocalNotificationManager(this)
        guardianAlertSender      = GuardianAlertSender(this, consentManager)
        riskEventStore           = RiskEventStore(app.database.riskEventDao())
        antiCoercionMonitor      = AntiCoercionMonitor(this, consentManager)

        dedupPrefs = getSharedPreferences(DEDUP_PREFS, MODE_PRIVATE)
        android.util.Log.i(TAG, "Accessibility service connected — monitoring active")
        localNotificationManager.showStatusIndicator()
        TelemetryManager.get(this).track("monitoring_enabled")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(modelAvailableReceiver, IntentFilter(ACTION_MODEL_AVAILABLE), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(modelAvailableReceiver, IntentFilter(ACTION_MODEL_AVAILABLE))
        }

        loadGemmaAsync()

        // Tune service info at runtime to ensure window content retrieval is enabled
        serviceInfo = serviceInfo?.also {
            it.flags = it.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun loadGemmaAsync() {
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                val gemma = OnDeviceClassifier(this@HitaAccessibilityService)
                val telemetry = TelemetryManager.get(this@HitaAccessibilityService)
                when {
                    gemma.isLoaded -> {
                        classifier.upgrade(gemma)
                        telemetry.track("gemma_load_success")
                        getSharedPreferences(AI_PREFS, MODE_PRIVATE)
                            .edit().putBoolean(KEY_GEMMA_LOADED, true).apply()
                        android.util.Log.i(TAG, "Gemma ready — ML-assisted mode active")
                    }
                    gemma.loadFailed -> {
                        telemetry.track("gemma_load_failed")
                        android.util.Log.w(TAG, "Gemma load failed — rules-only mode")
                    }
                    else -> {
                        android.util.Log.i(TAG, "Gemma not installed — rules-only mode")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        android.util.Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(modelAvailableReceiver) }
        flushAllPendingAlerts()
        localNotificationManager.dismissStatusIndicator()
        TelemetryManager.get(this).flush()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // Ignore events from non-target packages entirely — notifications, system UI,
        // overlays, etc. all fire here and must NOT trigger a flush, as that would
        // cause repeated alert sends while the user is still in the conversation.
        // Flushing is handled by processWindow (back-to-chat-list / conv switch)
        // and onDestroy.
        if (pkg !in targetPackages) return
        if (!consentManager.isOnboardingComplete) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> processWindow(pkg)
        }
    }

    // -------------------------------------------------------------------------
    // Window processing
    // -------------------------------------------------------------------------

    private fun processWindow(packageName: String) {
        val root = rootInActiveWindow ?: return
        try {
            // Must be in a conversation screen — bail out on list/home screens
            if (!isConversationScreen(root, packageName)) {
                if (packageName == "com.instagram.android") {
                    android.util.Log.w(TAG_IG_DIAG, "isConversationScreen=false — dumping hierarchy")
                    dumpHierarchy(root, "", 0)
                }
                // User navigated back to chat list — flush pending alert for this app
                activeConvHash?.let { flushPendingAlert(it) }
                activeConvHash = null
                return
            }

            // Skip group chats
            if (isGroupConversation(root, packageName)) {
                android.util.Log.d(TAG, "[$packageName] Group conversation — skipping")
                return
            }

            val contactName = extractContactName(root, packageName)
                ?: run {
                    if (packageName == "com.instagram.android") {
                        android.util.Log.w(TAG_IG_DIAG, "contactName=null — dumping hierarchy")
                        dumpHierarchy(root, "", 0)
                    }
                    android.util.Log.d(TAG, "[$packageName] Could not determine contact name — skipping")
                    TelemetryManager.get(this).track("parsing_failed_${pkgShortName(packageName)}")
                    return
                }

            val convHash = sha256("$packageName|$contactName")

            // If the user switched to a different conversation within the same app, flush the old one
            if (activeConvHash != null && activeConvHash != convHash) {
                flushPendingAlert(activeConvHash!!)
            }
            activeConvHash = convHash

            // ── Disappearing messages detection ────────────────────────────────
            // Runs before the messages-empty check: the vanish/disappearing banner
            // is a UI-level signal independent of whether any new text is visible.
            if (convHash !in disappearingAlertedConvHashes && detectDisappearingMessages(root)) {
                disappearingAlertedConvHashes.add(convHash)
                android.util.Log.i(TAG, "[$packageName] Disappearing messages detected for contact=$contactName")
                localNotificationManager.showDisappearingMessagesWarning()
                val syntheticResult = com.agenthita.app.detection.DetectionResult(
                    category    = com.agenthita.app.detection.HarmCategory.DISAPPEARING_MESSAGES,
                    riskLevel   = RiskLevel.HIGH,
                    score       = 1.0f,
                    signals     = emptyList(),
                    explanation = "Contact enabled disappearing messages"
                )
                serviceScope.launch {
                    val eventId = riskEventStore.save(
                        appPackage        = packageName,
                        contactIdentifier = contactName,
                        result            = syntheticResult
                    )
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val sending = guardianAlertSender.sendIfConfigured(
                            result      = syntheticResult,
                            eventId     = eventId,
                            packageName = packageName,
                            contactHash = sha256(contactName)
                        )
                        if (sending) riskEventStore.markAlertSent(eventId)
                    }
                }
            }

            val messages = extractIncomingMessages(root, packageName)
            if (messages.isEmpty()) {
                if (packageName == "com.instagram.android") {
                    android.util.Log.w(TAG_IG_DIAG, "messages=empty for contact=$contactName — dumping hierarchy")
                    dumpHierarchy(root, "", 0)
                }
                return
            }

            // Per-conversation dedup — two-layer guard:
            //
            // Layer 1 (fast path): if the last visible message text is identical to the
            // one we last processed for this contact, the window hasn't changed at all.
            //
            // Layer 2 (scroll-back guard): even if the last visible message differs from
            // the stored one (e.g. user scrolled up), check whether its hash already
            // exists in the per-conversation seen set. If so, this is an old message
            // re-appearing on screen — skip it to avoid duplicate alerts.
            val lastMessage  = messages.last()
            val convStateKey = "$CONV_KEY_PREFIX$convHash"
            val seenKey      = "$SEEN_KEY_PREFIX$convHash"

            // Layer 1
            val prevLastMessage = dedupPrefs.getString(convStateKey, null)
            if (lastMessage == prevLastMessage) return

            // Layer 2
            val lastMsgHash = sha256(lastMessage)
            val seen = dedupPrefs.getStringSet(seenKey, emptySet())!!
            if (lastMsgHash in seen) {
                android.util.Log.d(TAG, "[$packageName] Scroll-back detected — skipping already-seen message")
                return
            }

            // New message confirmed — persist both state trackers before analysis
            // so that any re-entrant event during the coroutine is also suppressed.
            persistConvState(convStateKey, lastMessage)
            addToSeenMessages(seenKey, lastMsgHash)

            android.util.Log.d(TAG, "[$packageName] Contact=$contactName newMsg=\"${lastMessage.take(60)}\"")

            // Safe-exit check on last message
            val safeExit = antiCoercionMonitor.checkForSafeExitIntent(lastMessage)
            if (safeExit.shouldSuppressAnalysis) return

            val context = if (messages.size > 1) messages.dropLast(1) else emptyList()

            serviceScope.launch {
                val telemetry = TelemetryManager.get(this@HitaAccessibilityService)
                telemetry.track("analysis_started")
                val startMs = System.currentTimeMillis()

                val results = try {
                    riskScorer.score(lastMessage, context)
                } catch (e: Exception) {
                    telemetry.track("analysis_failed")
                    android.util.Log.e(TAG, "[$packageName] Scoring failed: ${e.message}")
                    return@launch
                }

                val durationMs = System.currentTimeMillis() - startMs
                val topResult  = riskScorer.highestRisk(results)

                telemetry.track("analysis_completed")
                telemetry.track("analysis_duration_ms", durationMs.toDouble())

                if (topResult == null) {
                    android.util.Log.d(TAG, "[$packageName] No risk detected — lastMsg=\"${lastMessage.take(60)}\"")
                    return@launch
                }

                android.util.Log.d(TAG, "[$packageName] Score=${topResult.score} level=${topResult.riskLevel}")

                // Notification fires immediately for every new risky message.
                // setOnlyAlertOnce(true) in LocalNotificationManager ensures only one
                // heads-up banner per conversation — subsequent detections silently
                // update the existing notification without making noise.
                if (topResult.riskLevel >= RiskLevel.MEDIUM) {
                    localNotificationManager.showWarning(topResult)
                    telemetry.track("alert_generated")
                }

                if (topResult.riskLevel < RiskLevel.MEDIUM) return@launch

                // ── One DB record per session ──────────────────────────────────────
                // sessionRecords (main-thread map) tracks whether a RiskEvent has
                // already been saved for this conversation visit. Only the first
                // MEDIUM+ detection writes to DB; later detections in the same
                // session just update the in-memory best result for the guardian alert.
                val existingEventId = withContext(kotlinx.coroutines.Dispatchers.Main) {
                    sessionRecords[convHash]
                }

                val eventId: Long
                if (existingEventId == null) {
                    eventId = riskEventStore.save(
                        appPackage        = packageName,
                        contactIdentifier = contactName,
                        result            = topResult
                    )
                    // Always generate analysis when Gemma is available — even if patterns
                    // scored zero and Gemma was the sole detector for this message.
                    val signalNames = topResult.signals.map { it.signal }.distinct()
                    val analysis = classifier.generateAnalysis(lastMessage, context, signalNames, topResult.category)
                    riskEventStore.updateGemmaAnalysis(eventId, analysis)
                    android.util.Log.d(TAG, "[$packageName] Session event $eventId saved (gemmaLoaded=${classifier.isLoaded})")

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (activeConvHash == convHash) sessionRecords[convHash] = eventId
                    }
                } else {
                    eventId = existingEventId
                    // If Gemma became available after the first save, re-generate the analysis
                    // so the event detail view shows a richer explanation.
                    if (classifier.isLoaded) {
                        val signalNames = topResult.signals.map { it.signal }.distinct()
                        val analysis = classifier.generateAnalysis(lastMessage, context, signalNames, topResult.category)
                        riskEventStore.updateGemmaAnalysis(eventId, analysis)
                        android.util.Log.d(TAG, "[$packageName] Gemma analysis refreshed for event $eventId")
                    } else {
                        android.util.Log.d(TAG, "[$packageName] Session already has event $eventId — skipping DB save")
                    }
                }

                // ── Guardian alert (HIGH only, immediate) ─────────────────────────
                // Sent immediately on first HIGH result per session. alertedConvHashes
                // deduplicates within a session; it is cleared on conversation exit so
                // a future return to the same contact starts fresh. We do NOT gate on
                // activeConvHash — if the user pressed Home just before the coroutine
                // completed, the risk was still real and the alert should still fire.
                if (topResult.riskLevel == RiskLevel.HIGH) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (convHash !in alertedConvHashes) {
                            alertedConvHashes.add(convHash)
                            android.util.Log.i(TAG, "[$packageName] HIGH risk — sending guardian alert: score=${topResult.score}")
                            val sending = guardianAlertSender.sendIfConfigured(
                                result      = topResult,
                                eventId     = eventId,
                                packageName = packageName,
                                contactHash = sha256(contactName)
                            )
                            if (sending) riskEventStore.markAlertSent(eventId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Catch all unchecked exceptions from any per-app parsing helper so a broken
            // selector in one app never crashes the service and silences all other apps.
            val shortPkg = pkgShortName(packageName)
            android.util.Log.e(TAG, "[$packageName] Parsing exception — $shortPkg will be skipped this event: ${e.message}", e)
            TelemetryManager.get(this).track("parsing_failed_$shortPkg")
        } finally {
            root.recycle()
        }
    }

    /** Returns a short metric-safe label for a package name. */
    private fun pkgShortName(pkg: String) = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b"          -> "whatsapp"
        "com.instagram.android"                      -> "instagram"
        "com.google.android.apps.messaging"          -> "google_messages"
        "com.samsung.android.messaging"              -> "samsung_messages"
        "com.android.messaging"                      -> "aosp_messages"
        "com.android.mms"                            -> "aosp_mms"
        else                                         -> pkg.replace('.', '_')
    }

    // -------------------------------------------------------------------------
    // Conversation screen detection
    // -------------------------------------------------------------------------

    /**
     * Returns true only if the current window looks like an open conversation
     * (as opposed to a contact list, settings page, story viewer, etc.)
     */
    private fun isConversationScreen(root: AccessibilityNodeInfo, pkg: String): Boolean {
        return when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                val t = RemoteConfig.uiTags
                root.findAccessibilityNodeInfosByViewId("$pkg:id/${t.waEntryId}").isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId("$pkg:id/${t.waSendId}").isNotEmpty()
            }
            "com.instagram.android" -> {
                val t = RemoteConfig.uiTags
                root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/${t.igComposerEditTextId}").isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/${t.igSendButtonId}").isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/${t.igDirectSendButtonId}").isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/${t.igRecyclerViewId}").isNotEmpty()
            }
            else -> {
                // SMS apps: check known compose field IDs first
                val composeIds = listOf(
                    "com.google.android.apps.messaging:id/compose_message_text",
                    "com.google.android.apps.messaging:id/message_entry_layout",
                    "com.android.messaging:id/compose_message_text",
                    "com.samsung.android.messaging:id/text_editor",
                    "com.android.mms:id/embedded_text_editor",
                    "android:id/input"
                )
                val hasComposeField = composeIds.any { id ->
                    root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()
                }
                if (hasComposeField) return true
                // Fallback: if the window has a scrollable message list it's likely a conversation
                val hasMessageList = root.findAccessibilityNodeInfosByViewId(
                    "com.google.android.apps.messaging:id/message_list"
                ).isNotEmpty() || root.findAccessibilityNodeInfosByViewId(
                    "com.android.messaging:id/message_list"
                ).isNotEmpty()
                if (hasMessageList) return true
                // Google Messages (Compose builds): uses bare test tags — check for ConversationScreenUi
                val composeTags = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTag(root, RemoteConfig.uiTags.gmConversationScreenTag, composeTags)
                val found = composeTags.isNotEmpty()
                composeTags.forEach { it.recycle() }
                found
            }
        }
    }

    // -------------------------------------------------------------------------
    // Group chat detection
    // -------------------------------------------------------------------------

    private fun isGroupConversation(root: AccessibilityNodeInfo, pkg: String): Boolean {
        return when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                val subtitleNodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/${RemoteConfig.uiTags.waContactStatusId}")
                val subtitle = subtitleNodes.firstOrNull()?.text?.toString() ?: ""
                subtitleNodes.forEach { it.recycle() }
                subtitle.contains("member", ignoreCase = true) ||
                subtitle.split(",").size >= 3
            }
            "com.instagram.android" -> {
                val nodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/${RemoteConfig.uiTags.igHeaderSubtitleId}")
                val subtitle = nodes.firstOrNull()?.text?.toString() ?: ""
                nodes.forEach { it.recycle() }
                val isGroup = subtitle.contains("other", ignoreCase = true) ||
                    subtitle.contains("people", ignoreCase = true) ||
                    subtitle.contains("member", ignoreCase = true) ||
                    subtitle.split(",").size >= 3
                if (isGroup) {
                    android.util.Log.w(TAG_IG_DIAG, "isGroupConversation=true subtitle=\"$subtitle\" — dumping hierarchy")
                    dumpHierarchy(root, "", 0)
                }
                isGroup
            }
            else -> false // SMS doesn't surface group easily from the header; handled below
        }
    }

    // -------------------------------------------------------------------------
    // Contact name extraction
    // -------------------------------------------------------------------------

    private fun extractContactName(root: AccessibilityNodeInfo, pkg: String): String? {
        return when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/${RemoteConfig.uiTags.waContactNameId}")
                val name = nodes.firstOrNull()?.text?.toString()
                nodes.forEach { it.recycle() }
                name
            }
            "com.instagram.android" -> {
                val nodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/${RemoteConfig.uiTags.igHeaderTitleId}")
                val name = nodes.firstOrNull()?.text?.toString()
                nodes.forEach { it.recycle() }
                name
            }
            else -> {
                // SMS apps — look for action bar title
                val ids = listOf(
                    "com.google.android.apps.messaging:id/conversation_title",
                    "com.android.messaging:id/conversation_title",
                    "com.samsung.android.messaging:id/toolbar_title",
                    "android:id/action_bar_title"
                )
                for (id in ids) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    val name = nodes.firstOrNull()?.text?.toString()
                    nodes.forEach { it.recycle() }
                    if (!name.isNullOrBlank()) return name
                }
                // Google Messages (Compose builds): contact name sits inside a node tagged
                // "top_app_bar_title_row" with no package prefix.
                val t = RemoteConfig.uiTags
                val titleRowNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTag(root, t.gmTitleRowTag, titleRowNodes)
                for (row in titleRowNodes) {
                    val name = extractTextFromChildren(row)
                    row.recycle()
                    if (!name.isNullOrBlank()) return name
                }
                // Last resort: parse sender from content-desc of message_text nodes.
                // Format: "<contact> said <message>" — present in Google Messages Compose UI.
                val msgNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTag(root, t.gmMessageTag, msgNodes)
                val name = msgNodes.firstNotNullOfOrNull { node ->
                    val desc = node.contentDescription?.toString() ?: ""
                    val match = Regex("^(.+?) said .+").find(desc)
                    match?.groupValues?.get(1)?.takeIf { it != "You" && it.isNotBlank() }
                }
                msgNodes.forEach { it.recycle() }
                name
            }
        }
    }

    // -------------------------------------------------------------------------
    // Message text extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts the last up to 5 visible incoming message texts from the screen.
     * Own (outgoing) messages are excluded where they can be identified.
     */
    private fun extractIncomingMessages(root: AccessibilityNodeInfo, pkg: String): List<String> {
        val messages = mutableListOf<String>()

        when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/${RemoteConfig.uiTags.waMessageTextId}")
                nodes.forEach { node ->
                    val text = node.text?.toString()
                    if (!text.isNullOrBlank() && text.length >= MIN_MESSAGE_LENGTH) {
                        // Exclude own messages: in WhatsApp own messages sit inside
                        // a node with content-desc containing "You:" or similar.
                        if (!isOutgoingWhatsApp(node)) messages.add(text)
                    }
                    node.recycle()
                }
            }
            "com.instagram.android" -> {
                val igTextIds = RemoteConfig.uiTags.igMessageTextIds.map { "com.instagram.android:id/$it" }
                for (viewId in igTextIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                    nodes.forEach { node ->
                        val text = node.text?.toString()
                        if (!text.isNullOrBlank() && text.length >= MIN_MESSAGE_LENGTH) {
                            if (!isOutgoingInstagram(node)) messages.add(text)
                        }
                        node.recycle()
                    }
                    if (messages.isNotEmpty()) break
                }
            }
            else -> {
                // Google Messages (Compose builds): messages use bare test tags.
                // content-desc distinguishes sender: "<contact> said <msg>" vs "You said <msg>".
                val gmNodes = mutableListOf<AccessibilityNodeInfo>()
                findNodesByTag(root, RemoteConfig.uiTags.gmMessageTag, gmNodes)
                if (gmNodes.isNotEmpty()) {
                    gmNodes.forEach { node ->
                        val desc = node.contentDescription?.toString() ?: ""
                        val text = node.text?.toString()
                        // Exclude outgoing ("You said …"), timestamps, and UI labels
                        if (!text.isNullOrBlank() && text.length >= MIN_MESSAGE_LENGTH &&
                            !desc.startsWith("You said", ignoreCase = true) &&
                            !isUIChrome(text)
                        ) {
                            messages.add(text)
                        }
                        node.recycle()
                    }
                } else {
                    // Fallback for other SMS apps (Samsung, AOSP) — collect all substantial TextViews
                    collectTextViewContent(root, messages)
                }
            }
        }

        return messages.takeLast(5)
    }

    /**
     * WhatsApp outgoing messages have a parent with a "status" node (tick marks).
     * Walk up two levels and check for the status view id.
     */
    private fun isOutgoingWhatsApp(messageNode: AccessibilityNodeInfo): Boolean {
        val parent  = messageNode.parent ?: return false
        val gParent = parent.parent ?: return false
        val statusNodes = gParent.findAccessibilityNodeInfosByViewId("com.whatsapp:id/${RemoteConfig.uiTags.waStatusId}")
        val isOutgoing = statusNodes.isNotEmpty()
        statusNodes.forEach { it.recycle() }
        parent.recycle()
        gParent.recycle()
        return isOutgoing
    }

    /**
     * Instagram outgoing messages are right-aligned; incoming are left-aligned.
     * UI dump confirms: outgoing left-bound ≈ 945px, incoming ≈ 158px on a 1080px screen.
     * Using left-bound (not center) because long incoming messages can extend past the
     * screen midpoint and would be misclassified if we used centerX.
     */
    private fun isOutgoingInstagram(messageNode: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        messageNode.getBoundsInScreen(rect)
        val screenWidth = resources.displayMetrics.widthPixels
        return rect.left > screenWidth / 2
    }

    /**
     * Generic text collection for SMS apps — traverse tree and collect all
     * TextView-like content that looks like a message (long enough, not a label).
     */
    private fun collectTextViewContent(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank() &&
            text.length >= MIN_MESSAGE_LENGTH &&
            node.className?.contains("TextView") == true &&
            !isUIChrome(text)
        ) {
            out.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextViewContent(child, out)
            child.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // Disappearing messages detection
    // -------------------------------------------------------------------------

    /**
     * Returns true if any visible text node in [root] matches one of the known
     * phrases that apps display when disappearing/vanish mode is enabled.
     *
     * Matching is purely textual so it survives app version updates that change
     * view IDs. Depth-limited to 12 levels to stay fast on the main thread.
     */
    private fun detectDisappearingMessages(root: AccessibilityNodeInfo): Boolean =
        scanNodeForPatterns(root, DISAPPEARING_PATTERNS, 0)

    private fun scanNodeForPatterns(
        node: AccessibilityNodeInfo,
        patterns: List<Regex>,
        depth: Int
    ): Boolean {
        if (depth > 12) return false
        val text = (node.text?.toString() ?: node.contentDescription?.toString())
            ?.lowercase() ?: ""
        if (text.isNotBlank() && patterns.any { it.containsMatchIn(text) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanNodeForPatterns(child, patterns, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    /** Heuristic: short strings that look like UI labels rather than message content. */
    private fun isUIChrome(text: String): Boolean {
        if (text.length < MIN_MESSAGE_LENGTH) return true
        val lower = text.lowercase()
        if (lower == "send" || lower == "type a message" || lower == "message") return true
        if (lower.startsWith("today") || lower.startsWith("yesterday")) return true
        if (lower.matches(Regex("\\d{1,2}:\\d{2}\\s*(am|pm)?", RegexOption.IGNORE_CASE))) return true
        if (RemoteConfig.uiTags.gmUiChromePrefixes.any { lower.startsWith(it) }) return true
        return false
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Persists the last-analyzed message text for a conversation key.
     * Evicts excess entries (oldest by insertion order is not guaranteed in SharedPreferences,
     * so we just trim any entries beyond the cap to keep the prefs file lean).
     */
    private fun persistConvState(key: String, lastMessage: String) {
        val prefs = dedupPrefs
        val editor = prefs.edit()
        val all = prefs.all
        if (all.size >= DEDUP_MAX_ENTRIES) {
            all.keys
                .take(all.size - DEDUP_MAX_ENTRIES + 1)
                .forEach { editor.remove(it) }
        }
        editor.putString(key, lastMessage).apply()
    }

    /**
     * Ends the conversation session: clears the one-save-per-session record so a future
     * visit to the same conversation starts fresh, dismisses the warning notification so
     * the next conversation's alert shows as a fresh heads-up, then sends one guardian
     * alert if a HIGH-risk result was accumulated during the session.
     */
    /** Resets per-session state when the user leaves a conversation. */
    private fun flushPendingAlert(convHash: String) {
        sessionRecords.remove(convHash)
        alertedConvHashes.remove(convHash)
        disappearingAlertedConvHashes.remove(convHash)
        // Clear the seen-message hash set so that when the user returns to this
        // conversation, new messages are not blocked by the scroll-back guard.
        // Layer 1 (prevLastMessage) still prevents re-processing on the same window event.
        dedupPrefs.edit().remove("$SEEN_KEY_PREFIX$convHash").apply()
        localNotificationManager.dismissWarning()
    }

    private fun flushAllPendingAlerts() {
        sessionRecords.keys.toList().forEach { flushPendingAlert(it) }
    }

    /**
     * Adds [msgHash] to the per-conversation seen-message set.
     * Trims ~10% of entries when the cap is reached to amortise future trims.
     * Copies the set before mutating to avoid the SharedPreferences StringSet bug.
     */
    private fun addToSeenMessages(seenKey: String, msgHash: String) {
        val current = dedupPrefs.getStringSet(seenKey, emptySet())!!.toMutableSet()
        if (current.size >= MAX_SEEN_PER_CONV) {
            val trimCount = MAX_SEEN_PER_CONV / 10
            val iter = current.iterator()
            repeat(trimCount) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        current.add(msgHash)
        dedupPrefs.edit().putStringSet(seenKey, current).apply()
    }

    private fun extractTextFromChildren(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) return text
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = extractTextFromChildren(child)
            child.recycle()
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    /**
     * Walks the accessibility tree and collects all nodes whose viewIdResourceName
     * equals [tag] exactly. Used for Jetpack Compose UIs (e.g. Google Messages) that
     * expose bare test-tag strings instead of "package:id/name" resource IDs.
     */
    private fun findNodesByTag(
        node: AccessibilityNodeInfo,
        tag: String,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth > 20) return
        if (node.viewIdResourceName == tag) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByTag(child, tag, out, depth + 1)
            if (node.viewIdResourceName != tag) child.recycle()
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // Diagnostic hierarchy dump (Instagram debug only)
    // -------------------------------------------------------------------------

    /**
     * Logs the full view hierarchy to logcat under TAG_IG_DIAG so you can see
     * the real view IDs exposed by the installed Instagram version.
     *
     * Filter in logcat: tag:HitaIG
     *
     * Each line format:
     *   <indent>[viewId] className  text="…"  desc="…"
     *
     * Only fires when Instagram detection fails — remove or guard with a build
     * flag before shipping to production (raw node text leaks to logcat).
     */
    private fun dumpHierarchy(node: AccessibilityNodeInfo, indent: String, depth: Int) {
        if (depth > 8) return
        val viewId   = node.viewIdResourceName ?: "(no-id)"
        val cls      = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text     = node.text?.toString()?.take(60)?.let { "text=\"$it\"" } ?: ""
        val desc     = node.contentDescription?.toString()?.take(60)?.let { "desc=\"$it\"" } ?: ""
        val hint     = node.hintText?.toString()?.take(40)?.let { "hint=\"$it\"" } ?: ""
        android.util.Log.d(TAG_IG_DIAG, "$indent[$viewId] $cls  $text  $desc  $hint".trimEnd())
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpHierarchy(child, "$indent  ", depth + 1)
            child.recycle()
        }
    }

}

