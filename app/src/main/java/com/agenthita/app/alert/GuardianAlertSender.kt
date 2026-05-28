package com.agenthita.app.alert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agenthita.app.consent.ConsentManager
import com.agenthita.app.detection.DetectionResult
import com.agenthita.app.detection.HarmCategory

/**
 * Dispatches Tier 1 guardian alerts via a WorkManager background job.
 *
 * Tier 1 disclosure (consent.html): category + severity + approximate time + recommended action.
 * Explicitly excluded: message content, contact name/number, conversation history,
 * screenshots, precise timestamps.
 */
class GuardianAlertSender(
    private val context: Context,
    private val consentManager: ConsentManager
) {
    fun sendIfConfigured(result: DetectionResult, eventId: Long) {
        val guardianEmail = consentManager.guardianEmail ?: return
        if (!consentManager.isGuardianAlertsEnabled) return

        val data = Data.Builder()
            .putString(KEY_GUARDIAN_EMAIL, guardianEmail)
            .putString(KEY_CATEGORY, result.category.name)
            .putString(KEY_RISK_LEVEL, result.riskLevel.name)
            .putLong(KEY_EVENT_ID, eventId)
            .build()

        val request = OneTimeWorkRequestBuilder<GuardianAlertWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val KEY_GUARDIAN_EMAIL = "guardian_email"
        const val KEY_CATEGORY       = "category"
        const val KEY_RISK_LEVEL     = "risk_level"
        const val KEY_EVENT_ID       = "event_id"
    }
}

class GuardianAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardianEmail = inputData.getString(GuardianAlertSender.KEY_GUARDIAN_EMAIL)
            ?: return Result.failure()
        val categoryName = inputData.getString(GuardianAlertSender.KEY_CATEGORY)
            ?: return Result.failure()
        val riskLevel = inputData.getString(GuardianAlertSender.KEY_RISK_LEVEL) ?: "HIGH"
        val category = HarmCategory.valueOf(categoryName)

        return try {
            sendEmail(
                to = guardianEmail,
                subject = "Agent Hita — Safety Alert",
                body = buildAlertBody(category, riskLevel)
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun buildAlertBody(category: HarmCategory, riskLevel: String): String {
        val categoryLabel = when (category) {
            HarmCategory.SEXTORTION        -> "Sexual manipulation / potential sextortion"
            HarmCategory.FINANCIAL_SCAM    -> "Financial scam / coercion pressure"
            HarmCategory.GROOMING          -> "Predatory grooming behaviour"
            HarmCategory.ROMANCE_SCAM      -> "Romance scam / fake relationship"
            HarmCategory.IDENTITY_PHISHING -> "Identity phishing / credential theft"
            HarmCategory.LURING            -> "Luring / fake job or modelling offer"
            HarmCategory.HARASSMENT        -> "Harassment / threats / stalking"
        }
        val recommendedAction = when (category) {
            HarmCategory.SEXTORTION ->
                "Have a calm, non-accusatory conversation focusing on their safety, not punishment. " +
                "Resources: cyber civil rights initiative (cybercivilrights.org), NCMEC (missingkids.org)."
            HarmCategory.FINANCIAL_SCAM ->
                "Check in with them about any financial requests they may have received. " +
                "Remind them it's always okay to say no and check with trusted people first."
            HarmCategory.GROOMING ->
                "Gently ask if anyone has been making them feel uncomfortable or asking them to keep secrets. " +
                "Reassure them they are not in trouble and that you are there to help."
            HarmCategory.ROMANCE_SCAM ->
                "Ask if anyone online has asked them for money or personal information. " +
                "Remind them that genuine relationships do not involve financial requests from people they have never met in person."
            HarmCategory.IDENTITY_PHISHING ->
                "Check whether they have shared any passwords, verification codes, or personal details recently. " +
                "Help them change passwords and contact their bank if any financial details were shared."
            HarmCategory.LURING ->
                "Ask if they have received any unexpected job, modelling, or travel offers. " +
                "Remind them to verify any opportunity through official channels before sharing personal information or travelling anywhere."
            HarmCategory.HARASSMENT ->
                "Check in on their immediate safety and wellbeing. " +
                "If they are receiving threats, encourage them to report it to local authorities and document the messages."
        }

        // Approximate time only — no precise timestamp (Tier 1 disclosure spec)
        return """
Agent Hita Safety Alert

Category: $categoryLabel
Severity: $riskLevel
When: Today

What this means:
Agent Hita detected a conversation pattern consistent with $categoryLabel.

Recommended action:
$recommendedAction

---
No message content is included in this alert.
The person being protected has also received a private notification on their device.

This alert was sent by Agent Hita. To manage alert settings, open the app.
        """.trimIndent()
    }

    private fun sendEmail(to: String, subject: String, body: String) {
        // TODO: configure SMTP credentials — store via ConsentManager / Android Keystore
        // Recommended: use a dedicated transactional email service (e.g. SendGrid, Mailgun)
        // rather than personal SMTP credentials in the app.
        //
        // val props = Properties().apply {
        //     put("mail.smtp.auth", "true")
        //     put("mail.smtp.starttls.enable", "true")
        //     put("mail.smtp.host", BuildConfig.SMTP_HOST)
        //     put("mail.smtp.port", "587")
        // }
        // val session = Session.getInstance(props, object : Authenticator() {
        //     override fun getPasswordAuthentication() =
        //         PasswordAuthentication(BuildConfig.SMTP_USER, BuildConfig.SMTP_PASS)
        // })
        // val message = MimeMessage(session).apply {
        //     setFrom(InternetAddress("alerts@agenthita.com"))
        //     setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        //     setSubject(subject)
        //     setText(body)
        // }
        // Transport.send(message)
    }
}
