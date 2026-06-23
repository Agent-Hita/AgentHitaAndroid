package com.agenthita.app.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agenthita.app.HitaApplication
import com.agenthita.app.telemetry.TelemetryManager

class EventPruneWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "event_prune_daily"
    }

    override suspend fun doWork(): Result {
        return try {
            val store = RiskEventStore(
                (applicationContext as HitaApplication).database.riskEventDao()
            )
            store.pruneByTier()
            TelemetryManager.get(applicationContext).track("event_prune_run")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("EventPruneWorker", "Prune failed: ${e.message}", e)
            TelemetryManager.get(applicationContext).track("event_prune_failed")
            Result.retry()
        }
    }
}
