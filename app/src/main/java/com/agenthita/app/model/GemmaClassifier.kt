package com.agenthita.app.model

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.detection.HarmCategory
import com.agenthita.app.detection.RiskLevel
import com.agenthita.app.telemetry.TelemetryManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * On-device Gemma classifier using MediaPipe LLM Inference API.
 *
 * Recommended model: Gemma 3 1B IT CPU INT4 (~600 MB, works on 4 GB RAM devices)
 *
 * Model setup:
 *   1. Download from Kaggle (select "TFLite" framework, "gemma3-1b-it-cpu-int4" variation):
 *      https://www.kaggle.com/models/google/gemma3/frameworks/tfLite
 *   2. Push to the device:
 *      adb push gemma3-1b-it-cpu-int4.bin /sdcard/Download/
 *   3. The classifier will auto-detect it and activate.
 *
 * Also accepts the heavier Gemma 2B models if already downloaded.
 *
 * Falls back to returning 0.0 (no boost) when the model is not present.
 * Rule-based detectors run fully in fallback mode — Gemma is additive.
 */
class GemmaClassifier(context: Context) {

    private var llm: LlmInference? = null
    val isLoaded: Boolean get() = llm != null

    /**
     * True when a model file was found on-device but MediaPipe failed to
     * initialise it (e.g. incompatible model format, insufficient RAM, corrupt file).
     * Stays false when the model was simply not installed — that is expected, not an error.
     */
    var loadFailed: Boolean = false
        private set

    // Single-entry cache — avoids re-running inference when the test pipeline calls
    // classifier.classify(text) immediately after scorer.score(text) with the same inputs.
    private var cacheKey: Triple<String, List<String>, String?> = Triple("", emptyList(), null)
    private var cacheVal: Pair<HarmCategory, RiskLevel>? = null

    init {
        tryLoad(context)
    }

    private fun tryLoad(context: Context) {
        // Remove any previously staged GPU models — they can't run on the CPU backend
        context.filesDir.listFiles()
            ?.filter { it.name.lowercase().contains("gpu") && (it.name.endsWith(".bin") || it.name.endsWith(".task")) }
            ?.forEach { f ->
                if (f.delete()) android.util.Log.i("GemmaClassifier", "Removed incompatible GPU model: ${f.name}")
            }

        // 1. Try direct file access first (fast path)
        val modelPath = findModelPath(context)
            // 2. Fall back to copying model from MediaStore Downloads into internal storage.
            //    This handles Android 13+ scoped storage where the app's UID may differ from
            //    the POSIX owner of a previously-pushed model file in /sdcard/Download/.
            //    MediaStore tracks ownership by package name (not UID), so this survives reinstalls.
            ?: copyModelFromMediaStore(context)
            ?: run {
                android.util.Log.i("GemmaClassifier", "No model file found — running in rules-only mode")
                return
            }
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(RemoteConfig.gemmaMaxTokens)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            llm = LlmInference.createFromOptions(context, options)
            android.util.Log.i("GemmaClassifier", "Gemma loaded from $modelPath")
        } catch (e: Throwable) {
            loadFailed = true
            android.util.Log.w("GemmaClassifier", "Failed to load Gemma from $modelPath (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    /**
     * Queries MediaStore.Downloads for the model file by name and copies it to
     * [Context.filesDir] (internal storage, always readable by the app).
     *
     * MediaStore tracks Download ownership by package name rather than UID, so this
     * works even after an app uninstall/reinstall that changes the process UID.
     *
     * Returns the path of the staged copy, or null if the file is not in MediaStore.
     */
    private fun copyModelFromMediaStore(context: Context): String? {
        // Skip copy if already staged from a previous run
        context.filesDir.listFiles()?.forEach { f ->
            if ((f.name.endsWith(".bin") || f.name.endsWith(".task")) && f.length() > 100_000_000L) {
                android.util.Log.i("GemmaClassifier", "Using pre-staged model: ${f.absolutePath}")
                return f.absolutePath
            }
        }

        // Query all large files in Downloads and match by extension — avoids hardcoded filenames
        // since Kaggle and browsers may download with arbitrary names (archive.tar.gz, etc.)
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val cursor = runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.SIZE} > ?",
                arrayOf("100000000"), null
            )
        }.getOrNull() ?: return null

        data class Entry(val id: Long, val name: String, val size: Long)
        val entries = cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getLong(0), c.getString(1) ?: continue, c.getLong(2)))
                }
            }
        }

        // Process .bin/.task first (no extraction needed), then .tar.gz
        val sorted = entries.sortedBy { if (it.name.lowercase().endsWith(".gz")) 1 else 0 }

        for (entry in sorted) {
            val lower = entry.name.lowercase()
            // .gz catches .tar.gz and Kaggle split names like .tar-1.gz
            val isTarGz = lower.endsWith(".gz")
            val isBinary = lower.endsWith(".bin") || lower.endsWith(".task")
            if (!isTarGz && !isBinary) continue
            // GPU-quantized models require OpenCL — skip them since we run CPU-only
            if (isBinary && lower.contains("gpu")) {
                android.util.Log.i("GemmaClassifier", "Skipping GPU model: ${entry.name}")
                continue
            }

            val fileUri = ContentUris.withAppendedId(collection, entry.id)

            if (isTarGz) {
                android.util.Log.i("GemmaClassifier", "Extracting ${entry.name} from MediaStore …")
                val extracted = runCatching {
                    context.contentResolver.openInputStream(fileUri)!!.use { extractTarGz(context, it) }
                }.getOrNull()
                if (extracted != null) return extracted
            } else {
                val dest = File(context.filesDir, entry.name)
                android.util.Log.i("GemmaClassifier", "Copying ${entry.name} from MediaStore → ${dest.absolutePath} …")
                val copied = runCatching {
                    context.contentResolver.openInputStream(fileUri)!!.use { input ->
                        FileOutputStream(dest).use { out -> input.copyTo(out, bufferSize = 8 * 1024 * 1024) }
                    }
                }.isSuccess
                if (copied && dest.length() > 100_000_000L) {
                    android.util.Log.i("GemmaClassifier", "Copy complete: ${dest.length() / 1_000_000} MB")
                    // Remove from Downloads — model is now in private app storage where
                    // the user can't accidentally delete it. Best-effort: if the entry is
                    // not owned by this app (e.g. pushed via adb), MediaStore delete will
                    // throw RecoverableSecurityException — we log and continue.
                    runCatching { context.contentResolver.delete(fileUri, null, null) }
                        .onSuccess { android.util.Log.i("GemmaClassifier", "Downloads copy removed — model in app storage") }
                        .onFailure { android.util.Log.w("GemmaClassifier", "Could not remove Downloads copy: ${it.message}") }
                    return dest.absolutePath
                } else {
                    dest.delete()
                }
            }
        }
        return null
    }

    /**
     * Verifies the extracted model file against the SHA-256 allow-list in RemoteConfig.
     * Returns true when the list is empty (check not yet configured) or when the file's
     * digest matches an entry. Returns false — and the caller must delete the file — when
     * hashes are configured and none match, indicating a potentially malicious file.
     */
    private fun verifyModelFile(context: Context, file: File): Boolean =
        Companion.verifyModelFile(context, file)

    private fun extractTarGz(context: Context, inputStream: java.io.InputStream): String? =
        Companion.extractTarGz(context, inputStream)

    companion object {
        /**
         * Shared entry point used by both the service (auto-detection) and
         * GemmaDownloadActivity (user-selected file import).
         */
        internal fun extractTarGz(context: Context, inputStream: java.io.InputStream): String? {
            return extractTarGzImpl(context, inputStream)
        }

        private fun verifyModelFile(context: Context, file: File): Boolean {
        val allowedHashes = RemoteConfig.gemmaModelHashes
        if (allowedHashes.isEmpty()) return true  // No hashes configured yet — skip check

        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8 * 1024 * 1024)
            file.inputStream().use { input ->
                var n: Int
                while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            android.util.Log.i("GemmaClassifier", "Model SHA-256: $hex")
            (hex in allowedHashes).also { match ->
                if (!match) android.util.Log.w("GemmaClassifier", "SHA-256 not in allow-list")
            }
        } catch (e: Exception) {
            android.util.Log.w("GemmaClassifier", "Hash verification error: ${e.message}")
            false  // Fail closed — can't verify, reject
        }
    }

        private fun extractTarGzImpl(context: Context, inputStream: java.io.InputStream): String? {
        val headerBuf = ByteArray(512)
        val ioBuf = ByteArray(8 * 1024 * 1024)

        fun readFully(stream: java.io.InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
            var pos = 0
            while (pos < len) {
                val n = stream.read(buf, pos, len - pos)
                if (n < 0) return false
                pos += n
            }
            return true
        }

        fun skipFully(stream: java.io.InputStream, bytes: Long): Boolean {
            var rem = bytes
            while (rem > 0) {
                val n = stream.read(ioBuf, 0, minOf(rem, ioBuf.size.toLong()).toInt())
                if (n < 0) return false
                rem -= n
            }
            return true
        }

        try {
            GZIPInputStream(inputStream, 8 * 1024 * 1024).use { gz ->
                while (true) {
                    if (!readFully(gz, headerBuf)) return null
                    // Two consecutive zero blocks = end of archive
                    if (headerBuf.all { it == 0.toByte() }) return null

                    val entryName  = String(headerBuf, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                    val sizeOctal  = String(headerBuf, 124, 12, Charsets.UTF_8).trimEnd('\u0000').trim()
                    val typeFlag   = headerBuf[156].toInt().toChar()
                    val fileSize   = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)
                    val paddedSize = ((fileSize + 511) / 512) * 512

                    val baseName   = entryName.substringAfterLast('/')
                    val isRegular  = typeFlag == '0' || typeFlag == '\u0000'
                    // 500 MB floor rejects garbage; configurable ceiling prevents storage exhaustion
                    // from a crafted TAR header declaring a huge file size (real models are ~1-4 GB).
                    val isSane     = fileSize in 500_000_000L..RemoteConfig.gemmaMaxFileSizeBytes
                    val isModel    = isRegular && isSane &&
                        (baseName.endsWith(".bin") || baseName.endsWith(".task")) &&
                        !baseName.lowercase().contains("gpu")

                    if (isModel) {
                        val dest = File(context.filesDir, baseName)
                        android.util.Log.i("GemmaClassifier", "Extracting $entryName → ${dest.absolutePath}")
                        val ok = runCatching {
                            FileOutputStream(dest).use { out ->
                                var rem = fileSize
                                while (rem > 0) {
                                    val n = gz.read(ioBuf, 0, minOf(rem, ioBuf.size.toLong()).toInt())
                                    if (n < 0) error("Unexpected EOF during extraction")
                                    out.write(ioBuf, 0, n)
                                    rem -= n
                                }
                            }
                            val pad = paddedSize - fileSize
                            if (pad > 0) skipFully(gz, pad)
                        }.isSuccess
                        if (ok && dest.length() == fileSize) {
                            if (!verifyModelFile(context, dest)) {
                                android.util.Log.w("GemmaClassifier", "Hash mismatch — rejecting $baseName")
                                TelemetryManager.get(context).track("gemma_hash_mismatch")
                                dest.delete()
                                return null
                            }
                            android.util.Log.i("GemmaClassifier", "Extraction complete: ${dest.length() / 1_000_000} MB")
                            return dest.absolutePath
                        }
                        dest.delete()
                        return null
                    }

                    if (!skipFully(gz, paddedSize)) return null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GemmaClassifier", "TAR extraction error: ${e.message}")
        }
        return null
        }
    }  // end companion object

    private fun findModelPath(context: Context): String? {
        // Fast path: model already in private app storage from a previous run.
        // Scan by extension + size so any model filename is accepted (not just gemma.bin).
        context.filesDir.listFiles()?.firstOrNull { f ->
            (f.name.endsWith(".bin") || f.name.endsWith(".task")) &&
            !f.name.lowercase().contains("gpu") &&
            f.length() > 100_000_000L &&
            try { java.io.FileInputStream(f).use { true } } catch (_: Exception) { false }
        }?.let {
            android.util.Log.i("GemmaClassifier", "Model found: ${it.absolutePath}")
            return it.absolutePath
        }

        val appFiles = context.getExternalFilesDir(null)?.absolutePath
        val candidates = listOf(
            // App-private external storage — readable when external storage is mounted
            "$appFiles/gemma.task",
            "$appFiles/gemma.bin",
            // Downloads — CPU variants only; GPU models require OpenCL which we don't use
            "/sdcard/Download/gemma3-1b-it-cpu-int4.task",
            "/storage/emulated/0/Download/gemma3-1b-it-cpu-int4.task",
            "/sdcard/Download/gemma3-1b-it-cpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-cpu-int4.bin",
            "/sdcard/Download/gemma2-cpu-int4.bin",
            "/sdcard/Download/gemma.task",
            "/storage/emulated/0/Download/gemma.task",
            "/sdcard/Download/gemma.bin"
        )
        // Use FileInputStream to probe actual read access — canRead() can return false on
        // Android 13+ for files in Downloads even when POSIX permissions allow access,
        // due to how scoped storage interacts with the security model.
        val binary = candidates.firstOrNull { path ->
            try { java.io.FileInputStream(path).use { true } } catch (_: Exception) { false }
        }
        if (binary != null) {
            android.util.Log.i("GemmaClassifier", "Model found: $binary")
            return binary
        }

        // Try extracting from any .gz archive in Downloads (Kaggle uses names like
        // gemma3-keras-gemma3_instruct_1b-v3.tar-1.gz — match by extension, not filename)
        for (dir in listOf("/sdcard/Download", "/storage/emulated/0/Download")) {
            val files = runCatching { java.io.File(dir).listFiles() }.getOrNull() ?: continue
            for (f in files.sortedBy { it.name }) {
                if (!f.name.lowercase().endsWith(".gz")) continue
                val extracted = try {
                    java.io.FileInputStream(f).use { extractTarGz(context, it) }
                } catch (_: Exception) { null }
                if (extracted != null) {
                    android.util.Log.i("GemmaClassifier", "Model extracted from archive: ${f.absolutePath}")
                    // Delete the archive from Downloads — model is now in private app storage
                    if (f.delete()) {
                        android.util.Log.i("GemmaClassifier", "Archive removed from Downloads")
                    } else {
                        android.util.Log.w("GemmaClassifier", "Could not remove archive from Downloads (scoped storage)")
                    }
                    return extracted
                }
            }
        }

        android.util.Log.i("GemmaClassifier", "No model found in any candidate path")
        return null
    }

    /**
     * Single multi-class inference: Gemma identifies the most likely harm category
     * and severity in one LLM call. This replaces the old per-category getBoost()
     * approach so that Gemma can detect harm categories that rules miss entirely.
     *
     * Returns null if the model is not loaded, the message is safe, or the
     * response cannot be parsed.
     */
    fun classifyMessage(text: String, context: List<String> = emptyList(), ageHint: String? = null): Pair<HarmCategory, RiskLevel>? {
        val inference = llm ?: return null
        val key = Triple(text, context, ageHint)
        if (key == cacheKey) {
            android.util.Log.d("GemmaClassifier", "Cache hit — skipping inference")
            return cacheVal
        }
        return try {
            val prompt = buildMultiClassPrompt(text, context, ageHint)
            val t0 = System.currentTimeMillis()
            val response = inference.generateResponse(prompt).trim().uppercase()
            val ms = System.currentTimeMillis() - t0
            android.util.Log.i("GemmaClassifier", "Inference took ${ms}ms | response: \"$response\"")
            parseMultiClassResponse(response).also {
                cacheKey = key
                cacheVal = it
            }
        } catch (e: Exception) {
            android.util.Log.w("GemmaClassifier", "Multi-class inference error: ${e.message}")
            null
        }
    }

    private fun buildMultiClassPrompt(text: String, context: List<String> = emptyList(), ageHint: String? = null): String {
        // Budget: 512 tokens total. Reserve ~80 for prompt overhead + ~150 for response.
        // Split remaining ~280 between context (up to 3 prior msgs × 60 chars) and latest message.
        val truncated = text.take(RemoteConfig.gemmaInputTruncationChars)
        val recipientLine = if (ageHint != null) "\nRecipient: $ageHint" else ""
        val contextBlock = if (context.isEmpty()) "" else {
            val prior = context.takeLast(3).joinToString("\n") { "- \"${it.take(60)}\"" }
            "\nPrior messages in conversation:\n$prior"
        }
        return """Identify the harm in this message. Fill in the blanks below.

Valid harm types: SEXTORTION FINANCIAL_SCAM GROOMING ROMANCE_SCAM IDENTITY_PHISHING LURING HARASSMENT NONE
Valid severity levels: HIGH MEDIUM LOW NONE
$recipientLine$contextBlock
Latest message: "$truncated"

Harm type:
Severity: """.trimIndent()
    }

    /**
     * Token-scanning parser — does NOT rely on Gemma 1B following a strict format.
     *
     * Gemma 1B produces inconsistent responses:
     *   "SEXTORTION HIGH"           → ideal
     *   "IDENTITY_PHISHING"         → category only (default to MEDIUM)
     *   "Harm type: GROOMING\nSeverity: HIGH" → fill-in format (handled)
     *   "CATEGORY SEVERITY: HIGH"   → echoed template (no valid category)
     *   "SCAMMING"                  → invented word (no match)
     *
     * Strategy: strip non-alphanumeric chars, tokenise, scan every token for a known
     * category name and a known severity name. Require a category; default severity
     * to MEDIUM if Gemma identified a category but omitted the level.
     */
    private fun parseMultiClassResponse(response: String): Pair<HarmCategory, RiskLevel>? {
        // Strip colons, slashes, punctuation — keep letters, digits, underscores
        val normalized = response.replace(Regex("[^A-Z0-9_\\s]"), " ")
        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }

        val category = tokens.firstNotNullOfOrNull { token ->
            when (token) {
                "SEXTORTION"        -> HarmCategory.SEXTORTION
                "FINANCIAL_SCAM"    -> HarmCategory.FINANCIAL_SCAM
                "GROOMING"          -> HarmCategory.GROOMING
                "ROMANCE_SCAM", "ROMANCE" -> HarmCategory.ROMANCE_SCAM
                "IDENTITY_PHISHING", "PHISHING" -> HarmCategory.IDENTITY_PHISHING
                "LURING"            -> HarmCategory.LURING
                "HARASSMENT"        -> HarmCategory.HARASSMENT
                else                -> null
            }
        } ?: return null  // No recognised category → safe / unparseable

        val severity = tokens.firstNotNullOfOrNull { token ->
            when (token) {
                "HIGH"   -> RiskLevel.HIGH
                "MEDIUM" -> RiskLevel.MEDIUM
                "LOW"    -> RiskLevel.LOW
                else     -> null
            }
        } ?: RiskLevel.MEDIUM  // Category found but no severity word → default MEDIUM

        return Pair(category, severity)
    }

    /**
     * Generates a short human-readable analysis and safety recommendations for the alert detail view.
     * Returns null if Gemma is not loaded or inference fails.
     */
    fun generateAnalysis(
        lastMessage: String,
        context: List<String>,
        signals: List<String>,
        category: HarmCategory
    ): String? {
        val inference = llm ?: return null
        return try {
            val prompt = buildAnalysisPrompt(lastMessage, context, signals, category)
            val response = inference.generateResponse(prompt).trim()
            android.util.Log.d("GemmaClassifier", "Analysis generated (${response.length} chars)")
            response.ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.w("GemmaClassifier", "Analysis inference error: ${e.message}")
            null
        }
    }

    private fun buildAnalysisPrompt(
        lastMessage: String,
        context: List<String>,
        signals: List<String>,
        category: HarmCategory
    ): String {
        val categoryLabel = when (category) {
            HarmCategory.SEXTORTION        -> "sexual manipulation / sextortion"
            HarmCategory.FINANCIAL_SCAM    -> "financial scam"
            HarmCategory.GROOMING          -> "predatory grooming"
            HarmCategory.ROMANCE_SCAM      -> "romance scam"
            HarmCategory.IDENTITY_PHISHING -> "identity phishing"
            HarmCategory.LURING            -> "luring via fake offer"
            HarmCategory.HARASSMENT             -> "harassment or threats"
            HarmCategory.DISAPPEARING_MESSAGES  -> "disappearing messages / secrecy signal"
        }
        val signalList = signals.take(5).joinToString(", ").ifEmpty { "none" }

        // Budget ~280 tokens for conversation text (512 total - ~80 prompt overhead - ~150 response)
        val ctxMessages = RemoteConfig.gemmaContextMessages
        val ctxMsgLen   = RemoteConfig.gemmaContextMessageLength
        val conversationBlock = if (context.isEmpty()) {
            "Message: \"$lastMessage\""
        } else {
            val trimmedContext = context.takeLast(ctxMessages).joinToString("\n") { "- \"${it.take(ctxMsgLen)}\"" }
            "Previous messages:\n$trimmedContext\n\nLatest message: \"${lastMessage.take(ctxMsgLen)}\""
        }

        return """You are a safety advisor helping someone who received a concerning message.
Category: $categoryLabel
Signals: $signalList
$conversationBlock

In 2-3 sentences, explain why this conversation is concerning. Then give exactly 2 safety tips starting with "Tip 1:" and "Tip 2:". Be clear and supportive.""".trimIndent()
    }

    fun close() {
        llm?.close()
        llm = null
    }
}
