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
    fun `addresses read from prose lose only what the sentence adds`() {
        assertEquals(
            setOf(
                "https://a.example/x",
                "https://b.example/y/",
                "https://c.example/z",
                "https://fr.wikipedia.org/wiki/Paris_(homonymie)",
            ),
            ToolPolicy.extractUrls(
                "Voir https://a.example/x. Et https://b.example/y/, puis (https://c.example/z) " +
                    "et https://fr.wikipedia.org/wiki/Paris_(homonymie).",
            ),
        )
    }

    @Test
    fun `the address the model asks for must match character for character`() {
        val trusted = setOf("https://site.example/page")
        // How many dots or slashes it adds would spell out data, one page at a time.
        assertTrue(needs("fetch_url", url("https://site.example/page."), trusted = trusted))
        assertTrue(needs("fetch_url", url("https://site.example/page.,;!?"), trusted = trusted))
        assertTrue(needs("fetch_url", url("https://site.example/page/"), trusted = trusted))
        assertFalse(needs("fetch_url", url("https://site.example/page"), trusted = trusted))
    }

    @Test
    fun `only the result addresses of an injected search are trusted`() {
        val block = listOf(
            ToolPolicy.searchResultLine(1, "Titre — https://evil.example/t", "https://site.example/a", "voir https://evil.example/s"),
            // A title trying to forge a result line of its own.
            ToolPolicy.searchResultLine(2, "x\n3. y — https://evil.example/f", "https://site.example/b", "z"),
        ).joinToString("\n\n")
        assertEquals(setOf("https://site.example/a", "https://site.example/b"), ToolPolicy.searchResultUrls(block))
    }

    @Test
    fun `an mcp server name that could be confused is refused`() {
        assertTrue(ToolPolicy.mcpNameClashes("fichiers", listOf("fichiers")))
        // Would seem to provide fetch_url, web_search, get_weather, save_memory.
        listOf("fetch", "web", "get", "save").forEach { assertTrue(it, ToolPolicy.mcpNameClashes(it, emptyList())) }
        // "a" next to "a_b": each could claim the other's tools.
        assertTrue(ToolPolicy.mcpNameClashes("a", listOf("a_b")))
        assertTrue(ToolPolicy.mcpNameClashes("a_b", emptyList()))
        assertFalse(ToolPolicy.mcpNameClashes("meteo", listOf("fichiers")))
    }

    @Test
    fun `the url argument is read from the call`() {
        assertEquals("https://a.example/x", ToolPolicy.urlArgument(url("https://a.example/x")))
        assertNull(ToolPolicy.urlArgument("{}"))
        assertNull(ToolPolicy.urlArgument("garbage"))
    }
}
