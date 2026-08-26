package com.trucdecomptable.ollamachat.data.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted container for a data export.
 *
 * An export is the one copy of the conversations that leaves the device, so it
 * is encrypted rather than trusted to wherever the user puts it.
 *
 * Layout: `OCB1` | salt(16) | iv(12) | AES-256-GCM ciphertext.
 * Pure JVM on purpose — the format is covered by unit tests.
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    /** Shortest passphrase accepted; below this the encryption is theatre. */
    const val MIN_PASSPHRASE = 8

    class WrongPassphraseException : Exception("Phrase de passe incorrecte ou fichier abîmé")
    class NotABackupException : Exception("Ce fichier n’est pas une sauvegarde OllamaChat")

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            // Bind the header so a swapped salt or IV fails the tag check.
            updateAAD(MAGIC)
        }
        val body = cipher.doFinal(plain)
        return MAGIC + salt + iv + body
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): ByteArray {
        val headerSize = MAGIC.size + SALT_BYTES + IV_BYTES
        if (container.size <= headerSize) throw NotABackupException()
        if (!container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackupException()

        val salt = container.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = container.copyOfRange(MAGIC.size + SALT_BYTES, headerSize)
        val body = container.copyOfRange(headerSize, container.size)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(MAGIC)
                doFinal(body)
            }
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPassphraseException()
        }
    }

    fun isBackup(container: ByteArray): Boolean =
        container.size > MAGIC.size && container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
