package com.agenthita.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [RiskEvent::class, ContactNameEntry::class], version = 3, exportSchema = false)
abstract class HitaDatabase : RoomDatabase() {

    abstract fun riskEventDao(): RiskEventDao
    abstract fun contactNameDao(): ContactNameDao

    companion object {
        @Volatile private var instance: HitaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE risk_events ADD COLUMN gemmaAnalysis TEXT"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS contact_names (contactHash TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL)"
                )
            }
        }

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("HitaDatabase", "SQLCipher init failed — refusing to open unencrypted DB", e)
                throw IllegalStateException("Encrypted database unavailable. Cannot store risk events without encryption.", e)
            }
        }
    }
}
