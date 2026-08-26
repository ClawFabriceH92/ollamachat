package com.trucdecomptable.ollamachat.security

import android.content.Context
import com.trucdecomptable.ollamachat.util.DiagnosticLog
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Moves an existing plaintext database into an encrypted one.
 *
 * The plaintext file is only deleted once the encrypted copy has been reopened
 * and its row counts checked: a migration that loses the user's conversations
 * would be far worse than one that never ran.
 */
object DatabaseEncryption {

    const val PLAIN_NAME = "ollamachat.db"
    const val ENCRYPTED_NAME = "ollamachat-enc.db"

    /** Counts used to prove the copy is faithful before anything is removed. */
    private val CHECKED_TABLES = listOf("conversations", "messages", "memories")

    fun encryptedPath(context: Context): File = context.getDatabasePath(ENCRYPTED_NAME)

    /**
     * Converts the plaintext database if there is one and the encrypted file
     * does not exist yet. Returns true when a migration actually happened.
     */
    fun ensureEncrypted(
        context: Context,
        passphrase: ByteArray,
        plainName: String = PLAIN_NAME,
        encryptedName: String = ENCRYPTED_NAME,
    ): Boolean {
        val plain = context.getDatabasePath(plainName)
        val encrypted = context.getDatabasePath(encryptedName)
        if (encrypted.exists() || !plain.exists()) return false

        encrypted.parentFile?.mkdirs()
        val temp = File(encrypted.absolutePath + ".tmp")
        deleteDatabaseFiles(temp)

        return try {
            val expected = exportToEncrypted(plain, temp, String(passphrase, Charsets.UTF_8))
            verify(temp, passphrase, expected)
            if (!temp.renameTo(encrypted)) error("renommage impossible")
            deleteDatabaseFiles(plain)
            DiagnosticLog.record("db/encrypt", "base chiffrée (${expected.values.sum()} lignes)")
            true
        } catch (e: Throwable) {
            // Leave everything exactly as it was; the app opens the plaintext
            // database on the next line and the user loses nothing.
            deleteDatabaseFiles(temp)
            DiagnosticLog.record("db/encrypt", e)
            false
        }
    }

    /** Copies the plaintext database into [target] under [passphrase]. */
    private fun exportToEncrypted(plain: File, target: File, passphrase: String): Map<String, Int> {
        val source = SQLiteDatabase.openOrCreateDatabase(plain.absolutePath, "", null, null)
        try {
            val counts = CHECKED_TABLES.associateWith { table -> countRows(source, table) }
            val version = source.version
            val escapedPath = target.absolutePath.replace("'", "''")
            val escapedKey = passphrase.replace("'", "''")
            source.rawExecSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedKey'")
            source.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            // sqlcipher_export copies schema and rows, not the user version.
            source.rawExecSQL("PRAGMA encrypted.user_version = $version")
            source.rawExecSQL("DETACH DATABASE encrypted")
            return counts
        } finally {
            runCatching { source.close() }
        }
    }

    /** Reopens the copy and refuses to go further unless every count matches. */
    private fun verify(target: File, passphrase: ByteArray, expected: Map<String, Int>) {
        val copy = SQLiteDatabase.openOrCreateDatabase(target, passphrase, null, null)
        try {
            expected.forEach { (table, count) ->
                val actual = countRows(copy, table)
                if (actual != count) error("$table : $actual lignes copiées sur $count")
            }
        } finally {
            runCatching { copy.close() }
        }
    }

    private fun countRows(db: SQLiteDatabase, table: String): Int = try {
        db.rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    } catch (_: Exception) {
        // Table absent in an older schema: nothing to compare.
        0
    }

    private fun deleteDatabaseFiles(base: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(base.absolutePath + suffix).takeIf { it.exists() }?.delete()
        }
    }
}
