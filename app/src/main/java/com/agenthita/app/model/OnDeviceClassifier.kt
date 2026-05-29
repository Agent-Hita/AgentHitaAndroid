package com.agenthita.app.model

import android.content.Context
import com.agenthita.app.config.RemoteConfig
import com.agenthita.app.detection.HarmCategory
import com.agenthita.app.detection.RiskLevel
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
 *
 * generateAnalysis() always returns a result: Gemma text if the model is loaded,
 * otherwise a rules-based explanation built from category + signals.
 */
class OnDeviceClassifier private constructor(private val gemma: GemmaClassifier?) {

    // Atomic reference allows safe hot-swap from background thread
    private val activeGemma = AtomicReference(gemma)

    val isLoaded: Boolean get() = activeGemma.get()?.isLoaded == true

    /** True when a model file was found but MediaPipe threw during initialisation. */
    val loadFailed: Boolean get() = activeGemma.get()?.loadFailed == true

    /** Replace the stub with a loaded Gemma instance. Thread-safe. */
    fun upgrade(loaded: OnDeviceClassifier) {
        activeGemma.set(loaded.activeGemma.get())
    }

    /**
     * Single multi-class inference: Gemma identifies the most likely harm category
     * and its severity in one call. Returns null when not loaded or message is safe.
     *
     * [ageHint] is an optional natural-language hint (e.g. "child under 13 years old")
     * that is injected into the prompt to bias Gemma toward age-relevant harm categories.
     */
    fun classify(text: String, ageHint: String? = null): Pair<HarmCategory, RiskLevel>? {
        val g = activeGemma.get() ?: return null
        if (!g.isLoaded) return null
        return g.classifyMessage(text, ageHint)
    }

    /**
     * Returns an analysis string for the alert detail view.
     * Uses Gemma when loaded; falls back to a rules-based explanation otherwise.
     * Never returns null.
     */
    fun generateAnalysis(
        lastMessage: String,
        context: List<String>,
        signals: List<String>,
        category: HarmCategory
    ): String {
        val g = activeGemma.get()
        if (g?.isLoaded == true) {
            val result = g.generateAnalysis(lastMessage, context, signals, category)
            if (!result.isNullOrBlank()) return result
        }
        return buildFallbackAnalysis(category, signals)
    }

    fun close() = activeGemma.get()?.close()

    companion object {
        /** Stub constructor — no blocking, no model loading. */
        operator fun invoke(): OnDeviceClassifier = OnDeviceClassifier(null)

        /** Full constructor — loads Gemma. Call from a background thread. */
        operator fun invoke(context: Context): OnDeviceClassifier =
            OnDeviceClassifier(GemmaClassifier(context))

        private fun buildFallbackAnalysis(category: HarmCategory, signals: List<String>): String {
            val description = when (category) {
                HarmCategory.SEXTORTION ->
                    "This conversation shows signs of sexual coercion or sextortion — someone may be pressuring for intimate images or threatening to share private content."
                HarmCategory.FINANCIAL_SCAM ->
                    "This conversation shows signs of a financial scam. Someone appears to be creating urgency around money, gifts, or transfers to pressure a quick decision."
                HarmCategory.GROOMING ->
                    "This conversation shows signs of predatory grooming — patterns of building inappropriate trust, testing boundaries, or requesting secrecy."
                HarmCategory.ROMANCE_SCAM ->
                    "This conversation shows signs of a romance scam. Someone who has likely never met in person may be building a false emotional connection to request money or personal information."
                HarmCategory.IDENTITY_PHISHING ->
                    "This conversation shows signs of identity phishing — someone is attempting to collect passwords, verification codes, or personal documents."
                HarmCategory.LURING ->
                    "This conversation shows signs of luring through a suspicious offer. Unexpected job, modelling, or money-making proposals that seem too good to be true are a common tactic."
                HarmCategory.HARASSMENT ->
                    "This conversation shows signs of harassment, threats, or coercive control behaviour directed at the recipient."
                HarmCategory.DISAPPEARING_MESSAGES ->
                    "The other person enabled disappearing messages in this conversation. This feature is often used to hide harmful or coercive behaviour and destroy evidence."
            }

            val sr = RemoteConfig.safetyResources
            val urls = when (category) {
                HarmCategory.SEXTORTION        -> sr.sextortion
                HarmCategory.FINANCIAL_SCAM    -> sr.financialScam
                HarmCategory.GROOMING          -> sr.grooming
                HarmCategory.ROMANCE_SCAM      -> sr.romanceScam
                HarmCategory.IDENTITY_PHISHING -> sr.identityPhishing
                HarmCategory.LURING            -> sr.luring
                HarmCategory.HARASSMENT             -> sr.harassment
                HarmCategory.DISAPPEARING_MESSAGES  -> sr.grooming
            }

            val tip1 = when (category) {
                HarmCategory.SEXTORTION ->
                    "Tip 1: Do not send any images or comply with demands. Block the sender and report the account immediately. The FBI advises victims never to pay — paying does not guarantee the content will be deleted.\n${urls.tip1Url}"
                HarmCategory.FINANCIAL_SCAM ->
                    "Tip 1: Never send money or gift cards to someone you have not met in person. The FTC says to stop contact immediately if you feel pressured.\n${urls.tip1Url}"
                HarmCategory.GROOMING ->
                    "Tip 1: Gently ask if anyone has been asking them to keep secrets or making them feel uncomfortable. Reassure them they are not in trouble. The NCMEC provides guidance for parents and carers.\n${urls.tip1Url}"
                HarmCategory.ROMANCE_SCAM ->
                    "Tip 1: Genuine relationships do not involve financial requests from people you have never met in person. Do not send money. The FBI documents this scam in detail.\n${urls.tip1Url}"
                HarmCategory.IDENTITY_PHISHING ->
                    "Tip 1: Never share passwords, one-time codes, or identity documents in a chat. Legitimate services will never ask for these. CISA explains how to recognise and report phishing.\n${urls.tip1Url}"
                HarmCategory.LURING ->
                    "Tip 1: Verify any offer through official channels before sharing personal information or agreeing to meet. CISA recommends confirming identities independently before responding.\n${urls.tip1Url}"
                HarmCategory.HARASSMENT ->
                    "Tip 1: Document the messages and report them to the platform and, if threats are involved, to local authorities. StopBullying.gov outlines how to report online harassment.\n${urls.tip1Url}"
                HarmCategory.DISAPPEARING_MESSAGES ->
                    "Tip 1: Take screenshots of the conversation immediately before messages disappear. This is your primary way to preserve evidence.\n${urls.tip1Url}"
            }

            val tip2 = when (category) {
                HarmCategory.SEXTORTION ->
                    "Tip 2: If content has already been shared, the Cyber Civil Rights Initiative offers a confidential crisis helpline and image-removal assistance.\n${urls.tip2Url}"
                HarmCategory.FINANCIAL_SCAM ->
                    "Tip 2: If any money was already sent, contact your bank immediately to attempt a reversal and report the fraud to the FTC at ReportFraud.ftc.gov.\n${urls.tip2Url}"
                HarmCategory.GROOMING ->
                    "Tip 2: Report online enticement or grooming to the NCMEC CyberTipline, which works directly with law enforcement.\n${urls.tip2Url}"
                HarmCategory.ROMANCE_SCAM ->
                    "Tip 2: Report romance scams to the FTC. If you sent money, also report to your bank and the payment platform used.\n${urls.tip2Url}"
                HarmCategory.IDENTITY_PHISHING ->
                    "Tip 2: If credentials were already shared, change passwords immediately and visit IdentityTheft.gov for a personalised recovery plan.\n${urls.tip2Url}"
                HarmCategory.LURING ->
                    "Tip 2: Report suspicious profiles or accounts to the platform and, if a physical meeting was arranged, contact local authorities and file a report with the FBI's IC3.\n${urls.tip2Url}"
                HarmCategory.HARASSMENT ->
                    "Tip 2: Block the sender and consider reaching out to a support service. CISA provides resources on reporting and recovering from online abuse.\n${urls.tip2Url}"
                HarmCategory.DISAPPEARING_MESSAGES ->
                    "Tip 2: If you suspect the person is a predator or the contact involves a minor, report to the NCMEC CyberTipline, which works directly with law enforcement.\n${urls.tip2Url}"
            }

            val signalSummary = if (signals.isNotEmpty()) {
                "\n\nDetected signals: ${signals.take(4).joinToString(", ")}."
            } else ""

            return "$description$signalSummary\n\n$tip1\n\n$tip2"
        }
    }
}
