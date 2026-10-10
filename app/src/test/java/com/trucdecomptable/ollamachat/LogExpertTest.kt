package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.util.DiagnosticLog
import com.trucdecomptable.ollamachat.util.LogExpert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExpertTest {

    private var clock = 1_760_000_000_000L

    private fun entry(tag: String, message: String) =
        DiagnosticLog.Entry(at = clock++, tag = tag, message = message)

    /** Exactly what OllamaClient records for the failure in the screenshots. */
    private val realTimeout = entry(
        "ollama",
        "SocketTimeoutException: failed to connect to /192.168.0.162 (port 11434) " +
            "from /192.168.27.65 (port 38780) after 10000ms",
    )

    @Test
    fun `the tunnel case is read straight off the log line`() {
        val findings = LogExpert.analyze(listOf(realTimeout))
        assertEquals(1, findings.size)
        val finding = findings.first()
        assertEquals(LogExpert.Severity.BLOCKING, finding.severity)
        // Both addresses are named, because which is which is the whole point.
        assertTrue(finding.title, finding.title.contains("192.168.27.65"))
        assertTrue(finding.title, finding.title.contains("192.168.0.162"))
        // The router guess is derived from the server's subnet, not the phone's.
        assertTrue(finding.action, finding.action.contains("http://192.168.0.1"))
        assertTrue(finding.action, finding.action.contains("11434"))
        // The evidence travels with the verdict.
        assertEquals(realTimeout.message, finding.evidence)
    }

    @Test
    fun `a timeout inside one subnet is not blamed on a tunnel`() {
        // Same /24: the server is simply not answering. Naming a routing
        // problem here would be a confident wrong answer.
        val findings = LogExpert.analyze(
            listOf(
                entry(
                    "ollama",
                    "SocketTimeoutException: failed to connect to /192.168.0.162 (port 11434) " +
                        "from /192.168.0.44 (port 38780) after 10000ms",
                )
            )
        )
        assertTrue(findings.toString(), findings.isEmpty())
    }

    @Test
    fun `an active tunnel is mentioned only when connectivity saw one`() {
        val withoutReport = LogExpert.analyze(listOf(realTimeout)).first()
        assertTrue(!withoutReport.action.startsWith("Un tunnel est actif"))

        val withReport = LogExpert.analyze(
            listOf(realTimeout, entry("réseau", "VPN actif : oui | ✗ Serveur Ollama — injoignable"))
        ).first { it.severity == LogExpert.Severity.BLOCKING }
        assertTrue(withReport.action, withReport.action.startsWith("Un tunnel est actif"))
    }

    @Test
    fun `repeated failures are counted, not repeated`() {
        val findings = LogExpert.analyze(listOf(realTimeout, realTimeout, realTimeout))
        assertEquals(1, findings.size)
        assertEquals(3, findings.first().occurrences)
    }

    @Test
    fun `a refusal points at what is listening, not at the network`() {
        val findings = LogExpert.analyze(
            listOf(entry("ollama", "ConnectException: Connection refused"))
        )
        assertEquals(LogExpert.Severity.BLOCKING, findings.first().severity)
        assertTrue(findings.first().action.contains("OLLAMA_HOST"))
    }

    @Test
    fun `an exhausted quota is explained rather than counted twice`() {
        val findings = LogExpert.analyze(
            listOf(
                entry(
                    "update",
                    "vérification impossible : quota GitHub épuisé pour cette adresse IP " +
                        "(fréquent derrière un VPN à sortie partagée)",
                )
            )
        )
        // The generic "update checks are failing" finding must stand down for it.
        assertEquals(1, findings.size)
        assertTrue(findings.first().title, findings.first().title.contains("quota"))
    }

    @Test
    fun `a non-quota update failure still gets its own finding`() {
        val findings = LogExpert.analyze(
            listOf(entry("update", "vérification impossible : délai d'attente dépassé"))
        )
        assertEquals(1, findings.size)
        assertTrue(findings.first().title.contains("mise à jour"))
    }

    @Test
    fun `blocking findings come before warnings`() {
        val findings = LogExpert.analyze(
            listOf(
                entry("mcp/outils", "IOException: refusé"),
                entry("update", "vérification impossible : délai d'attente dépassé"),
                realTimeout,
            )
        )
        assertEquals(LogExpert.Severity.BLOCKING, findings.first().severity)
        assertTrue(findings.last().severity != LogExpert.Severity.BLOCKING)
    }

    @Test
    fun `an unreadable encrypted database is reported as a privacy matter`() {
        val findings = LogExpert.analyze(
            listOf(entry("db/open", "UnsatisfiedLinkError: libsqlcipher"))
        )
        assertTrue(findings.first().action, findings.first().action.contains("non chiffrée"))
    }

    @Test
    fun `an empty or unremarkable log yields nothing at all`() {
        assertTrue(LogExpert.analyze(emptyList()).isEmpty())
        assertTrue(
            LogExpert.analyze(
                listOf(
                    entry("update", "aucune version plus récente que 1.5.5"),
                    entry("ephemeral", "2 conversations expirées supprimées"),
                )
            ).isEmpty()
        )
    }

    @Test
    fun `subnet comparison is on the first three octets`() {
        assertTrue(LogExpert.sameSubnet24("192.168.0.1", "192.168.0.254"))
        assertTrue(!LogExpert.sameSubnet24("192.168.0.1", "192.168.27.65"))
        assertTrue(!LogExpert.sameSubnet24("10.0.0.1", "192.168.0.1"))
    }
}
