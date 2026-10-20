package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.util.ConnectivityProbe
import com.trucdecomptable.ollamachat.util.ConnectivityProbe.ServerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict is read off the routes, not guessed from which leg failed: the
 * previous version called a home tunnel with no return route "a commercial
 * VPN" — the owner's exact case, and the one cause it was not.
 */
class ConnectivityProbeTest {

    private fun report(
        vpn: Boolean = true,
        serverOk: Boolean,
        updateOk: Boolean,
        updateReached: Boolean = false,
        route: ServerRoute = ServerRoute.UNKNOWN,
        vpnDefault: Boolean = false,
    ) = ConnectivityProbe.Report(
        vpnActive = vpn,
        legs = listOf(
            ConnectivityProbe.Leg(
                name = "${ConnectivityProbe.SERVER} (http://192.168.0.162:11434)",
                ok = serverOk,
                detail = if (serverOk) "joignable" else "failed to connect",
                millis = 12,
            ),
            ConnectivityProbe.Leg(
                name = ConnectivityProbe.UPDATE,
                ok = updateOk,
                detail = if (updateOk) "à jour" else "HTTP 502",
                millis = 34,
                reached = updateReached,
            ),
        ),
        serverRoute = route,
        vpnDefaultRoute = vpnDefault,
    )

    private fun verdict(r: ConnectivityProbe.Report) = ConnectivityProbe.diagnosis(r)!!

    @Test
    fun `a tunnel that routes the server's network points beyond the tunnel`() {
        // The owner's case: a home WireGuard tunnel carrying 192.168.0.0/24,
        // the server silent. The return route, NAT or the host firewall.
        val v = verdict(report(serverOk = false, updateOk = true, route = ServerRoute.VPN_SPECIFIC))
        assertTrue(v, v.contains("route de retour"))
        assertFalse("le diagnostic accuse un VPN commercial", v.contains("VPN commercial ne peut") && !v.contains("au-delà"))
        assertTrue(v, v.contains("au-delà"))
    }

    @Test
    fun `a tunnel carrying everything names both possibilities`() {
        val v = verdict(report(serverOk = false, updateOk = true, route = ServerRoute.VPN_DEFAULT_ONLY))
        assertTrue(v, v.contains("VPN commercial"))
        assertTrue(v, v.contains("VPN maison"))
    }

    @Test
    fun `a tunnel with no route to the server says how to add one`() {
        val v = verdict(report(serverOk = false, updateOk = true, route = ServerRoute.OUTSIDE_VPN))
        assertTrue(v, v.contains("AllowedIPs"))
    }

    @Test
    fun `without route information the verdict stays neutral`() {
        val v = verdict(report(serverOk = false, updateOk = true, route = ServerRoute.UNKNOWN))
        assertFalse("conclusion sans preuve", v.contains("VPN commercial"))
        assertTrue(v, v.contains("table de routage"))
    }

    @Test
    fun `both legs silent points upstream of either`() {
        val v = verdict(report(serverOk = false, updateOk = false))
        assertTrue(v, v.contains("amont"))
    }

    @Test
    fun `github refusing is not the network`() {
        // An HTTP error means something answered: Internet works.
        val v = verdict(report(serverOk = true, updateOk = false, updateReached = true))
        assertTrue(v, v.contains("GitHub répond"))
        assertFalse(v, v.contains("tunnel capte"))

        val both = verdict(report(serverOk = false, updateOk = false, updateReached = true, route = ServerRoute.VPN_SPECIFIC))
        assertFalse("une réponse de GitHub n'est pas une panne en amont", both.contains("amont"))
    }

    @Test
    fun `a full tunnel without a way out is named only when it carries a default route`() {
        val full = verdict(report(serverOk = true, updateOk = false, vpnDefault = true))
        assertTrue(full, full.contains("sortie vers Internet"))
        // A split tunnel sends GitHub over mobile data: the tunnel is not it.
        val split = verdict(report(serverOk = true, updateOk = false, vpnDefault = false))
        assertFalse(split, split.contains("tunnel"))
    }

    @Test
    fun `nothing is diagnosed when both legs answer`() {
        assertNull(ConnectivityProbe.diagnosis(report(serverOk = true, updateOk = true)))
    }

    @Test
    fun `every verdict fits in one diagnostic log entry`() {
        // The log keeps 400 characters per entry and the report is logged
        // line by line: a verdict longer than that would be cut.
        val reports = ServerRoute.values().map { report(serverOk = false, updateOk = true, route = it) } +
            listOf(
                report(serverOk = false, updateOk = false),
                report(serverOk = true, updateOk = false, updateReached = true),
                report(serverOk = true, updateOk = false, vpnDefault = true),
                report(serverOk = true, updateOk = false),
            )
        reports.forEach { r ->
            val v = verdict(r)
            assertTrue("${v.length} caractères : $v", v.length <= 400)
        }
    }

    @Test
    fun `the report states both legs and the tunnel`() {
        val text = report(serverOk = true, updateOk = false).formatted()
        assertTrue(text, text.contains("VPN actif : oui"))
        assertTrue(text, text.contains(ConnectivityProbe.SERVER))
        assertTrue(text, text.contains(ConnectivityProbe.UPDATE))
        assertTrue(text, text.contains("✗"))
        assertTrue(text, text.contains("✓"))
    }

    @Test
    fun `the routing lines travel with the report`() {
        val text = report(serverOk = false, updateOk = true).copy(
            networks = listOf("VPN (tun0) : 192.168.27.65/24", "    0.0.0.0/0  (route par défaut)"),
        ).formatted()
        assertTrue(text, text.contains("Ce que l'appareil sait joindre"))
        assertTrue(text, text.contains("192.168.27.65/24"))
    }

    @Test
    fun `only an address literal is checked against the routes`() {
        assertEquals("192.168.0.162", ConnectivityProbe.ipv4Host("http://192.168.0.162:11434"))
        assertEquals("192.168.0.162", ConnectivityProbe.ipv4Host("192.168.0.162:11434"))
        assertNull(ConnectivityProbe.ipv4Host("http://ollama.maison.lan:11434"))
        assertNull(ConnectivityProbe.ipv4Host("http://999.1.1.1:11434"))
    }

    @Test
    fun `excluded routes are recognised`() {
        assertTrue(ConnectivityProbe.isExclusion("192.168.0.0/16 -> 0.0.0.0 tun0 mtu 1280 throw"))
        assertTrue(ConnectivityProbe.isExclusion("10.0.0.0/8 unreachable"))
        assertFalse(ConnectivityProbe.isExclusion("192.168.0.0/24 -> 0.0.0.0 tun0 mtu 1280"))
    }

    @Test
    fun `ipv6 lines are left out, colons and all`() {
        assertTrue(ConnectivityProbe.isIpv4("192.168.0.0/24 -> 0.0.0.0 wlan0"))
        assertFalse(ConnectivityProbe.isIpv4("fe80::/64 -> :: wlan0"))
    }

    @Test
    fun `allOk is false as soon as one leg fails`() {
        assertTrue(report(serverOk = true, updateOk = true).allOk)
        assertFalse(report(serverOk = true, updateOk = false).allOk)
    }
}
