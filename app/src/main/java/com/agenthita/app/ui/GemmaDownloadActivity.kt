package com.agenthita.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agenthita.app.R
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.databinding.ActivityGemmaDownloadBinding
import com.agenthita.app.model.GemmaClassifier
import com.agenthita.app.service.HitaAccessibilityService
import com.agenthita.app.telemetry.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class GemmaDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGemmaDownloadBinding

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGemmaDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        binding.btnDownload.setOnClickListener {
            TelemetryManager.get(this).track("gemma_download_tapped")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RemoteConfig.kaggleUrl)))
        }

        binding.btnSelectFile.setOnClickListener {
            filePicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnNotNow.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun importModel(uri: Uri) {
        val fileName = resolveFileName(uri) ?: ""
        val lower = fileName.lowercase()

        val isTarGz = lower.endsWith(".gz")
        val isDirect = lower.endsWith(".bin") || lower.endsWith(".task") || lower.endsWith(".tflite")

        if (!isTarGz && !isDirect) {
            showError("Unsupported file type. Select the .bin, .tflite, or .tar.gz file from your Downloads folder.")
            return
        }

        setImporting(true, indeterminate = isTarGz)

        lifecycleScope.launch {
            val extractedPath = withContext(Dispatchers.IO) {
                if (isTarGz) {
                    contentResolver.openInputStream(uri)?.use { GemmaClassifier.extractTarGz(this@GemmaDownloadActivity, it) }
                } else {
                    val destName = if (lower.endsWith(".tflite")) fileName.dropLast(7) + ".bin" else fileName
                    val dest = File(filesDir, destName)
                    if (copyUri(uri, dest)) dest.absolutePath else null
                }
            }

            if (extractedPath != null) {
                getSharedPreferences("hita_ai_prefs", MODE_PRIVATE)
                    .edit().putBoolean("gemma_loaded", true).apply()
                sendBroadcast(
                    Intent(HitaAccessibilityService.ACTION_MODEL_AVAILABLE).apply {
                        `package` = packageName
                    }
                )
                TelemetryManager.get(this@GemmaDownloadActivity).track("gemma_import_success")
                finish()
            } else {
                setImporting(false)
                showError("Import failed. Make sure the file is the correct Gemma TFLite model and you have enough storage space.")
            }
        }
    }

    private fun copyUri(uri: Uri, dest: File): Boolean {
        return try {
            val total = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var copied = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            runOnUiThread { binding.progressBar.progress = pct }
                        }
                    }
                }
            }
            dest.length() > 100_000_000L
        } catch (e: Exception) {
            android.util.Log.e("GemmaImport", "Copy failed: ${e.message}")
            false
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun setImporting(active: Boolean, indeterminate: Boolean = false) {
        binding.layoutProgress.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnSelectFile.isEnabled = !active
        binding.btnDownload.isEnabled = !active
        binding.btnNotNow.isEnabled = !active
        binding.progressBar.isIndeterminate = indeterminate
        binding.tvProgressLabel.text = if (indeterminate) "Extracting model…" else "Importing model…"
        if (active && !indeterminate) binding.progressBar.progress = 0
    }

    private fun showError(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Dialog_AgentHita_Transparent)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
