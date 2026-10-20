package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.NetworkScanner
import org.junit.Assert.assertEquals
import org.junit.Test

/** A route and an interface on one subnet are the same network, counted once. */
class NetworkScannerTest {

    @Test
    fun `an address is reduced to its network`() {
        assertEquals("192.168.0.0", NetworkScanner.networkOf("192.168.0.37", 24))
        assertEquals("192.168.0.0", NetworkScanner.networkOf("192.168.0.0", 24))
        assertEquals("10.8.0.0", NetworkScanner.networkOf("10.8.3.9", 16))
        assertEquals("172.16.0.0", NetworkScanner.networkOf("172.31.255.1", 12))
        assertEquals("192.168.27.65", NetworkScanner.networkOf("192.168.27.65", 32))
    }
}
