package com.agenthita.app.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Maps a contact hash to its display name, stored only on this device.
 *
 * Privacy notes:
 * - displayName is stored on-device only — never transmitted to any server.
 * - Alerts send only the contactHash (SHA-256), never the plain name.
 * - Encrypted at rest via SQLCipher (same DB as risk_events).
 * - Populated by the accessibility service reading the conversation title on-screen.
 */
@Entity(tableName = "contact_names")
data class ContactNameEntry(
    @PrimaryKey val contactHash: String,
    val displayName: String
)
