package com.agenthita.app.model

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.security.DeviceTokenManager
import com.agenthita.app.service.HitaAccessibilityService
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class ModelDownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME      = "gemma_model_download"
        const val KEY_PHASE      = "phase"
        const val KEY_PROGRESS   = "progress"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_EXTRACT  = "extract"
        const val PHASE_DONE     = "done"
        private const val NUM_CHUNKS = 4
        private const val TAG        = "ModelDownloadWorker"
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

            val deviceId    = ConsentManager(appContext).userId
            val deviceToken = DeviceTokenManager.getToken(appContext)

            val signedUrlConn = URL(RemoteConfig.modelSignedUrlEndpoint).openConnection() as HttpURLConnection
            signedUrlConn.connectTimeout = 15_000
            signedUrlConn.readTimeout    = 15_000
            signedUrlConn.setRequestProperty("X-Device-Token", deviceToken)
            signedUrlConn.setRequestProperty("X-Device-Id",    deviceId)
            signedUrlConn.connect()

            val signedUrlStatus = signedUrlConn.responseCode
            if (signedUrlStatus !in 200..299) {
                android.util.Log.w(TAG, "Signed-URL request failed: HTTP $signedUrlStatus")
                signedUrlConn.disconnect()
                if (signedUrlStatus == 401) DeviceTokenManager.invalidate(appContext)
                return@withContext Result.retry()
            }
            val signedUrl = JSONObject(signedUrlConn.inputStream.bufferedReader().readText()).getString("url")
            signedUrlConn.disconnect()
            android.util.Log.i(TAG, "Received signed download URL")

            // Probe file size and range support; fall back to single stream if unavailable.
            val fileSize = probeFileSize(signedUrl)
            if (fileSize > 0) {
                android.util.Log.i(TAG, "Parallel download: $NUM_CHUNKS chunks, ${fileSize / 1_000_000} MB total")
                downloadParallel(signedUrl, archiveDest, fileSize)
            } else {
                android.util.Log.i(TAG, "Falling back to single-stream download")
                downloadSingleStream(signedUrl, archiveDest)
            }
            android.util.Log.i(TAG, "Download complete: ${archiveDest.length() / 1_000_000} MB")

            // ── Phase 2: Extract ──────────────────────────────────────────────
            setProgress(workDataOf(KEY_PHASE to PHASE_EXTRACT, KEY_PROGRESS to -1))

            val extractedPath = archiveDest.inputStream().use { input ->
                GemmaClassifier.extractTarGz(appContext, input)
            }
            archiveDest.delete()

            if (extractedPath == null) {
                android.util.Log.e(TAG, "Extraction failed")
                TelemetryManager.get(appContext).track("gemma_download_failed")
                return@withContext Result.retry()
            }

            val durationMs = System.currentTimeMillis() - startMs
            android.util.Log.i(TAG, "Extraction complete: $extractedPath (${durationMs / 1000}s total)")
            TelemetryManager.get(appContext).track("gemma_download_success")
            TelemetryManager.get(appContext).track("gemma_download_duration_ms", durationMs.toDouble())
            TelemetryManager.get(appContext).flush()
            setProgress(workDataOf(KEY_PHASE to PHASE_DONE, KEY_PROGRESS to 100))
            notifyModelReady()
            Result.success()

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Download/extract failed: ${e.message}")
            TelemetryManager.get(appContext).track("gemma_download_failed")
            Result.retry()
        }
    }

    /**
     * Issues a HEAD request to determine file size and whether the server
     * supports byte-range requests. Returns -1 if either is unavailable.
     */
    private fun probeFileSize(url: String): Long {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod  = "HEAD"
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000
            conn.connect()
            val acceptsRanges = conn.getHeaderField("Accept-Ranges")?.lowercase() == "bytes"
            val size = conn.contentLengthLong
            conn.disconnect()
            if (acceptsRanges && size > 0) size else -1L
        } catch (e: Exception) {
            android.util.Log.w(TAG, "HEAD probe failed — will use single stream: ${e.message}")
            -1L
        }
    }

    /**
     * Downloads [fileSize] bytes from [url] in [NUM_CHUNKS] parallel Range requests.
     * Each chunk is written to a numbered temp file; on completion all chunks are
     * concatenated in order into [dest]. Temp files are always cleaned up.
     */
    private suspend fun downloadParallel(url: String, dest: File, fileSize: Long) {
        val chunkFiles = (0 until NUM_CHUNKS).map { i ->
            File(appContext.cacheDir, "gemma_chunk_$i.tmp")
        }
        val bytesDownloaded = AtomicLong(0L)

        try {
            val parallelStartMs = System.currentTimeMillis()
            coroutineScope {
                val chunkSize = fileSize / NUM_CHUNKS
                (0 until NUM_CHUNKS).map { i ->
                    val start = i * chunkSize
                    val end   = if (i == NUM_CHUNKS - 1) fileSize - 1 else start + chunkSize - 1
                    async(Dispatchers.IO) {
                        downloadChunk(url, chunkFiles[i], start, end, fileSize, bytesDownloaded)
                    }
                }.awaitAll()
            }
            val parallelMs = System.currentTimeMillis() - parallelStartMs
            val mbps = (fileSize / 1_000_000.0) / (parallelMs / 1000.0)
            android.util.Log.i(TAG, "All $NUM_CHUNKS chunks complete — ${parallelMs / 1000}s total, %.1f MB/s".format(mbps))

            // Concatenate chunks in order
            val concatStartMs = System.currentTimeMillis()
            FileOutputStream(dest).use { out ->
                chunkFiles.forEach { chunk -> chunk.inputStream().use { it.copyTo(out) } }
            }
            android.util.Log.i(TAG, "Chunk concat complete — ${System.currentTimeMillis() - concatStartMs}ms")
        } finally {
            chunkFiles.forEach { it.delete() }
        }
    }

    /**
     * Downloads bytes [start]..[end] from [url] into [dest].
     * Updates the shared [bytesDownloaded] counter and reports merged progress.
     */
    private suspend fun downloadChunk(
        url: String,
        dest: File,
        start: Long,
        end: Long,
        totalFileSize: Long,
        bytesDownloaded: AtomicLong
    ) {
        val chunkStartMs = System.currentTimeMillis()
        val chunkMb = (end - start + 1) / 1_000_000

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=$start-$end")
        conn.connectTimeout = 30_000
        conn.readTimeout    = 600_000
        conn.connect()

        val connectMs = System.currentTimeMillis() - chunkStartMs
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect()
            throw IOException("Chunk $start-$end: expected 206, got $code")
        }

        FileOutputStream(dest).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    val total = bytesDownloaded.addAndGet(n.toLong())
                    val pct   = (total * 100 / totalFileSize).toInt()
                    setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD, KEY_PROGRESS to pct))
                }
            }
        }
        conn.disconnect()

        val chunkMs = System.currentTimeMillis() - chunkStartMs
        val chunkMbps = chunkMb.toDouble() / (chunkMs / 1000.0)
        android.util.Log.i(TAG, "Chunk $start-$end: ${chunkMb}MB in ${chunkMs / 1000}s (connect ${connectMs}ms, %.1f MB/s)".format(chunkMbps))
    }

    /**
     * Single-stream fallback for servers that do not advertise Accept-Ranges: bytes.
     */
    private suspend fun downloadSingleStream(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 600_000
        conn.connect()

        val totalBytes = conn.contentLengthLong
        var downloadedBytes = 0L

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IOException("Single-stream download failed: HTTP ${conn.responseCode}")
        }

        FileOutputStream(dest).use { out ->
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
