package com.trucdecomptable.ollamachat.util

import java.security.MessageDigest

object PinUtils {

    /** SHA-256 hex digest of the PIN. The PIN itself is never stored in clear. */
    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it.isDigit() }
}
