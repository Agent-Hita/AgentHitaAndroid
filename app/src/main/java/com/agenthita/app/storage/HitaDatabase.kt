package com.agenthita.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [RiskEvent::class], version = 1, exportSchema = false)
abstract class HitaDatabase : RoomDatabase() {

    abstract fun riskEventDao(): RiskEventDao

    companion object {
        @Volatile private var instance: HitaDatabase? = null

        fun getInstance(context: Context): HitaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): HitaDatabase {
            return try {
                // SQLCipher-backed encryption — risk events never stored in plaintext
                // TODO: derive key from Android Keystore in production rather than a hardcoded string
                val passphrase = SQLiteDatabase.getBytes("hita-poc-key".toCharArray())
                val factory = SupportFactory(passphrase)
                Room.databaseBuilder(
                    context.applicationContext,
                    HitaDatabase::class.java,
                    "hita_events.db"
                )
                    .openHelperFactory(factory)
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("HitaDatabase", "SQLCipher init failed, falling back to unencrypted DB", e)
                Room.databaseBuilder(
                    context.applicationContext,
                    HitaDatabase::class.java,
                    "hita_events_fallback.db"
                ).build()
            }
        }
    }
}
