package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.NetworkScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkScannerTest {

    @Test
    fun `hexToInt parses little-endian routes`() {
        // /proc/net/route stores IPv4 in little-endian hex.
        assertEquals(0xC0A80000.toInt(), NetworkScanner.hexToInt("0000A8C0"))   // 192.168.0.0
        assertEquals(0x0A000000, NetworkScanner.hexToInt("0000000A"))   // 10.0.0.0
        assertEquals(0x7F000001, NetworkScanner.hexToInt("0100007F"))   // 127.0.0.1
    }

    @Test
    fun `hexToInt rejects garbage`() {
        assertNull(NetworkScanner.hexToInt("zzzz"))
        assertNull(NetworkScanner.hexToInt(""))
    }
}
