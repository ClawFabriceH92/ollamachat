package com.trucdecomptable.ollamachat.util

import java.net.InetAddress

/** Whether an address falls inside a network prefix, compared bit by bit. */
object IpPrefix {

    /**
     * True when the first [length] bits of [ip] and [prefix] agree. Addresses
     * of different families never match; a length of 0 matches every address
     * of the family, as a default route does.
     */
    fun matches(ip: ByteArray, prefix: ByteArray, length: Int): Boolean {
        if (ip.size != prefix.size || length < 0 || length > ip.size * 8) return false
        val whole = length / 8
        for (i in 0 until whole) if (ip[i] != prefix[i]) return false
        val rest = length % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (ip[whole].toInt() and mask) == (prefix[whole].toInt() and mask)
    }

    fun matches(ip: InetAddress, prefix: InetAddress, length: Int): Boolean =
        matches(ip.address, prefix.address, length)
}
