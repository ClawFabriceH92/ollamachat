package com.trucdecomptable.ollamachat.util

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN hashing.
 *
 * Stored format: `pbkdf2$<iterations>$<saltHex>$<hashHex>`.
 *
 * Installs made before v1.3 stored an unsalted SHA-256 digest, which a 4-digit
 * PIN makes trivially reversible (10 000 candidates). [verify] still accepts
 * that legacy form so nobody is locked out, and [needsUpgrade] tells the caller
 * to re-hash the PIN with a salt right after a successful unlock.
 */
object PinUtils {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val PREFIX = "pbkdf2"
    private const val SEP = "$"

    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    /** Unsalted digest — kept only to verify PINs stored before v1.3. */
    fun legacyHash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .toHex()

    fun hash(pin: String): String = hash(pin, randomSalt())

    internal fun hash(pin: String, salt: ByteArray): String = listOf(
        PREFIX,
        ITERATIONS.toString(),
        salt.toHex(),
        derive(pin, salt, ITERATIONS).toHex(),
    ).joinToString(SEP)

    /** True when [pin] matches [stored], in either the salted or legacy format. */
    fun verify(pin: String, stored: String): Boolean {
        if (stored.isBlank()) return false
        val parts = stored.split(SEP)
        if (parts.size == 4 && parts[0] == PREFIX) {
            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = parts[2].fromHex() ?: return false
            return constantTimeEquals(derive(pin, salt, iterations).toHex(), parts[3])
        }
        return constantTimeEquals(legacyHash(pin), stored)
    }

    /** True when [stored] uses the legacy unsalted format and should be re-hashed. */
    fun needsUpgrade(stored: String): Boolean =
        stored.isNotBlank() && !stored.startsWith(PREFIX + SEP)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Length-independent comparison, so a wrong PIN leaks no timing signal. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
