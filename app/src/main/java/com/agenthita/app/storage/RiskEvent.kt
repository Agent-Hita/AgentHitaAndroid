package com.agenthita.app.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local-only record of a detected risk event.
 *
 * Privacy notes:
 * - contactHash is SHA-256 of the contact identifier — raw names/numbers are never stored.
 * - No message content is stored at any tier.
 * - appPackage identifies the source app but not the specific conversation thread.
 */
@Entity(tableName = "risk_events")
data class RiskEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val appPackage: String,
    val contactHash: String,      // SHA-256 of contact name/number
    val harmCategory: String,     // HarmCategory.name
    val riskLevel: String,        // RiskLevel.name
    val score: Float,
    val signals: String,          // Comma-separated signal type names
    val explanation: String,
    val gemmaAnalysis: String? = null,   // Gemma-generated analysis & recommendations (populated async)
    val guardianAlertSent: Boolean = false
)
