package com.agenthita.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Database(entities = [RiskEvent::class, ContactNameEntry::class], version = 4, exportSchema = false)
abstract class HitaDatabase : RoomDatabase() {

    abstract fun riskEventDao(): RiskEventDao
    abstract fun contactNameDao(): ContactNameDao

    companion object {
        @Volatile private var instance: HitaDatabase? = null

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "hita_db_wrapping_key"
        private const val PREFS_NAME = "hita_db_prefs"
        private const val PREF_ENCRYPTED_KEY = "encrypted_db_key"
        private const val PREF_KEY_IV = "db_key_iv"
        private const val GCM_TAG_LENGTH = 128

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE risk_events ADD COLUMN gemmaAnalysis TEXT"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS contact_names (contactHash TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE risk_events ADD COLUMN feedbackState TEXT NOT NULL DEFAULT 'NONE'"
                )
            }
        }

        fun getInstance(context: Context): HitaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        // Returns or creates the AES-256-GCM wrapping key inside the Android Keystore.
        // This key never leaves the secure element and is unique per device/install.
        private fun getOrCreateWrappingKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setUserAuthenticationRequired(false)
                            .build()
                    )
                    generateKey()
                }
            }
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        // Returns the 32-byte database passphrase. On first run it generates one with
        // SecureRandom, encrypts it with the Keystore wrapping key (envelope encryption),
        // and persists the ciphertext in SharedPreferences. On subsequent runs it decrypts
        // the ciphertext to recover the passphrase. The raw key never touches disk.
        private fun getDatabasePassphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wrappingKey = getOrCreateWrappingKey()

            val encryptedKeyB64 = prefs.getString(PREF_ENCRYPTED_KEY, null)
            val ivB64 = prefs.getString(PREF_KEY_IV, null)

            if (encryptedKeyB64 != null && ivB64 != null) {
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val encryptedKey = Base64.decode(encryptedKeyB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                return cipher.doFinal(encryptedKey)
            }

            val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val encryptedKey = cipher.doFinal(rawKey)
            prefs.edit()
                .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                .putString(PREF_KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()

            return rawKey
        }

        private fun build(context: Context): HitaDatabase {
            return try {
                val passphrase = getDatabasePassphrase(context.applicationContext)
                val factory = SupportOpenHelperFactory(passphrase)
                passphrase.fill(0) // zero out our copy; SupportFactory has its own
                Room.databaseBuilder(
                    context.applicationContext,
                    HitaDatabase::class.java,
                    "hita_events.db"
                )
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("HitaDatabase", "SQLCipher init failed — refusing to open unencrypted DB", e)
                throw IllegalStateException("Encrypted database unavailable. Cannot store risk events without encryption.", e)
            }
        }
    }
}
