package com.trucdecomptable.ollamachat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (API keys) with an AES key held in the Android
 * Keystore, so they are never written to disk in clear — DataStore files are
 * readable on a rooted device and by anything that can reach app storage.
 *
 * Stored form: `v1:<base64 iv>:<base64 ciphertext>`. Values that do not carry
 * that prefix are returned as-is, so keys saved by an older version keep
 * working until the user next edits them.
 */
object SecretVault {

    private const val ALIAS = "ollamachat_secrets"
    private const val PREFIX = "v1"
    private const val SEP = ":"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            listOf(PREFIX, cipher.iv.b64(), bytes.b64()).joinToString(SEP)
        } catch (_: Exception) {
            // Keystore unavailable (rare OEM failures): storing in clear is
            // still better than losing the user's key.
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val parts = stored.split(SEP)
        if (parts.size != 3 || parts[0] != PREFIX) return stored
        return try {
            val iv = parts[1].unb64()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(parts[2].unb64()), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
