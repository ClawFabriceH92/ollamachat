package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.util.ConnectivityProbe
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "It does not work" fits two opposite tunnel topologies, so the report has to
 * name which one the device is on rather than leave it to be guessed.
 */
class ConnectivityProbeTest {

    private fun report(
        vpn: Boolean,
        serverOk: Boolean,
        updateOk: Boolean,
    ) = ConnectivityProbe.Report(
        vpnActive = vpn,
        legs = listOf(
            ConnectivityProbe.Leg(
                name = "${ConnectivityProbe.SERVER} (http://192.168.1.20:11434)",
                ok = serverOk,
                detail = if (serverOk) "joignable" else "aucune route vers l'hôte",
                millis = 12,
            ),
            ConnectivityProbe.Leg(
                name = ConnectivityProbe.UPDATE,
                ok = updateOk,
                detail = if (updateOk) "à jour" else "nom d'hôte introuvable",
                millis = 34,
            ),
        ),
    )

    @Test
    fun `a full tunnel home reaches the server but not the internet`() {
        val diagnosis = ConnectivityProbe.diagnosis(report(vpn = true, serverOk = true, updateOk = false))
        assertTrue(diagnosis, diagnosis!!.contains("sortie vers Internet"))
    }

    @Test
    fun `a commercial vpn reaches the internet but not the lan`() {
        val diagnosis = ConnectivityProbe.diagnosis(report(vpn = true, serverOk = false, updateOk = true))
        assertTrue(diagnosis, diagnosis!!.contains("réseau local"))
    }

    @Test
    fun `both legs down points upstream of either`() {
        val diagnosis = ConnectivityProbe.diagnosis(report(vpn = false, serverOk = false, updateOk = false))
        assertTrue(diagnosis, diagnosis!!.contains("amont"))
    }

    @Test
    fun `nothing is diagnosed when both legs answer`() {
        assertNull(ConnectivityProbe.diagnosis(report(vpn = true, serverOk = true, updateOk = true)))
        assertNull(ConnectivityProbe.diagnosis(report(vpn = false, serverOk = true, updateOk = true)))
    }

    @Test
    fun `the report states both legs and the tunnel`() {
        val text = report(vpn = true, serverOk = true, updateOk = false).formatted()
        assertTrue(text, text.contains("VPN actif : oui"))
        assertTrue(text, text.contains(ConnectivityProbe.SERVER))
        assertTrue(text, text.contains(ConnectivityProbe.UPDATE))
        // The failing leg is marked as such, not buried in prose.
        assertTrue(text, text.contains("✗"))
        assertTrue(text, text.contains("✓"))
    }

    @Test
    fun `allOk is false as soon as one leg fails`() {
        assertTrue(report(vpn = false, serverOk = true, updateOk = true).allOk)
        assertTrue(!report(vpn = false, serverOk = true, updateOk = false).allOk)
    }
}
