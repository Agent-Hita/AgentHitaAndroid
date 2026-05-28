package com.agenthita.poc.model

import android.content.Context
import com.agenthita.poc.detection.HarmCategory
import com.google.mediapipe.tasks.genai.llminference.LlmInference

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

    init {
        tryLoad(context)
    }

    private fun tryLoad(context: Context) {
        val modelPath = findModelPath(context) ?: run {
            android.util.Log.i("GemmaClassifier", "No model file found — running in rules-only mode")
            return
        }
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(16)   // We only need a single word response
                .build()
            llm = LlmInference.createFromOptions(context, options)
            android.util.Log.i("GemmaClassifier", "Gemma loaded from $modelPath")
        } catch (e: Throwable) {
            android.util.Log.w("GemmaClassifier", "Failed to load Gemma (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    private fun findModelPath(context: Context): String? {
        val candidates = listOf(
            // Gemma 3 1B — recommended for low-RAM devices (Samsung Galaxy A16 etc.)
            "/sdcard/Download/gemma3-1b-it-cpu-int4.bin",
            "/sdcard/Download/gemma3-1b-it-gpu-int4.bin",
            // Gemma 2B — heavier, requires 6 GB+ RAM
            "/sdcard/Download/gemma-2b-it-gpu-int4.bin",
            "/sdcard/Download/gemma-2b-it-cpu-int4.bin",
            "/sdcard/Download/gemma2-cpu-int4.bin",
            // Generic fallback name
            "/sdcard/Download/gemma.bin",
            "${context.getExternalFilesDir(null)?.absolutePath}/gemma.bin"
        )
        return candidates.firstOrNull { java.io.File(it).exists() }
    }

    /**
     * Prompts Gemma to classify the message for the given harm category.
     * Returns a confidence value [0.0, 1.0] used as a score boost.
     */
    fun classify(text: String, category: HarmCategory): Float {
        val inference = llm ?: return 0f
        return try {
            val response = inference.generateResponse(buildPrompt(text, category))
                .trim().uppercase()
            android.util.Log.d("GemmaClassifier", "Response for ${category.name}: $response")
            parseResponse(response)
        } catch (e: Exception) {
            android.util.Log.w("GemmaClassifier", "Inference error: ${e.message}")
            0f
        }
    }

    private fun buildPrompt(text: String, category: HarmCategory): String {
        val desc = when (category) {
            HarmCategory.SEXTORTION        -> "sexual manipulation, image solicitation, or sextortion"
            HarmCategory.FINANCIAL_SCAM    -> "financial scams, payment coercion, urgency pressure, or fraud"
            HarmCategory.GROOMING          -> "predatory grooming, trust-building with minors, or boundary violations"
            HarmCategory.ROMANCE_SCAM      -> "fake romantic relationships designed to extract money or personal information"
            HarmCategory.IDENTITY_PHISHING -> "phishing for passwords, OTP codes, or personal identity information"
            HarmCategory.LURING            -> "fake job or modelling offers used to harvest information or lure victims"
            HarmCategory.HARASSMENT        -> "threats, stalking, doxxing, or coercive control behaviour"
        }
        return """You are a safety classifier detecting $desc.
Analyze this message and respond with exactly one word: HIGH, MEDIUM, LOW, or NONE.

Message: "$text"

Risk:""".trimIndent()
    }

    private fun parseResponse(response: String): Float = when {
        response.startsWith("HIGH")   -> 1.0f
        response.startsWith("MEDIUM") -> 0.6f
        response.startsWith("LOW")    -> 0.2f
        else                          -> 0f
    }

    fun close() {
        llm?.close()
        llm = null
    }
}
