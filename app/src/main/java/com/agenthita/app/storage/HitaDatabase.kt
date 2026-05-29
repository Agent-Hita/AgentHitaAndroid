package com.agenthita.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [RiskEvent::class], version = 2, exportSchema = false)
abstract class HitaDatabase : RoomDatabase() {

    abstract fun riskEventDao(): RiskEventDao

    companion object {
        @Volatile private var instance: HitaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE risk_events ADD COLUMN gemmaAnalysis TEXT"
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
                    .addMigrations(MIGRATION_1_2)
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("HitaDatabase", "SQLCipher init failed, falling back to unencrypted DB", e)
                Room.databaseBuilder(
                    context.applicationContext,
                    HitaDatabase::class.java,
                    "hita_events_fallback.db"
                ).addMigrations(MIGRATION_1_2).build()
            }
        }
    }
}
