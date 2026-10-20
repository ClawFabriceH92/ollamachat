package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.web.UrlFetcher
import com.trucdecomptable.ollamachat.util.IpPrefix
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * What a fetch the model asked for may not reach. The check runs on the
 * address actually connected to; these pin the ranges it refuses.
 */
class UrlFetcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** Long enough to count as a page (200 characters at least). */
    private val page = "<html><body><p>" + "Contenu de test. ".repeat(30) + "</p></body></html>"

    private fun blocked(ip: String) = UrlFetcher.isPrivate(InetAddress.getByName(ip).address)

    @Test
    fun `the lan, loopback and link-local are refused`() {
        listOf(
            "192.168.0.162", "192.168.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254",
            "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0",
        ).forEach { assertTrue(it, blocked(it)) }
    }

    @Test
    fun `ipv6 private forms are refused, mapped ipv4 included`() {
        listOf("::1", "fe80::1", "fd12:3456::1").forEach { assertTrue(it, blocked(it)) }
        // Built by hand: InetAddress turns "::ffff:a.b.c.d" into a plain IPv4
        // address, and the mapped branch would never run.
        val mapped = ByteArray(16).also {
            it[10] = 0xFF.toByte()
            it[11] = 0xFF.toByte()
            byteArrayOf(192.toByte(), 168.toByte(), 0, 162.toByte()).copyInto(it, 12)
        }
        assertTrue(UrlFetcher.isPrivate(mapped))
        val mappedPublic = mapped.copyOf().also { byteArrayOf(1, 1, 1, 1).copyInto(it, 12) }
        assertFalse(UrlFetcher.isPrivate(mappedPublic))
    }

    @Test
    fun `a prefix match is bit by bit`() {
        val prefix = InetAddress.getByName("2a01:e0a:1:2::")
        assertTrue(IpPrefix.matches(InetAddress.getByName("2a01:e0a:1:2::1"), prefix, 64))
        assertFalse(IpPrefix.matches(InetAddress.getByName("2a01:e0a:1:3::1"), prefix, 64))
        assertTrue(IpPrefix.matches(InetAddress.getByName("192.168.0.162"), InetAddress.getByName("192.168.0.0"), 24))
        // Not on a byte boundary: 192.168.0.0/23 holds 192.168.1.x, not 192.168.2.x.
        assertTrue(IpPrefix.matches(InetAddress.getByName("192.168.1.162"), InetAddress.getByName("192.168.0.0"), 23))
        assertFalse(IpPrefix.matches(InetAddress.getByName("192.168.2.1"), InetAddress.getByName("192.168.0.0"), 23))
        // Never across families.
        assertFalse(IpPrefix.matches(InetAddress.getByName("::1"), InetAddress.getByName("0.0.0.0"), 0))
    }

    @Test
    fun `hosts only a local network can resolve stay away from the reader service`() {
        listOf("nas", "nas.local", "box.lan", "printer.home.arpa", "::1", "fe80::1").forEach {
            assertTrue(it, UrlFetcher.looksLocal(it))
        }
        listOf("www.legifrance.gouv.fr", "fr.wikipedia.org").forEach { assertFalse(it, UrlFetcher.looksLocal(it)) }
    }

    @Test
    fun `a page on the lan is refused to the model, before any byte is sent`() = runBlocking {
        server.enqueue(MockResponse().setBody(page))
        val result = UrlFetcher.fetch(server.url("/").toString())

        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is UrlFetcher.PrivateAddressBlocked)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the owner's own typed address may reach the lan`() = runBlocking {
        server.enqueue(MockResponse().setBody(page))
        val result = UrlFetcher.fetch(server.url("/").toString(), allowPrivate = true)

        assertTrue(result.getOrNull().orEmpty().contains("Contenu de test"))
    }

    @Test
    fun `a redirect from the typed host to another machine of the lan is refused`() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody(page))
            // Same machine, other name: as far as the check goes, another host.
            val host = server.url("/").host
            val elsewhere = if (host == "127.0.0.1") "localhost" else "127.0.0.1"
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://$elsewhere:${other.port}/"))

            val result = UrlFetcher.fetch(server.url("/").toString(), allowPrivate = true)

            assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is UrlFetcher.PrivateAddressBlocked)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `public addresses pass`() {
        listOf("1.1.1.1", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::1111").forEach {
            assertFalse(it, blocked(it))
        }
    }

    @Test
    fun `a private literal host is recognised from the url alone`() {
        assertTrue(UrlFetcher.isPrivateLiteral("http://192.168.0.162:11434/api/tags"))
        assertFalse(UrlFetcher.isPrivateLiteral("https://www.legifrance.gouv.fr/"))
        assertFalse(UrlFetcher.isPrivateLiteral("https://1.1.1.1/"))
    }
}
