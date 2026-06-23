package com.agenthita.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RiskEventDao {

    @Insert
    suspend fun insert(event: RiskEvent): Long

    @Query("SELECT * FROM risk_events ORDER BY timestampMs DESC LIMIT 100")
    fun getRecentEvents(): Flow<List<RiskEvent>>

    @Query("SELECT * FROM risk_events WHERE id = :id")
    suspend fun getById(id: Long): RiskEvent?

    @Query("SELECT * FROM risk_events WHERE contactHash = :hash ORDER BY timestampMs DESC")
    suspend fun getEventsForContact(hash: String): List<RiskEvent>

    @Query("UPDATE risk_events SET guardianAlertSent = 1 WHERE id = :id")
    suspend fun markAlertSent(id: Long)

    @Query("UPDATE risk_events SET gemmaAnalysis = :analysis WHERE id = :id")
    suspend fun updateGemmaAnalysis(id: Long, analysis: String)

    @Query("SELECT * FROM risk_events WHERE contactHash = :hash AND guardianAlertSent = 0 ORDER BY timestampMs ASC")
    suspend fun getUnsentEventsForContact(hash: String): List<RiskEvent>

    @Query("UPDATE risk_events SET guardianAlertSent = 1 WHERE contactHash = :hash AND guardianAlertSent = 0")
    suspend fun markAllUnsentAlertsSent(hash: String)

    @Query("UPDATE risk_events SET feedbackState = 'FALSE_POSITIVE' WHERE id = :id")
    suspend fun markFalsePositive(id: Long)

    @Query("DELETE FROM risk_events WHERE riskLevel = 'LOW' AND timestampMs < :cutoffMs")
    suspend fun deleteLowOlderThan(cutoffMs: Long)

    @Query("DELETE FROM risk_events WHERE riskLevel = 'MEDIUM' AND timestampMs < :cutoffMs")
    suspend fun deleteMediumOlderThan(cutoffMs: Long)

    @Query("DELETE FROM risk_events WHERE riskLevel = 'HIGH' AND timestampMs < :cutoffMs")
    suspend fun deleteHighOlderThan(cutoffMs: Long)
}
