package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.web.UrlFetcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * What a fetch the model asked for may not reach. The check runs on the
 * address actually connected to; these pin the ranges it refuses.
 */
class UrlFetcherTest {

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
        listOf("::1", "fe80::1", "fd12:3456::1", "::ffff:192.168.0.162").forEach { assertTrue(it, blocked(it)) }
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
