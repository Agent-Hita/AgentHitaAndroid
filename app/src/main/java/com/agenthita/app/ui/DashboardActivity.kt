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
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.agenthita.app.model.ModelDownloadWorker
import com.agenthita.app.storage.ContactNameDao
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

        private val GEMMA_TERMS = """
            GOOGLE GEMMA TERMS OF USE
            Last Updated: February 21, 2024

            By using or distributing any portion of the Gemma model(s), you agree to be bound by these Terms of Use ("Terms"). If you do not agree to these Terms, do not use the Gemma model(s).

            1. DEFINITIONS
            "Google" means Google LLC. "Gemma" means the machine learning model(s) and any accompanying software, documentation, and other materials made available by Google under these Terms. "Outputs" means any content generated through use of the Gemma model(s).

            2. USE RIGHTS
            Subject to these Terms, Google grants you a non-exclusive, worldwide, non-transferable, non-sublicensable, royalty-free limited license to: (a) use and reproduce the Gemma model(s); (b) distribute the Gemma model(s); and (c) create and distribute derivative works of the Gemma model(s).

            3. DISTRIBUTION
            If you distribute or make available Gemma or any derivative works, you must: (a) include a copy of or a link to these Terms with any distribution; (b) not misrepresent the origin of the model(s); and (c) retain all copyright, patent, trademark, and attribution notices.

            4. ADDITIONAL COMMERCIAL TERMS
            If your business or the organization you represent has total annual gross revenues exceeding $10 million USD, you may not use Gemma under these Terms and must contact Google to obtain a separate commercial license.

            5. GEMMA PROHIBITED USE POLICY
            You agree that you will not use, and will not permit others to use, Gemma or its Outputs to:

            (a) Violate any applicable law or regulation, or the rights of any person or entity, including intellectual property rights, privacy rights, or rights of personality;

            (b) Engage in, promote, incite, facilitate, or assist in harassment, abuse, threatening, or bullying of individuals or groups;

            (c) Generate content that is hateful or discriminatory based on race, ethnicity, national origin, religion, sex, gender, sexual orientation, disability, or caste;

            (d) Generate, distribute, or facilitate disinformation, misinformation, or propaganda intended to cause harm or deceive;

            (e) Generate or distribute sexually explicit content, or any content that sexualizes minors;

            (f) Facilitate the sexual exploitation or abuse of minors in any way;

            (g) Develop, assist in developing, or deploy weapons of mass destruction, including biological, chemical, nuclear, or radiological weapons;

            (h) Plan or facilitate attacks on critical infrastructure or public safety systems;

            (i) Create cyberweapons, malicious code, or tools designed to cause significant damage if deployed;

            (j) Infringe, misappropriate, or violate the intellectual property rights of any third party;

            (k) Engage in unauthorized collection, processing, or use of personal data;

            (l) Impersonate any person or entity, or misrepresent your affiliation with any person or entity.

            6. INTELLECTUAL PROPERTY
            Google retains all ownership and intellectual property rights in and to Gemma. Except for the licenses expressly granted in these Terms, Google does not grant you any rights in Google's trademarks, trade names, or service marks.

            7. FEEDBACK
            If you provide Google with feedback, ideas, or suggestions regarding Gemma, you grant Google a perpetual, irrevocable, fully paid-up, royalty-free, worldwide license to use and incorporate that feedback without restriction or obligation to you.

            8. DISCLAIMER OF WARRANTIES
            GEMMA IS PROVIDED "AS IS" WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, OR FITNESS FOR A PARTICULAR PURPOSE. GOOGLE DOES NOT WARRANT THAT THE OUTPUTS WILL BE ACCURATE, COMPLETE, OR SUITABLE FOR ANY PARTICULAR PURPOSE.

            9. LIMITATION OF LIABILITY
            TO THE FULLEST EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT WILL GOOGLE OR ITS AFFILIATES, DIRECTORS, OFFICERS, EMPLOYEES, OR AGENTS BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR EXEMPLARY DAMAGES ARISING FROM OR IN ANY WAY CONNECTED WITH YOUR ACCESS TO OR USE OF GEMMA, EVEN IF GOOGLE HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

            10. INDEMNIFICATION
            You agree to indemnify, defend, and hold harmless Google and its affiliates, directors, officers, employees, and agents from and against any and all claims, liabilities, damages, losses, and expenses (including reasonable attorneys' fees) arising out of or in any way connected with: (a) your access to or use of Gemma; (b) your Outputs; or (c) your violation of these Terms.

            11. TERM AND TERMINATION
            These Terms will remain in effect until terminated. Google may terminate your rights under these Terms immediately and without notice for any breach of these Terms. Upon termination, all licenses granted to you under these Terms will immediately cease.

            12. CHANGES TO TERMS
            Google may update these Terms from time to time at its sole discretion. Your continued use of Gemma after any such changes constitutes your acceptance of the revised Terms.

            13. GOVERNING LAW
            These Terms are governed by and construed in accordance with the laws of the State of Delaware, USA, without regard to its conflict of law provisions.

            Full terms: ai.google.dev/gemma/terms
        """.trimIndent()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var consentManager: ConsentManager
    private lateinit var eventStore: RiskEventStore
    private lateinit var contactNameDao: ContactNameDao
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
        contactNameDao      = app.database.contactNameDao()
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
            TelemetryManager.get(this).track("gemma_download_tapped")
            showGemmaTermsDialog()
        }

        observeModelDownload()
        maybePromptModelDownload()

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
                    val nameMap = withContext(Dispatchers.IO) {
                        contactNameDao.getAll().associate { it.contactHash to it.displayName }
                    }
                    eventAdapter.nameMap = nameMap
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
                        handlePrivateAiSettingsTap()
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

        val modelReady = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
            .getBoolean("gemma_loaded", false)
        val showEmptyState = filtered.isEmpty() && allEvents.isEmpty() && modelReady

        binding.layoutEmptyState.visibility = if (showEmptyState) View.VISIBLE else View.GONE
        binding.rvRecentEvents.visibility   = if (showEmptyState) View.GONE else View.VISIBLE

        binding.tvEventSummary.text = when {
            showEmptyState -> ""
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

        if (loadFailed) {
            binding.tvAiStatus.text = "Private AI Model: Error — tap to reinstall"
            binding.layoutAiStatus.visibility = View.VISIBLE
            return
        }

        if (aiPrefs.getBoolean("gemma_loaded", false)) {
            binding.layoutAiStatus.visibility = View.GONE
            applyFilters()
            return
        }

        // Hide the chip while download is running — the banner above handles that state
        val downloading = WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (downloading) {
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
        if (inFilesDir) {
            healGemmaLoadedPref()
            binding.layoutAiStatus.visibility = View.GONE
            return
        }

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

        return false
    }

    private fun isModelFile(name: String): Boolean {
        val lower = name.lowercase()
        // Match .bin, .task, .tar.gz, and Kaggle split-archive names like .tar-1.gz
        return lower.endsWith(".bin") || lower.endsWith(".task") || lower.endsWith(".gz")
    }

    private fun modelOnDisk(): Boolean =
        filesDir.listFiles()?.any { f ->
            (f.name.endsWith(".bin") || f.name.endsWith(".task")) && f.length() > 100_000_000L
        } == true

    private fun healGemmaLoadedPref() {
        getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
            .edit().putBoolean("gemma_loaded", true).apply()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName, ignoreCase = true)
    }

    private fun maybePromptModelDownload() {
        val aiPrefs = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
        val modelReady = aiPrefs.getBoolean("gemma_loaded", false)
        if (modelReady) return

        val onDisk = modelOnDisk()
        if (onDisk) {
            healGemmaLoadedPref()
            return
        }

        val workInfos = WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.WORK_NAME).get()
        if (workInfos.any { it.state == WorkInfo.State.RUNNING }) return
        // ENQUEUED with runAttemptCount > 0 means stuck in backoff after an interrupted
        // download — cancel so we re-enqueue immediately rather than waiting up to 60 min.
        if (workInfos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }) {
            WorkManager.getInstance(this).cancelUniqueWork(ModelDownloadWorker.WORK_NAME)
        } else if (workInfos.any { it.state == WorkInfo.State.ENQUEUED }) {
            return
        }

        val termsAccepted = aiPrefs.getBoolean("gemma_terms_accepted", false)
        if (!termsAccepted) showGemmaTermsDialog()
        else startModelDownload()
    }

    private fun handlePrivateAiSettingsTap() {
        val aiPrefs = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
        if (aiPrefs.getBoolean("gemma_loaded", false) || modelOnDisk()) {
            showAiInfoDialog(
                title   = "Private AI Model",
                message = "The on-device AI model is downloaded and enabled. All analysis runs privately on your device — no data leaves it."
            )
            return
        }
        val downloading = WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (downloading) {
            showAiInfoDialog(
                title   = "Private AI Model",
                message = "The model is currently downloading in the background. This may take a few minutes."
            )
            return
        }
        showGemmaTermsDialog()
    }

    private fun showAiInfoDialog(title: String, message: String) {
        val view = layoutInflater.inflate(R.layout.dialog_ai_info, null)
        view.findViewById<android.widget.TextView>(R.id.tv_dialog_title).text   = title
        view.findViewById<android.widget.TextView>(R.id.tv_dialog_message).text = message
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setView(view)
            .create()
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_ok)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showGemmaTermsDialog() {
        val aiPrefs = getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)

        val view = layoutInflater.inflate(R.layout.dialog_gemma_terms, null)
        val scrollTerms  = view.findViewById<android.widget.ScrollView>(R.id.scroll_terms)
        val tvTerms      = view.findViewById<android.widget.TextView>(R.id.tv_terms_content)
        val tvScrollHint = view.findViewById<android.widget.TextView>(R.id.tv_scroll_hint)
        val btnAccept    = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_accept_download)
        val btnNotNow    = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_not_now)

        tvTerms.text = GEMMA_TERMS

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setView(view)
            .create()

        fun onScrolled() {
            if (!scrollTerms.canScrollVertically(1)) {
                btnAccept.isEnabled = true
                tvScrollHint.visibility = View.GONE
            }
        }

        scrollTerms.setOnScrollChangeListener { _, _, _, _, _ -> onScrolled() }
        // If the terms text fits without scrolling, unlock immediately after layout.
        scrollTerms.post { onScrolled() }

        btnAccept.setOnClickListener {
            aiPrefs.edit().putBoolean("gemma_terms_accepted", true).apply()
            TelemetryManager.get(this).track("gemma_terms_accepted")
            TelemetryManager.get(this).flush()
            dialog.dismiss()
            startModelDownload()
        }

        btnNotNow.setOnClickListener {
            TelemetryManager.get(this).track("gemma_terms_declined")
            TelemetryManager.get(this).flush()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startModelDownload() {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            ModelDownloadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        TelemetryManager.get(this).track("gemma_download_started")
    }

    private fun observeModelDownload() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.WORK_NAME)
            .observe(this) { workInfos ->
                val info = workInfos?.firstOrNull()
                when (info?.state) {
                    WorkInfo.State.RUNNING -> {
                        val phase = info.progress.getString(ModelDownloadWorker.KEY_PHASE)
                        val pct   = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, -1)
                        showDownloadBanner(phase, pct)
                    }
                    WorkInfo.State.ENQUEUED -> {
                        // runAttemptCount > 0 means this is waiting to retry after a failure.
                        // Only show the banner if the file isn't already on disk — the worker
                        // will find it and succeed on the next run without actually downloading.
                        if (info.runAttemptCount > 0 && !modelOnDisk()) {
                            showDownloadBanner(ModelDownloadWorker.PHASE_DOWNLOAD, -1)
                        } else {
                            binding.layoutModelDownload.visibility = View.GONE
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.layoutModelDownload.visibility = View.GONE
                        binding.layoutAiStatus.visibility = View.GONE
                    }
                    WorkInfo.State.FAILED -> {
                        // The OS may have killed the worker after extraction completed but before
                        // it could write gemma_loaded=true. Check disk before showing an error.
                        if (modelOnDisk()) {
                            healGemmaLoadedPref()
                            binding.layoutModelDownload.visibility = View.GONE
                            binding.layoutAiStatus.visibility = View.GONE
                        } else {
                            binding.layoutModelDownload.visibility = View.GONE
                            binding.tvAiStatus.text = "Private AI Model: Download failed — tap to retry"
                            binding.layoutAiStatus.visibility = View.VISIBLE
                        }
                    }
                    else -> binding.layoutModelDownload.visibility = View.GONE
                }
            }
    }

    private fun showDownloadBanner(phase: String?, pct: Int) {
        binding.layoutAiStatus.visibility = View.GONE
        binding.layoutModelDownload.visibility = View.VISIBLE
        when (phase) {
            ModelDownloadWorker.PHASE_DOWNLOAD -> {
                if (pct >= 0) {
                    binding.progressModelDownload.isIndeterminate = false
                    binding.progressModelDownload.progress = pct
                    binding.tvModelDownloadLabel.text = "Downloading private AI model… $pct%"
                } else {
                    binding.progressModelDownload.isIndeterminate = true
                    binding.tvModelDownloadLabel.text = "Downloading private AI model…"
                }
            }
            ModelDownloadWorker.PHASE_EXTRACT -> {
                binding.progressModelDownload.isIndeterminate = true
                binding.tvModelDownloadLabel.text = "Extracting private AI model…"
            }
            ModelDownloadWorker.PHASE_DONE -> {
                binding.layoutModelDownload.visibility = View.GONE
            }
        }
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
