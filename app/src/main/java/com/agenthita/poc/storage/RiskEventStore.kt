package com.agenthita.poc.storage

import com.agenthita.poc.detection.DetectionResult
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
            timestampMs = timestampMs,
            appPackage = appPackage,
            contactHash = sha256(contactIdentifier),
            harmCategory = result.category.name,
            riskLevel = result.riskLevel.name,
            score = result.score,
            signals = result.signals.map { it.signal }.distinct().joinToString(","),
            explanation = result.explanation
        )
        return dao.insert(event)
    }

    suspend fun markAlertSent(eventId: Long) = dao.markAlertSent(eventId)

    /** Prune events older than maxAgeMs (default: 30 days). Call periodically. */
    suspend fun pruneOldEvents(maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        dao.deleteOlderThan(System.currentTimeMillis() - maxAgeMs)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
