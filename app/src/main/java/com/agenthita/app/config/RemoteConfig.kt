package com.agenthita.app.config

import android.content.Context
import android.util.Log
import com.agenthita.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Centralised app configuration with CloudFront remote-override support.
 *
 * Priority (highest wins):
 *   1. Freshly fetched remote JSON (applied immediately + cached to prefs)
 *   2. Previously cached remote JSON (loaded synchronously on init)
 *   3. Bundled assets/default_config.json
 *
 * Usage:
 *   // In Application.onCreate():
 *   RemoteConfig.init(this)           // load defaults + cached override (fast, synchronous)
 *   RemoteConfig.fetchAsync(this)     // refresh from CloudFront in background
 *
 *   // Anywhere:
 *   RemoteConfig.telemetryEndpoint
 *   RemoteConfig.riskThresholds.child.high
 */
object RemoteConfig {

    private const val TAG          = "RemoteConfig"
    private const val PREFS_NAME   = "hita_remote_config"
    private const val KEY_JSON     = "cached_json"

    // Build-type-specific URL: debug → .../dev/app_config.json, release → .../app_config.json
    // Set via BuildConfig so dev and prod remote overrides are isolated from each other.
    private val CONFIG_URL get() = BuildConfig.REMOTE_CONFIG_URL

    @Volatile
    private var current = ConfigSnapshot()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Call from Application.onCreate(). Reads the bundled default_config.json,
     * then overlays any previously cached remote JSON. Synchronous but fast
     * (no network I/O).
     */
    fun init(context: Context) {
        // 1. Load bundled defaults
        val base = runCatching {
            context.assets.open("default_config.json")
                .bufferedReader().readText()
                .let { parse(it) }
        }.getOrElse {
            Log.w(TAG, "Could not read bundled default_config.json: ${it.message}")
            ConfigSnapshot()
        }

        // 2. Overlay cached remote config (if any)
        val cached = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)

        current = if (cached != null) {
            runCatching { parse(cached) }.getOrElse {
                Log.w(TAG, "Cached config is corrupt, ignoring: ${it.message}")
                base
            }
        } else {
            base
        }

        Log.i(TAG, "Config initialised (v${current.version}, cached=${cached != null})")
    }

    /**
     * Fetches fresh config from CloudFront on a daemon thread.
     * Applies immediately and persists for the next cold start.
     * Silently swallowed on error — callers always get a valid snapshot.
     */
    fun fetchAsync(context: Context) {
        Thread {
            try {
                val conn = URL(CONFIG_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = configConnectTimeoutMs
                conn.readTimeout    = configReadTimeoutMs
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode in 200..299) {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val fresh = parse(json)
                    current = fresh
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_JSON, json).apply()
                    Log.i(TAG, "Remote config refreshed (v${fresh.version})")
                } else {
                    Log.d(TAG, "Config fetch returned HTTP ${conn.responseCode} — using cached")
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Config fetch failed (using cached/defaults): ${e.message}")
            }
        }.also { it.isDaemon = true; it.name = "RemoteConfig-fetch" }.start()
    }

    // ── Typed accessors ───────────────────────────────────────────────────────

    val telemetryEndpoint: String        get() = current.telemetryEndpoint
    val alertEndpoint: String            get() = current.alertEndpoint
    val guardianConfigEndpoint: String   get() = current.guardianConfigEndpoint
    val feedbackEndpoint: String         get() = current.feedbackEndpoint
    val falseFeedbackEndpoint: String    get() = current.falseFeedbackEndpoint
    val deviceRegisterEndpoint: String   get() = current.deviceRegisterEndpoint
    val connectTimeoutMs:            Int  get() = current.connectTimeoutMs
    val readTimeoutMs:               Int  get() = current.readTimeoutMs
    val configConnectTimeoutMs:      Int  get() = current.configConnectTimeoutMs
    val configReadTimeoutMs:         Int  get() = current.configReadTimeoutMs
    val guardianAlertThrottleMs:     Long get() = current.guardianAlertThrottleMs

    val telemetryFlushThreshold: Int  get() = current.telemetryFlushThreshold
    val telemetryFlushIntervalMs: Long get() = current.telemetryFlushIntervalMs
    val appVersion: String            get() = current.appVersion
    val buildType: String             get() = current.buildType

    val gemmaMaxTokens: Int             get() = current.gemmaMaxTokens
    val gemmaInputTruncationChars: Int  get() = current.gemmaInputTruncationChars
    val gemmaContextMessages: Int       get() = current.gemmaContextMessages
    val gemmaContextMessageLength: Int  get() = current.gemmaContextMessageLength
    val modelDownloadUrl: String        get() = current.modelDownloadUrl
    val modelSignedUrlEndpoint: String  get() = current.modelSignedUrlEndpoint
    val kaggleUrl: String              get() = current.kaggleUrl
    val gemmaModelHashes: List<String>  get() = current.gemmaModelHashes
    val gemmaMaxFileSizeBytes: Long     get() = current.gemmaMaxFileSizeBytes

    val riskThresholds: RiskThresholds  get() = current.riskThresholds
    val safetyResources: SafetyResources get() = current.safetyResources
    val helpResources: List<HelpResource> get() = current.helpResources

    val minMessageLength: Int      get() = current.minMessageLength
    val targetPackages: Set<String> get() = current.targetPackages
    val uiTags: UiTags             get() = current.uiTags

    // ── Data classes ──────────────────────────────────────────────────────────

    data class RiskBand(val high: Float, val medium: Float, val low: Float)

    data class RiskThresholds(
        val child:           RiskBand = RiskBand(0.80f, 0.62f, 0.32f),
        val adolescent:      RiskBand = RiskBand(0.82f, 0.65f, 0.36f),
        val adult:           RiskBand = RiskBand(0.85f, 0.70f, 0.40f),
        val vulnerableAdult: RiskBand = RiskBand(0.85f, 0.70f, 0.40f)
    )

    data class CategoryResources(val tip1Url: String, val tip2Url: String)

    data class SafetyResources(
        val sextortion:       CategoryResources = CategoryResources(
            "https://www.fbi.gov/how-we-can-help-you/scams-and-safety/common-frauds-and-scams/sextortion",
            "https://cybercivilrights.org/ccri-crisis-helpline/"),
        val financialScam:    CategoryResources = CategoryResources(
            "https://consumer.ftc.gov/articles/how-avoid-scam",
            "https://reportfraud.ftc.gov"),
        val grooming:         CategoryResources = CategoryResources(
            "https://www.missingkids.org/theissues/grooming",
            "https://www.missingkids.org/gethelpnow/cybertipline"),
        val romanceScam:      CategoryResources = CategoryResources(
            "https://www.fbi.gov/how-we-can-help-you/scams-and-safety/common-frauds-and-scams/romance-scams",
            "https://consumer.ftc.gov/articles/what-you-need-know-about-romance-scams"),
        val identityPhishing: CategoryResources = CategoryResources(
            "https://www.cisa.gov/topics/cyber-threats-and-advisories/phishing",
            "https://www.identitytheft.gov"),
        val luring:           CategoryResources = CategoryResources(
            "https://www.cisa.gov/topics/cyber-threats-and-advisories/social-engineering",
            "https://www.ic3.gov"),
        val harassment:       CategoryResources = CategoryResources(
            "https://www.stopbullying.gov/cyberbullying/how-to-report",
            "https://www.cisa.gov/topics/cyber-threats-and-advisories/cyberstalking-and-online-harassment")
    )

    data class HelpResource(val name: String, val url: String)

    data class ConfigSnapshot(
        val version: Int = 1,

        // API — base URLs come from BuildConfig so debug builds hit api-dev automatically
        val telemetryEndpoint: String = BuildConfig.TELEMETRY_API_URL,
        val alertEndpoint:          String = "${BuildConfig.ALERT_API_URL}/guardian",
        val guardianConfigEndpoint: String = "${BuildConfig.ALERT_API_URL}/guardian/configure",
        val feedbackEndpoint:        String = BuildConfig.FEEDBACK_API_URL,
        val falseFeedbackEndpoint:   String = "${BuildConfig.FEEDBACK_API_URL}/false-positive",
        val deviceRegisterEndpoint:  String = BuildConfig.DEVICE_REGISTER_URL,
        val connectTimeoutMs:           Int  = 10_000,
        val readTimeoutMs:              Int  = 15_000,
        val configConnectTimeoutMs:     Int  = 5_000,
        val configReadTimeoutMs:        Int  = 10_000,
        val guardianAlertThrottleMs:    Long = 24L * 60 * 60 * 1_000,

        // Telemetry
        val telemetryFlushThreshold: Int    = 50,
        val telemetryFlushIntervalMs: Long  = 60_000L,
        val appVersion:              String = "0.1.1",
        val buildType:               String = "release",

        // Gemma
        val gemmaMaxTokens:            Int    = 512,
        val gemmaInputTruncationChars: Int    = 300,
        val gemmaContextMessages:      Int    = 5,
        val gemmaContextMessageLength: Int    = 80,
        val modelDownloadUrl:          String = "https://www.agenthita.org/model/gemma-3-tflite-gemma3-1b-it-int4-v1.tar.gz",
        val modelSignedUrlEndpoint:    String = BuildConfig.MODEL_SIGNED_URL_ENDPOINT,
        val kaggleUrl:                 String = "https://www.kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4",
        // SHA-256 hex digests of known-good extracted model binaries.
        // Add new entries here (or via remote config) when shipping updated model versions.
        val gemmaModelHashes:          List<String> = listOf(
            // gemma-3-tflite-gemma3-1b-it-int4-v1.tar.gz → gemma3-1B-it-int4.task
            "e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee",
            // legacy gemma-tflite-gemma-2b-it-cpu-int4-v1.tar.gz → gemma-2b-it-cpu-int4.bin
            "176452e0eef32e7cd477e5609160278f3f5cbfeeb46d2cb2d37bd631af1b0bea"
        ),
        val gemmaMaxFileSizeBytes:     Long   = 5_000_000_000L,

        // Thresholds
        val riskThresholds: RiskThresholds = RiskThresholds(),

        // Safety resources
        val safetyResources: SafetyResources = SafetyResources(),
        val helpResources: List<HelpResource> = listOf(
            HelpResource("National Domestic Violence Hotline", "https://www.thehotline.org"),
            HelpResource("Crisis Text Line",                  "https://www.crisistextline.org"),
            HelpResource("Cyber Civil Rights Initiative",     "https://cybercivilrights.org"),
            HelpResource("FBI IC3 Sextortion Resources",      "https://www.ic3.gov"),
            HelpResource("NCMEC CyberTipline",                "https://www.missingkids.org/gethelpnow/cybertipline")
        ),

        // Monitoring
        val minMessageLength: Int       = 8,
        val targetPackages:   Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.messaging",
            "com.android.mms"
        ),

        // Per-app UI tag overrides — lets us fix broken selectors via remote config
        // without a Play Store release whenever an app updates its view hierarchy.
        val uiTags: UiTags = UiTags()
    )

    /**
     * Tag/ID names used to locate UI elements inside each supported messaging app.
     * All fields have safe defaults matching the currently verified app versions.
     * Push an updated app_config.json to CloudFront to patch broken selectors OTA.
     */
    data class UiTags(
        // Google Messages (Compose-based, uses bare test tags)
        val gmConversationScreenTag: String       = "ConversationScreenUi",
        val gmTitleRowTag: String                 = "top_app_bar_title_row",
        val gmMessageTag: String                  = "message_text",
        // UI chrome prefixes to filter from Google Messages message_text nodes
        val gmUiChromePrefixes: List<String>      = listOf("texting with"),
        // Edit-mode indicator — empty = check disabled until confirmed via OTA.
        val gmEditBarId: String                   = "",

        // WhatsApp view IDs (package-qualified)
        val waMessageTextId: String               = "message_text",
        val waEntryId: String                     = "entry",
        val waSendId: String                      = "send",
        val waStatusId: String                    = "status",
        val waContactNameId: String               = "conversation_contact_name",
        val waContactStatusId: String             = "conversation_contact_status",
        // Edit-mode indicator — visible when user is editing a sent message.
        // Empty string = check disabled (set via OTA once confirmed).
        val waEditBarId: String                   = "",

        // Instagram view IDs (package-qualified, verified v424)
        val igComposerEditTextId: String          = "row_thread_composer_edittext",
        val igSendButtonId: String                = "send_button",
        val igDirectSendButtonId: String          = "direct_thread_send_button",
        val igRecyclerViewId: String              = "direct_thread_recycler_view",
        val igHeaderTitleId: String               = "header_title",
        val igHeaderSubtitleId: String            = "header_subtitle",
        // Edit-mode indicator — confirmed from logcat 2026-07-09.
        val igEditBarId: String                   = "edit_bar_header",
        val igMessageTextIds: List<String>        = listOf(
            "direct_text_message_text_view",
            "message_content",
            "direct_message_text"
        )
    )

    // ── JSON parser ───────────────────────────────────────────────────────────

    private fun parse(json: String): ConfigSnapshot {
        val root = JSONObject(json)
        val defaults = ConfigSnapshot()

        val api = root.optJSONObject("api")
        val tel = root.optJSONObject("telemetry")
        val gem = root.optJSONObject("gemma")
        val thr = root.optJSONObject("risk_thresholds")
        val mon = root.optJSONObject("monitoring")
        val res = root.optJSONObject("safety_resources")
        val hlp = root.optJSONArray("help_resources")
        val tags = root.optJSONObject("ui_tags")

        return defaults.copy(
            version = root.optInt("version", defaults.version),

            // API
            telemetryEndpoint = api?.optString("telemetry_endpoint", defaults.telemetryEndpoint) ?: defaults.telemetryEndpoint,
            alertEndpoint          = api?.optString("alert_endpoint",           defaults.alertEndpoint)          ?: defaults.alertEndpoint,
            guardianConfigEndpoint = api?.optString("guardian_config_endpoint", defaults.guardianConfigEndpoint) ?: defaults.guardianConfigEndpoint,
            feedbackEndpoint       = api?.optString("feedback_endpoint",         defaults.feedbackEndpoint)       ?: defaults.feedbackEndpoint,
            falseFeedbackEndpoint  = api?.optString("false_positive_endpoint",   defaults.falseFeedbackEndpoint)  ?: defaults.falseFeedbackEndpoint,
            deviceRegisterEndpoint = api?.optString("device_register_endpoint",  defaults.deviceRegisterEndpoint) ?: defaults.deviceRegisterEndpoint,
            connectTimeoutMs        = api?.optInt("connect_timeout_ms",          defaults.connectTimeoutMs)        ?: defaults.connectTimeoutMs,
            readTimeoutMs           = api?.optInt("read_timeout_ms",            defaults.readTimeoutMs)           ?: defaults.readTimeoutMs,
            configConnectTimeoutMs  = api?.optInt("config_connect_timeout_ms",  defaults.configConnectTimeoutMs)  ?: defaults.configConnectTimeoutMs,
            configReadTimeoutMs     = api?.optInt("config_read_timeout_ms",     defaults.configReadTimeoutMs)     ?: defaults.configReadTimeoutMs,
            guardianAlertThrottleMs = api?.optLong("guardian_alert_throttle_ms", defaults.guardianAlertThrottleMs) ?: defaults.guardianAlertThrottleMs,

            // Telemetry
            telemetryFlushThreshold  = tel?.optInt("flush_threshold",    defaults.telemetryFlushThreshold)         ?: defaults.telemetryFlushThreshold,
            telemetryFlushIntervalMs = tel?.optLong("flush_interval_ms", defaults.telemetryFlushIntervalMs)        ?: defaults.telemetryFlushIntervalMs,
            appVersion               = tel?.optString("app_version",     defaults.appVersion)                      ?: defaults.appVersion,
            buildType                = tel?.optString("build_type",      defaults.buildType)                       ?: defaults.buildType,

            // Gemma
            gemmaMaxTokens            = gem?.optInt("max_tokens",             defaults.gemmaMaxTokens)            ?: defaults.gemmaMaxTokens,
            gemmaInputTruncationChars = gem?.optInt("input_truncation_chars", defaults.gemmaInputTruncationChars) ?: defaults.gemmaInputTruncationChars,
            gemmaContextMessages      = gem?.optInt("context_messages",       defaults.gemmaContextMessages)      ?: defaults.gemmaContextMessages,
            gemmaContextMessageLength = gem?.optInt("context_message_length", defaults.gemmaContextMessageLength) ?: defaults.gemmaContextMessageLength,
            modelDownloadUrl          = gem?.optString("model_download_url",    defaults.modelDownloadUrl)          ?: defaults.modelDownloadUrl,
            modelSignedUrlEndpoint    = gem?.optString("model_signed_url_endpoint", defaults.modelSignedUrlEndpoint) ?: defaults.modelSignedUrlEndpoint,
            kaggleUrl                 = gem?.optString("kaggle_url",           defaults.kaggleUrl)                 ?: defaults.kaggleUrl,
            gemmaModelHashes          = gem?.optJSONArray("model_hashes")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.length == 64 } }
            } ?: defaults.gemmaModelHashes,
            gemmaMaxFileSizeBytes     = gem?.optLong("max_file_size_bytes", defaults.gemmaMaxFileSizeBytes)     ?: defaults.gemmaMaxFileSizeBytes,

            // Risk thresholds
            riskThresholds = thr?.let { parseBands(it, defaults.riskThresholds) } ?: defaults.riskThresholds,

            // Safety resources
            safetyResources = res?.let { parseSafetyResources(it, defaults.safetyResources) } ?: defaults.safetyResources,

            // Help resources
            helpResources = hlp?.let { parseHelpResources(it) }?.takeIf { it.isNotEmpty() } ?: defaults.helpResources,

            // Monitoring
            minMessageLength = mon?.optInt("min_message_length", defaults.minMessageLength) ?: defaults.minMessageLength,
            targetPackages   = mon?.optJSONArray("target_packages")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                ?.takeIf { it.isNotEmpty() }
                ?: defaults.targetPackages,

            // UI tags
            uiTags = tags?.let { parseUiTags(it, defaults.uiTags) } ?: defaults.uiTags
        )
    }

    private fun parseUiTags(obj: JSONObject, d: UiTags): UiTags {
        val gm = obj.optJSONObject("google_messages")
        val wa = obj.optJSONObject("whatsapp")
        val ig = obj.optJSONObject("instagram")
        return d.copy(
            gmConversationScreenTag = gm?.optString("conversation_screen_tag", d.gmConversationScreenTag) ?: d.gmConversationScreenTag,
            gmTitleRowTag           = gm?.optString("title_row_tag",           d.gmTitleRowTag)           ?: d.gmTitleRowTag,
            gmMessageTag            = gm?.optString("message_tag",             d.gmMessageTag)            ?: d.gmMessageTag,
            gmUiChromePrefixes      = gm?.optJSONArray("ui_chrome_prefixes")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) } }
                ?.takeIf { it.isNotEmpty() } ?: d.gmUiChromePrefixes,
            gmEditBarId             = gm?.optString("edit_bar_id",             d.gmEditBarId)             ?: d.gmEditBarId,

            waMessageTextId   = wa?.optString("message_text_id",    d.waMessageTextId)   ?: d.waMessageTextId,
            waEntryId         = wa?.optString("entry_id",           d.waEntryId)         ?: d.waEntryId,
            waSendId          = wa?.optString("send_id",            d.waSendId)          ?: d.waSendId,
            waStatusId        = wa?.optString("status_id",          d.waStatusId)        ?: d.waStatusId,
            waContactNameId   = wa?.optString("contact_name_id",    d.waContactNameId)   ?: d.waContactNameId,
            waContactStatusId = wa?.optString("contact_status_id",  d.waContactStatusId) ?: d.waContactStatusId,
            waEditBarId       = wa?.optString("edit_bar_id",        d.waEditBarId)       ?: d.waEditBarId,

            igComposerEditTextId  = ig?.optString("composer_edit_text_id",   d.igComposerEditTextId)  ?: d.igComposerEditTextId,
            igSendButtonId        = ig?.optString("send_button_id",          d.igSendButtonId)        ?: d.igSendButtonId,
            igDirectSendButtonId  = ig?.optString("direct_send_button_id",   d.igDirectSendButtonId)  ?: d.igDirectSendButtonId,
            igRecyclerViewId      = ig?.optString("recycler_view_id",        d.igRecyclerViewId)      ?: d.igRecyclerViewId,
            igHeaderTitleId       = ig?.optString("header_title_id",         d.igHeaderTitleId)       ?: d.igHeaderTitleId,
            igHeaderSubtitleId    = ig?.optString("header_subtitle_id",      d.igHeaderSubtitleId)    ?: d.igHeaderSubtitleId,
            igEditBarId           = ig?.optString("edit_bar_id",             d.igEditBarId)           ?: d.igEditBarId,
            igMessageTextIds      = ig?.optJSONArray("message_text_ids")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) } }
                ?.takeIf { it.isNotEmpty() } ?: d.igMessageTextIds
        )
    }

    private fun parseBand(obj: JSONObject, default: RiskBand) = RiskBand(
        high   = obj.optDouble("high",   default.high.toDouble()).toFloat(),
        medium = obj.optDouble("medium", default.medium.toDouble()).toFloat(),
        low    = obj.optDouble("low",    default.low.toDouble()).toFloat()
    )

    private fun parseBands(thr: JSONObject, defaults: RiskThresholds) = RiskThresholds(
        child           = thr.optJSONObject("child")?.let           { parseBand(it, defaults.child) }           ?: defaults.child,
        adolescent      = thr.optJSONObject("adolescent")?.let      { parseBand(it, defaults.adolescent) }      ?: defaults.adolescent,
        adult           = thr.optJSONObject("adult")?.let           { parseBand(it, defaults.adult) }           ?: defaults.adult,
        vulnerableAdult = thr.optJSONObject("vulnerable_adult")?.let { parseBand(it, defaults.vulnerableAdult) } ?: defaults.vulnerableAdult
    )

    private fun parseCategoryResources(obj: JSONObject?, default: CategoryResources) =
        if (obj == null) default
        else CategoryResources(
            tip1Url = obj.optString("tip1_url", default.tip1Url),
            tip2Url = obj.optString("tip2_url", default.tip2Url)
        )

    private fun parseSafetyResources(res: JSONObject, defaults: SafetyResources) = SafetyResources(
        sextortion       = parseCategoryResources(res.optJSONObject("sextortion"),        defaults.sextortion),
        financialScam    = parseCategoryResources(res.optJSONObject("financial_scam"),    defaults.financialScam),
        grooming         = parseCategoryResources(res.optJSONObject("grooming"),          defaults.grooming),
        romanceScam      = parseCategoryResources(res.optJSONObject("romance_scam"),      defaults.romanceScam),
        identityPhishing = parseCategoryResources(res.optJSONObject("identity_phishing"), defaults.identityPhishing),
        luring           = parseCategoryResources(res.optJSONObject("luring"),            defaults.luring),
        harassment       = parseCategoryResources(res.optJSONObject("harassment"),        defaults.harassment)
    )

    private fun parseHelpResources(arr: JSONArray): List<HelpResource> =
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url  = obj.optString("url").takeIf  { it.isNotBlank() } ?: return@mapNotNull null
            HelpResource(name, url)
        }
}
