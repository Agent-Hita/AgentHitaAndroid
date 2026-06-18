package com.agenthita.app.model

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.service.HitaAccessibilityService
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME    = "gemma_model_download"
        const val KEY_PHASE    = "phase"
        const val KEY_PROGRESS = "progress"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_EXTRACT  = "extract"
        const val PHASE_DONE     = "done"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Already installed — nothing to do
        val existing = appContext.filesDir.listFiles()?.firstOrNull { f ->
            (f.name.endsWith(".bin") || f.name.endsWith(".task")) &&
            !f.name.lowercase().contains("gpu") &&
            f.length() > 100_000_000L
        }
        if (existing != null) {
            notifyModelReady()
            return@withContext Result.success()
        }

        val archiveDest = File(appContext.cacheDir, "gemma_model_download.tar.gz")
        val startMs = System.currentTimeMillis()

        return@withContext try {
            // ── Phase 1: Download ─────────────────────────────────────────────
            setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD, KEY_PROGRESS to 0))

            // /model/* on CloudFront requires a signed URL — fetch one from the backend first.
            val deviceId = ConsentManager(appContext).userId
            val signedUrlConn = URL(RemoteConfig.modelSignedUrlEndpoint).openConnection() as HttpURLConnection
            signedUrlConn.connectTimeout = 15_000
            signedUrlConn.readTimeout    = 15_000
            signedUrlConn.setRequestProperty("X-Api-Key",   RemoteConfig.apiKey)
            signedUrlConn.setRequestProperty("X-Device-Id", deviceId)
            signedUrlConn.connect()

            val signedUrlStatus = signedUrlConn.responseCode
            if (signedUrlStatus !in 200..299) {
                android.util.Log.w("ModelDownloadWorker", "Signed-URL request failed: HTTP $signedUrlStatus")
                signedUrlConn.disconnect()
                return@withContext Result.retry()
            }
            val signedUrl = JSONObject(signedUrlConn.inputStream.bufferedReader().readText()).getString("url")
            signedUrlConn.disconnect()
            android.util.Log.i("ModelDownloadWorker", "Received signed download URL")

            val conn = URL(signedUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 600_000

            // Resume partial download if the file exists and server supports range requests
            val existingBytes = if (archiveDest.exists()) archiveDest.length() else 0L
            if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")
            conn.connect()

            val serverTotal = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            val totalBytes  = if (existingBytes > 0 && serverTotal > 0) existingBytes + serverTotal else serverTotal
            var downloadedBytes = existingBytes

            val responseCode = conn.responseCode
            android.util.Log.i("ModelDownloadWorker", "HTTP $responseCode  content-length=${conn.contentLengthLong}  content-type=${conn.contentType}")
            if (responseCode !in 200..299) {
                conn.disconnect()
                return@withContext Result.retry()
            }

            val appendMode = responseCode == HttpURLConnection.HTTP_PARTIAL
            FileOutputStream(archiveDest, appendMode).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloadedBytes += n
                        val pct = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else -1
                        setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD, KEY_PROGRESS to pct))
                    }
                }
            }
            conn.disconnect()
            android.util.Log.i("ModelDownloadWorker", "Download complete: ${archiveDest.length() / 1_000_000} MB")

            // ── Phase 2: Extract ──────────────────────────────────────────────
            setProgress(workDataOf(KEY_PHASE to PHASE_EXTRACT, KEY_PROGRESS to -1))

            val extractedPath = archiveDest.inputStream().use { input ->
                GemmaClassifier.extractTarGz(appContext, input)
            }
            archiveDest.delete()

            if (extractedPath == null) {
                android.util.Log.e("ModelDownloadWorker", "Extraction failed")
                TelemetryManager.get(appContext).track("gemma_download_failed")
                return@withContext Result.retry()
            }

            val durationMs = System.currentTimeMillis() - startMs
            android.util.Log.i("ModelDownloadWorker", "Extraction complete: $extractedPath (${durationMs / 1000}s total)")
            TelemetryManager.get(appContext).track("gemma_download_success")
            TelemetryManager.get(appContext).track("gemma_download_duration_ms", durationMs.toDouble())
            TelemetryManager.get(appContext).flush()
            setProgress(workDataOf(KEY_PHASE to PHASE_DONE, KEY_PROGRESS to 100))
            notifyModelReady()
            Result.success()

        } catch (e: Exception) {
            android.util.Log.e("ModelDownloadWorker", "Download/extract failed: ${e.message}")
            TelemetryManager.get(appContext).track("gemma_download_failed")
            // Keep the partial archive so the next retry can resume
            Result.retry()
        }
    }

    private fun notifyModelReady() {
        appContext.getSharedPreferences("hita_ai_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("gemma_loaded", true).apply()
        appContext.sendBroadcast(
            Intent(HitaAccessibilityService.ACTION_MODEL_AVAILABLE).apply {
                `package` = appContext.packageName
            }
        )
    }
}
