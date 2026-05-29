package com.agenthita.app.config

import android.content.Context
import android.util.Log
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

    // The single hardcoded URL — all other URLs come from the config itself.
    private const val CONFIG_URL =
        "https://config.agenthita.com/app_config.json"

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
                conn.connectTimeout = 5_000
                conn.readTimeout    = 10_000
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

    val telemetryEndpoint: String  get() = current.telemetryEndpoint
    val alertEndpoint: String      get() = current.alertEndpoint
    val feedbackEndpoint: String   get() = current.feedbackEndpoint
    val apiKey: String             get() = current.apiKey
    val connectTimeoutMs: Int      get() = current.connectTimeoutMs
    val readTimeoutMs: Int         get() = current.readTimeoutMs

    val telemetryFlushThreshold: Int  get() = current.telemetryFlushThreshold
    val telemetryFlushIntervalMs: Long get() = current.telemetryFlushIntervalMs
    val appVersion: String            get() = current.appVersion
    val buildType: String             get() = current.buildType

    val gemmaMaxTokens: Int             get() = current.gemmaMaxTokens
    val gemmaInputTruncationChars: Int  get() = current.gemmaInputTruncationChars
    val gemmaContextMessages: Int       get() = current.gemmaContextMessages
    val gemmaContextMessageLength: Int  get() = current.gemmaContextMessageLength
    val kaggleUrl: String              get() = current.kaggleUrl
    val gemmaModelHashes: List<String>  get() = current.gemmaModelHashes
    val gemmaMaxFileSizeBytes: Long     get() = current.gemmaMaxFileSizeBytes

    val riskThresholds: RiskThresholds  get() = current.riskThresholds
    val safetyResources: SafetyResources get() = current.safetyResources
    val helpResources: List<HelpResource> get() = current.helpResources

    val minMessageLength: Int      get() = current.minMessageLength
    val targetPackages: Set<String> get() = current.targetPackages

    // ── Data classes ──────────────────────────────────────────────────────────

    data class RiskBand(val high: Float, val medium: Float, val low: Float)

    data class RiskThresholds(
        val child:      RiskBand = RiskBand(0.40f, 0.18f, 0.08f),
        val adolescent: RiskBand = RiskBand(0.48f, 0.22f, 0.10f),
        val adult:      RiskBand = RiskBand(0.55f, 0.28f, 0.12f)
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

        // API
        val telemetryEndpoint: String = "https://api.agenthita.com/telemetry",
        val alertEndpoint:     String = "https://api.agenthita.com/alert/guardian",
        val feedbackEndpoint:  String = "https://api.agenthita.com/feedback",
        val apiKey:            String = "szJ7jHrFa4HhVPzQW8DO+pCZNi9SSxIIq8ApBTH38EM=",
        val connectTimeoutMs:  Int    = 10_000,
        val readTimeoutMs:     Int    = 15_000,

        // Telemetry
        val telemetryFlushThreshold: Int    = 50,
        val telemetryFlushIntervalMs: Long  = 60_000L,
        val appVersion:              String = "0.1.0-poc",
        val buildType:               String = "release",

        // Gemma
        val gemmaMaxTokens:            Int    = 200,
        val gemmaInputTruncationChars: Int    = 300,
        val gemmaContextMessages:      Int    = 5,
        val gemmaContextMessageLength: Int    = 80,
        val kaggleUrl:                 String = "https://www.kaggle.com/models/google/gemma/tfLite",
        // SHA-256 hex digests of known-good model files. Empty = skip verification (POC default).
        // Populate via remote config once you have hashes for the shipped model variants.
        val gemmaModelHashes:          List<String> = emptyList(),
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

        return defaults.copy(
            version = root.optInt("version", defaults.version),

            // API
            telemetryEndpoint = api?.optString("telemetry_endpoint", defaults.telemetryEndpoint) ?: defaults.telemetryEndpoint,
            alertEndpoint     = api?.optString("alert_endpoint",     defaults.alertEndpoint)     ?: defaults.alertEndpoint,
            feedbackEndpoint  = api?.optString("feedback_endpoint",  defaults.feedbackEndpoint)  ?: defaults.feedbackEndpoint,
            apiKey            = api?.optString("api_key",            defaults.apiKey)            ?: defaults.apiKey,
            connectTimeoutMs  = api?.optInt("connect_timeout_ms",    defaults.connectTimeoutMs)  ?: defaults.connectTimeoutMs,
            readTimeoutMs     = api?.optInt("read_timeout_ms",       defaults.readTimeoutMs)     ?: defaults.readTimeoutMs,

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
            kaggleUrl                 = gem?.optString("kaggle_url",          defaults.kaggleUrl)                 ?: defaults.kaggleUrl,
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
                ?: defaults.targetPackages
        )
    }

    private fun parseBand(obj: JSONObject, default: RiskBand) = RiskBand(
        high   = obj.optDouble("high",   default.high.toDouble()).toFloat(),
        medium = obj.optDouble("medium", default.medium.toDouble()).toFloat(),
        low    = obj.optDouble("low",    default.low.toDouble()).toFloat()
    )

    private fun parseBands(thr: JSONObject, defaults: RiskThresholds) = RiskThresholds(
        child      = thr.optJSONObject("child")?.let      { parseBand(it, defaults.child) }      ?: defaults.child,
        adolescent = thr.optJSONObject("adolescent")?.let { parseBand(it, defaults.adolescent) } ?: defaults.adolescent,
        adult      = thr.optJSONObject("adult")?.let      { parseBand(it, defaults.adult) }      ?: defaults.adult
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
