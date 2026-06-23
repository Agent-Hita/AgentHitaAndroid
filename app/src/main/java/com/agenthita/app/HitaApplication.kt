package com.agenthita.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.security.DeviceTokenManager
import com.agenthita.app.storage.EventPruneWorker
import com.agenthita.app.storage.HitaDatabase
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class HitaApplication : Application() {

    val database: HitaDatabase by lazy { HitaDatabase.getInstance(this) }

    /** Millisecond timestamp captured at process start — used to measure cold-start durations. */
    val startMs: Long = System.currentTimeMillis()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        RemoteConfig.init(this)
        RemoteConfig.fetchAsync(this)
        GlobalScope.launch(Dispatchers.IO) {
            try { DeviceTokenManager.getToken(this@HitaApplication) }
            catch (e: Exception) { Log.w("HitaApplication", "Device token pre-warm failed: ${e.message}") }
        }
        createNotificationChannels()
        installCrashHandler()
        installForegroundTracker()
        // Pre-warm the WebView renderer process so TermsActivity's local-asset
        // load is instant. Without this, the first WebView instantiation blocks
        // for 300–700 ms while the renderer process starts, keeping the progress
        // bar visible even though the content is already on-device.
        WebView(this).destroy()
        // Pre-warm SQLCipher + Room on a background thread so the first
        // access in DashboardActivity doesn't block the main thread
        GlobalScope.launch(Dispatchers.IO) { database.riskEventDao() }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            EventPruneWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EventPruneWorker>(1, TimeUnit.DAYS).build()
        )
    }

    /**
     * Tracks app-level foreground/background transitions using the activity
     * reference count pattern: when the count goes 0→1 the app opened;
     * when it drops back to 0 the app closed.
     *
     * This fires exactly once per foreground session regardless of how many
     * activities are in the back stack, and handles activity rotations correctly
     * (a configuration-change recreation increments then decrements before
     * the new instance increments again — net zero change).
     */
    private fun installForegroundTracker() {
        var resumedCount = 0
        var startupTracked = false
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedCount++
                if (resumedCount == 1) {
                    TelemetryManager.get(this@HitaApplication).track("app_open")
                    if (!startupTracked) {
                        startupTracked = true
                        val startupMs = System.currentTimeMillis() - startMs
                        TelemetryManager.get(this@HitaApplication).track("app_startup_ms", startupMs.toDouble())
                    }
                }
            }
            override fun onActivityPaused(activity: Activity) {
                resumedCount--
                if (resumedCount == 0) {
                    TelemetryManager.get(this@HitaApplication).track("app_close")
                    TelemetryManager.get(this@HitaApplication).flush()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Installs an uncaught-exception handler that tracks crashes via telemetry
     * before delegating to the system default handler (which writes the crash log
     * and shows the "App has stopped" dialog as normal).
     *
     * Fires a synchronous flush so the event reaches the backend even if the
     * process is about to be killed — telemetry batching is bypassed here.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("HitaApplication", "Uncaught exception on ${thread.name}", throwable)
                TelemetryManager.get(this).track("app_crash")
                TelemetryManager.get(this).flush()
                // Give the flush coroutine a moment to dispatch before process dies
                Thread.sleep(300)
            } catch (_: Exception) {
                // Never let the crash handler itself crash
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // High-priority channel for safety warnings shown to the user
        val warningChannel = NotificationChannel(
            CHANNEL_WARNINGS,
            "Safety Warnings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Agent Hita safety pattern alerts — shown to you privately"
        }

        // Low-priority persistent channel satisfying the anti-coercion transparency requirement:
        // a non-dismissable indicator that Agent Hita is active (consent.html safeguard #1).
        // Badges are disabled — this is a background status indicator, not an actionable alert.
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "Agent Hita Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indicates Agent Hita is actively monitoring"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(listOf(warningChannel, statusChannel))
    }

    companion object {
        const val CHANNEL_WARNINGS = "hita_warnings"
        const val CHANNEL_STATUS = "hita_status"
    }
}
