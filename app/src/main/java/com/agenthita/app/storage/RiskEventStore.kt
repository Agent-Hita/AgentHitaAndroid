package com.agenthita.app.storage

import com.agenthita.app.detection.DetectionResult
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

class RiskEventStore(private val dao: RiskEventDao) {

    fun getRecentEvents(): Flow<List<RiskEvent>> = dao.getRecentEvents()

    suspend fun getById(id: Long): RiskEvent? = dao.getById(id)

    suspend fun save(
        appPackage: String,
        contactIdentifier: String,
        result: DetectionResult,
        timestampMs: Long = System.currentTimeMillis()
    ): Long {
        val event = RiskEvent(
            timestampMs  = timestampMs,
            appPackage   = appPackage,
            contactHash  = sha256(contactIdentifier),
            harmCategory = result.category.name,
            riskLevel    = result.riskLevel.name,
            score        = result.score,
            signals      = result.signals.map { it.signal }.distinct().joinToString(","),
            explanation  = result.explanation
        )
        return dao.insert(event)
    }

    suspend fun markAlertSent(eventId: Long) = dao.markAlertSent(eventId)

    suspend fun markFalsePositive(eventId: Long) = dao.markFalsePositive(eventId)

    suspend fun updateGemmaAnalysis(eventId: Long, analysis: String) =
        dao.updateGemmaAnalysis(eventId, analysis)

    /** Prune events by tier: LOW after 30 days, MEDIUM after 60 days, HIGH after 180 days. */
    suspend fun pruneByTier(
        lowMaxDays:    Long = 30,
        mediumMaxDays: Long = 60,
        highMaxDays:   Long = 180
    ) {
        val now = System.currentTimeMillis()
        dao.deleteLowOlderThan(now    - lowMaxDays    * 86_400_000L)
        dao.deleteMediumOlderThan(now - mediumMaxDays * 86_400_000L)
        dao.deleteHighOlderThan(now   - highMaxDays   * 86_400_000L)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
