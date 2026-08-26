package com.trucdecomptable.ollamachat.security

import android.content.Context
import android.util.Base64
import com.trucdecomptable.ollamachat.util.DiagnosticLog
import java.io.File
import java.security.SecureRandom

/**
 * The passphrase protecting the local database.
 *
 * A random 256-bit value, generated once, kept wrapped by an Android Keystore
 * key so it is never on disk in clear. The user never sees or types it: the
 * PIN guards the screen, this guards the file.
 */
object DatabaseKey {

    private const val PREFS = "db_key"
    private const val ENTRY = "passphrase"

    /** Returns the passphrase, creating it on first run. */
    fun get(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(ENTRY, null)

        if (stored != null) {
            val plain = SecretVault.decrypt(stored)
            if (plain.isNotEmpty()) return plain.toByteArray(Charsets.UTF_8)
            // The Keystore entry is gone (factory reset, restored device…).
            // Regenerating is the only way forward, but the old file is kept
            // aside rather than destroyed.
            DiagnosticLog.record("db/key", "clé illisible, base précédente mise de côté")
            quarantineDatabase(context)
        }

        val fresh = Base64.encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.NO_WRAP,
        )
        prefs.edit().putString(ENTRY, SecretVault.encrypt(fresh)).commit()
        return fresh.toByteArray(Charsets.UTF_8)
    }

    /** Renames the unreadable database instead of deleting it. */
    private fun quarantineDatabase(context: Context) {
        listOf("", "-wal", "-shm").forEach { suffix ->
            val file = File(context.getDatabasePath(DatabaseEncryption.ENCRYPTED_NAME).absolutePath + suffix)
            if (file.exists()) {
                runCatching { file.renameTo(File(file.absolutePath + ".unreadable")) }
            }
        }
    }
}
