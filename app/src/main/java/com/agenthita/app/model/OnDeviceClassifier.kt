package com.agenthita.app.model

import android.content.Context
import com.agenthita.app.detection.HarmCategory
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified on-device classifier.
 *
 * Two construction modes:
 *   OnDeviceClassifier()              — stub mode, returns 0.0 immediately (no blocking)
 *   OnDeviceClassifier(context)       — attempts to load Gemma (call from background thread)
 *
 * After background loading completes, call upgrade() to hot-swap in the Gemma classifier
 * without restarting the service. Rules-based detection continues uninterrupted throughout.
 */
class OnDeviceClassifier private constructor(private val gemma: GemmaClassifier?) {

    // Atomic reference allows safe hot-swap from background thread
    private val activeGemma = AtomicReference(gemma)

    val isLoaded: Boolean get() = activeGemma.get()?.isLoaded == true

    /** Replace the stub with a loaded Gemma instance. Thread-safe. */
    fun upgrade(loaded: OnDeviceClassifier) {
        activeGemma.set(loaded.activeGemma.get())
    }

    fun getBoost(text: String, category: HarmCategory): Float {
        val g = activeGemma.get() ?: return 0f
        if (!g.isLoaded) return 0f
        return g.classify(text, category)
    }

    fun close() = activeGemma.get()?.close()

    companion object {
        /** Stub constructor — no blocking, no model loading. */
        operator fun invoke(): OnDeviceClassifier = OnDeviceClassifier(null)

        /** Full constructor — loads Gemma. Call from a background thread. */
        operator fun invoke(context: Context): OnDeviceClassifier =
            OnDeviceClassifier(GemmaClassifier(context))
    }
}
