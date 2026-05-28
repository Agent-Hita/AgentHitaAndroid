package com.agenthita.poc.storage

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

    @Query("DELETE FROM risk_events WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
