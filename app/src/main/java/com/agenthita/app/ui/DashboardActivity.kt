package com.agenthita.app.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.agenthita.app.HitaApplication
import com.agenthita.app.R
import com.agenthita.app.consent.AntiCoercionMonitor
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.databinding.ActivityDashboardBinding
import com.agenthita.app.storage.RiskEvent
import com.agenthita.app.storage.RiskEventStore
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class DashboardActivity : AppCompatActivity() {

    companion object {
        // Heartbeat is written every 60 s; allow 3 min before calling it frozen.
        private const val SERVICE_FROZEN_THRESHOLD_MS = 3 * 60 * 1000L
        // After detecting frozen, recheck after this delay — gives the unfreezing
        // process time to write a fresh heartbeat before we update the UI.
        private const val FROZEN_RECHECK_DELAY_MS = 3_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var consentManager: ConsentManager
    private lateinit var eventStore: RiskEventStore
    private lateinit var antiCoercionMonitor: AntiCoercionMonitor
    private lateinit var eventAdapter: RiskEventAdapter

    private var allEvents: List<RiskEvent> = emptyList()
    private var activeDateChipId: Int = R.id.chip_date_all
    private var activeCategoryChipId: Int = R.id.chip_cat_all
    private var enableDialog: androidx.appcompat.app.AlertDialog? = null
    private var aiStatusJob: Job? = null

    private val aiPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "gemma_loaded" || key == "gemma_load_failed") updateAiStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        val app = application as HitaApplication
        consentManager      = ConsentManager(this)
        eventStore          = RiskEventStore(app.database.riskEventDao())
        antiCoercionMonitor = AntiCoercionMonitor(this, consentManager)

        eventAdapter = RiskEventAdapter { event ->
            val intent = Intent(this, EventDetailActivity::class.java)
            intent.putExtra(EventDetailActivity.EXTRA_EVENT_ID, event.id)
            startActivity(intent)
        }
        binding.rvRecentEvents.apply {
            layoutManager = LinearLayoutManager(this@DashboardActivity)
            adapter = eventAdapter
        }

        updateStatusDot()
        updateAiStatus()

        binding.layoutAiStatus.setOnClickListener {
            startActivity(Intent(this, GemmaDownloadActivity::class.java))
        }

        binding.chipGroupDate.setOnCheckedStateChangeListener { _, checkedIds ->
            activeDateChipId = checkedIds.firstOrNull() ?: R.id.chip_date_all
            applyFilters()
        }

        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            activeCategoryChipId = checkedIds.firstOrNull() ?: R.id.chip_cat_all
            applyFilters()
        }

        // repeatOnLifecycle(STARTED) restarts collection every time the activity
        // becomes visible — ensures list and counts update after returning from background
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                eventStore.getRecentEvents().collectLatest { events ->
                    allEvents = events
                    applyFilters()
                }
            }
        }

        if (antiCoercionMonitor.shouldShowAutonomyPrompt()) {
            showAutonomyPrompt()
            antiCoercionMonitor.recordAutonomyPromptShown()
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(aiPrefsListener)
        updateStatusDot()
        updateAiStatus()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(aiPrefsListener)
        aiStatusJob?.cancel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_help) {
            startActivity(Intent(this, FeedbackActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_settings) {
            val anchor: View = findViewById(R.id.action_settings) ?: binding.toolbar
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.popup_settings, popup.menu)
            popup.setOnMenuItemClickListener { subItem ->
                when (subItem.itemId) {
                    R.id.popup_guardian -> {
                        startActivity(Intent(this, GuardianSetupActivity::class.java))
                        true
                    }
                    R.id.popup_notification_settings -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        true
                    }
                    R.id.popup_private_ai -> {
                        startActivity(Intent(this, GemmaDownloadActivity::class.java))
                        true
                    }
else -> false
                }
            }
            popup.show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applyFilters() {
        val now = System.currentTimeMillis()
        val dateThreshold: Long = when (activeDateChipId) {
            R.id.chip_date_today -> startOfToday()
            R.id.chip_date_week  -> now - 7L  * 24 * 60 * 60 * 1000
            R.id.chip_date_month -> now - 30L * 24 * 60 * 60 * 1000
            else                 -> 0L
        }

        val categoryFilter: String? = when (activeCategoryChipId) {
            R.id.chip_cat_sextortion  -> "SEXTORTION"
            R.id.chip_cat_financial   -> "FINANCIAL_SCAM"
            R.id.chip_cat_grooming    -> "GROOMING"
            R.id.chip_cat_romance     -> "ROMANCE_SCAM"
            R.id.chip_cat_phishing    -> "IDENTITY_PHISHING"
            R.id.chip_cat_luring      -> "LURING"
            R.id.chip_cat_harassment  -> "HARASSMENT"
            else                      -> null
        }

        val filtered = allEvents.filter { event ->
            event.timestampMs >= dateThreshold &&
            (categoryFilter == null || event.harmCategory == categoryFilter)
        }

        eventAdapter.submitList(filtered)

        val highCount  = filtered.count { it.riskLevel == "HIGH" }
        val medCount   = filtered.count { it.riskLevel == "MEDIUM" }
        val totalCount = highCount + medCount

        binding.tvEventSummary.text = when {
            filtered.isEmpty() && allEvents.isEmpty() -> "No alerts detected yet."
            filtered.isEmpty() -> "No alerts match the current filter."
            else -> buildString {
                append("$totalCount alert${if (totalCount != 1) "s" else ""} detected")
                if (highCount > 0) append(" — $highCount high-risk")
                if (medCount > 0)  append(", $medCount medium-risk")
            }
        }
    }

    private fun startOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun updateStatusDot() {
        val enabled = isNotificationListenerEnabled()
        val dot = findViewById<View>(R.id.view_status_dot) ?: return
        val label = findViewById<android.widget.TextView>(R.id.tv_status_label)
        val drawable = dot.background.mutate()

        if (!enabled) {
            DrawableCompat.setTint(drawable, Color.parseColor("#EF4444"))
            dot.background = drawable
            label?.text = "Monitoring is not active"
            if (enableDialog?.isShowing != true) showEnableAccessibilityDialog()
            return
        }

        // Service is enabled — check whether the process is actually alive via heartbeat.
        val lastAlive = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
            .getLong("service_last_alive_ms", 0L)
        val frozen = lastAlive > 0L && (System.currentTimeMillis() - lastAlive) > SERVICE_FROZEN_THRESHOLD_MS

        if (frozen) {
            DrawableCompat.setTint(drawable, Color.parseColor("#F59E0B")) // amber — degraded
            dot.background = drawable
            label?.text = "Monitoring was paused by the OS — tap to fix"
            label?.setOnClickListener { requestBatteryOptimizationExemption() }
            TelemetryManager.get(this).track("service_frozen")
            android.util.Log.w("DashboardActivity", "Service heartbeat stale by ${(System.currentTimeMillis() - lastAlive) / 1000}s — reporting service_frozen")
            // Opening this app unfreezes the process; give the heartbeat timer 3s to
            // write a fresh value before rechecking, so the UI recovers automatically.
            mainHandler.postDelayed({ updateStatusDot() }, FROZEN_RECHECK_DELAY_MS)
            requestBatteryOptimizationExemption()
        } else {
            label?.setOnClickListener(null)
            DrawableCompat.setTint(drawable, Color.parseColor("#10B981"))
            dot.background = drawable
            label?.text = "Monitoring is active"
        }

        // Dismiss the enable-prompt if it was shown for a previous disabled state.
        enableDialog?.dismiss()
        enableDialog = null
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                // Fallback: open general battery optimization settings
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
    }

    private fun showEnableAccessibilityDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_monitoring_inactive, null)

        enableDialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setView(view)
            .setOnDismissListener { enableDialog = null }
            .create()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_enable)
            .setOnClickListener {
                enableDialog?.dismiss()
                // Android 13+: deep-link directly to this app's accessibility detail page.
                // Older Android: open the general accessibility settings list.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    try {
                        startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", android.net.Uri.parse("package:$packageName")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_not_now)
            .setOnClickListener {





                TelemetryManager.get(this).track("permission_accessibility_denied")
                enableDialog?.dismiss()
            }

        enableDialog?.show()
    }

    private fun updateAiStatus() {
        val aiPrefs = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
        val loadFailed = aiPrefs.getBoolean("gemma_load_failed", false)

        // Model found but MediaPipe failed to initialise it (corrupt file, OOM, etc.).
        // Show the chip with an error label regardless of any other state — user must reinstall.
        if (loadFailed) {
            binding.tvAiStatus.text = "Private AI Model: Error — tap to reinstall"
            binding.layoutAiStatus.visibility = View.VISIBLE
            return
        }

        // Model loaded successfully — hide the chip.
        if (aiPrefs.getBoolean("gemma_loaded", false)) {
            binding.layoutAiStatus.visibility = View.GONE
            return
        }

        // Model not yet installed — check whether the file is present so the chip
        // disappears as soon as the service copies it, before gemma_loaded is written.
        binding.tvAiStatus.text = "Private AI Model: Download"

        // Synchronous check: filesDir is always accessible and populated after the service copies the model.
        val inFilesDir = filesDir.listFiles()?.any { f ->
            (f.name.endsWith(".bin") || f.name.endsWith(".task")) && f.length() > 100_000_000L
        } == true
        if (inFilesDir) { binding.layoutAiStatus.visibility = View.GONE; return }

        // All remaining checks involve I/O — run off the main thread.
        // Cancel any previous pending check so a stale result can't overwrite a later correct state.
        aiStatusJob?.cancel()
        aiStatusJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { modelFileAccessible() }
            // Re-check pref in case the service wrote gemma_loaded=true during our I/O.
            val nowLoaded = aiPrefs.getBoolean("gemma_loaded", false)
            binding.layoutAiStatus.visibility = if (found || nowLoaded) View.GONE else View.VISIBLE
        }
    }

    /**
     * Returns true if a Gemma model file is accessible by the app, using the same
     * detection strategies as GemmaClassifier.findModelPath() / copyModelFromMediaStore().
     *
     * Three strategies, in order:
     *   1. Direct FileInputStream probe on the same hardcoded candidate paths the service uses.
     *      Works for adb-pushed files without any storage permission — this is the path the
     *      service takes, and the dashboard must match it.
     *   2. MediaStore query — catches Kaggle browser downloads, no extra permission needed.
     *   3. Directory listing — only works with MANAGE_EXTERNAL_STORAGE, so it's a last resort.
     */
    private fun modelFileAccessible(): Boolean {
        // 1. Same candidate paths as GemmaClassifier.findModelPath() — covers adb-push scenario.
        val appFiles = getExternalFilesDir(null)?.absolutePath
        val internalFiles = filesDir.absolutePath
        val candidates = listOf(
            "$appFiles/gemma.task", "$appFiles/gemma.bin",
            "$internalFiles/gemma.task", "$internalFiles/gemma.bin",
            "/sdcard/Download/gemma-2b-it-cpu-int4.task",
            "/storage/emulated/0/Download/gemma-2b-it-cpu-int4.task",
            "/sdcard/Download/gemma-2b-it-cpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-gpu-int4.task",
            "/sdcard/Download/gemma-2b-it-gpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-gpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-cpu-int4.bin",
            "/sdcard/Download/gemma2-cpu-int4.bin",
            "/sdcard/Download/gemma.task",
            "/storage/emulated/0/Download/gemma.task",
            "/sdcard/Download/gemma.bin"
        )
        if (candidates.any { path ->
            try { java.io.FileInputStream(path).use { true } } catch (_: Exception) { false }
        }) {
            android.util.Log.i("HitaAI", "Model found via direct path probe")
            return true
        }

        // 2. MediaStore — catches Kaggle browser downloads and .gz archives.
        val cursor = runCatching {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.SIZE} > ?",
                arrayOf("100000000"), null
            )
        }.getOrNull()
        android.util.Log.i("HitaAI", "MediaStore rows: ${cursor?.count ?: "null"}")
        cursor?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val size = c.getLong(1)
                android.util.Log.i("HitaAI", "  MediaStore: $name — ${size / 1_000_000} MB")
                if (isModelFile(name)) {
                    android.util.Log.i("HitaAI", "Model found via MediaStore: $name")
                    return true
                }
            }
        }

        // 3. Direct directory scan — requires MANAGE_EXTERNAL_STORAGE; last resort.
        for (dir in listOf("/sdcard/Download", "/storage/emulated/0/Download").map { java.io.File(it) }) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (f.length() > 100_000_000L && isModelFile(f.name)) {
                    android.util.Log.i("HitaAI", "Model found via dir scan: ${f.absolutePath}")
                    return true
                }
            }
        }
        return false
    }

    private fun isModelFile(name: String): Boolean {
        val lower = name.lowercase()
        // Match .bin, .task, .tar.gz, and Kaggle split-archive names like .tar-1.gz
        return lower.endsWith(".bin") || lower.endsWith(".task") || lower.endsWith(".gz")
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName, ignoreCase = true)
    }

    private fun showAutonomyPrompt() {
        val view = layoutInflater.inflate(R.layout.dialog_autonomy_prompt, null)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setView(view)
            .create()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_keep_alerts)
            .setOnClickListener {
                consentManager.isGuardianAlertsEnabled = true
                dialog.dismiss()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_turn_off)
            .setOnClickListener {
                consentManager.isGuardianAlertsEnabled = false
                dialog.dismiss()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_ask_later)
            .setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
