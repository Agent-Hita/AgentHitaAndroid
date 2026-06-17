package com.agenthita.app.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContactNameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ContactNameEntry)

    @Query("SELECT * FROM contact_names")
    suspend fun getAll(): List<ContactNameEntry>

    @Query("SELECT displayName FROM contact_names WHERE contactHash = :hash LIMIT 1")
    suspend fun getName(hash: String): String?
}
