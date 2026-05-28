package com.agenthita.poc.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import com.agenthita.poc.HitaApplication
import com.agenthita.poc.R
import com.agenthita.poc.consent.AntiCoercionMonitor
import com.agenthita.poc.consent.ConsentManager
import com.agenthita.poc.databinding.ActivityDashboardBinding
import com.agenthita.poc.storage.RiskEvent
import com.agenthita.poc.storage.RiskEventStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var consentManager: ConsentManager
    private lateinit var eventStore: RiskEventStore
    private lateinit var antiCoercionMonitor: AntiCoercionMonitor
    private lateinit var eventAdapter: RiskEventAdapter

    private var allEvents: List<RiskEvent> = emptyList()
    private var activeDateChipId: Int = R.id.chip_date_all
    private var activeCategoryChipId: Int = R.id.chip_cat_all

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
        updateStatusDot()
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
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
        val active = isNotificationListenerEnabled()
        val dot = findViewById<View>(R.id.view_status_dot) ?: return
        val drawable = dot.background.mutate()
        DrawableCompat.setTint(drawable, if (active) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
        dot.background = drawable
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun showAutonomyPrompt() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Your safety settings")
            .setMessage(
                "Do you still want your guardian to receive alerts when Agent Hita detects " +
                "something concerning?\n\nYour answer is private — your guardian won't be told either way."
            )
            .setPositiveButton("Yes, keep alerts on") { _, _ ->
                consentManager.isGuardianAlertsEnabled = true
            }
            .setNegativeButton("Turn alerts off") { _, _ ->
                consentManager.isGuardianAlertsEnabled = false
            }
            .setNeutralButton("Ask me later", null)
            .show()
    }
}
