package com.agenthita.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.agenthita.app.storage.HitaDatabase
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class HitaApplication : Application() {

    val database: HitaDatabase by lazy { HitaDatabase.getInstance(this) }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Pre-warm SQLCipher + Room on a background thread so the first
        // access in DashboardActivity doesn't block the main thread
        GlobalScope.launch(Dispatchers.IO) { database.riskEventDao() }
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
        // a non-dismissable indicator that Agent Hita is active (consent.html safeguard #1)
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "Agent Hita Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indicates Agent Hita is actively monitoring"
        }

        notificationManager.createNotificationChannels(listOf(warningChannel, statusChannel))
    }

    companion object {
        const val CHANNEL_WARNINGS = "hita_warnings"
        const val CHANNEL_STATUS = "hita_status"
    }
}
