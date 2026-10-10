package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.tools.ToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate decides what runs unattended. Its failure mode is silent, so each
 * rule is pinned here — above all the ones that must answer "ask".
 */
class ToolPolicyTest {

    private val servers = listOf("fichiers", "courriel")

    @Test
    fun `the built-in read-only tools never interrupt`() {
        listOf("web_search", "fetch_url", "get_current_time", "calculate", "get_weather")
            .forEach { tool ->
                assertEquals(tool, ToolPolicy.Tier.READ, ToolPolicy.tierOf(tool, servers))
                assertTrue(
                    tool,
                    !ToolPolicy.needsApproval(tool, servers, gateEnabled = true, allowed = emptySet()),
                )
            }
    }

    @Test
    fun `an mcp tool is external whatever it is called`() {
        // Reassuring names are exactly what a gate must not fall for.
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("fichiers_lire", servers))
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("fichiers_supprimer", servers))
        assertTrue(
            ToolPolicy.needsApproval("fichiers_lire", servers, gateEnabled = true, allowed = emptySet())
        )
    }

    @Test
    fun `an unrecognised name is treated as external, not as safe`() {
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("inconnu", servers))
        assertTrue(
            ToolPolicy.needsApproval("inconnu", servers, gateEnabled = true, allowed = emptySet())
        )
        // A name that merely looks like one of ours is still not one of ours.
        assertTrue(
            ToolPolicy.needsApproval("web_search_v2", servers, gateEnabled = true, allowed = emptySet())
        )
    }

    @Test
    fun `save_memory writes but is not gated`() {
        // Bounded, visible and undoable in the app: prompting would stall every
        // answer for nothing. The tier still records that it writes.
        assertEquals(ToolPolicy.Tier.LOCAL_WRITE, ToolPolicy.tierOf("save_memory", servers))
        assertTrue(
            !ToolPolicy.needsApproval("save_memory", servers, gateEnabled = true, allowed = emptySet())
        )
    }

    @Test
    fun `an allowed tool stops asking`() {
        assertTrue(
            !ToolPolicy.needsApproval(
                "fichiers_lire",
                servers,
                gateEnabled = true,
                allowed = setOf("fichiers_lire"),
            )
        )
        // Allowing one tool does not allow its neighbours on the same server.
        assertTrue(
            ToolPolicy.needsApproval(
                "fichiers_supprimer",
                servers,
                gateEnabled = true,
                allowed = setOf("fichiers_lire"),
            )
        )
    }

    @Test
    fun `turning the gate off asks for nothing`() {
        assertTrue(
            !ToolPolicy.needsApproval("fichiers_supprimer", servers, gateEnabled = false, allowed = emptySet())
        )
    }

    @Test
    fun `with no server configured an mcp-looking name is still external`() {
        assertEquals(ToolPolicy.Tier.EXTERNAL, ToolPolicy.tierOf("fichiers_lire", emptyList()))
        assertTrue(
            ToolPolicy.needsApproval("fichiers_lire", emptyList(), gateEnabled = true, allowed = emptySet())
        )
    }

    @Test
    fun `the server behind a tool is named so the dialog can say it`() {
        assertEquals("fichiers", ToolPolicy.serverOf("fichiers_lire", servers))
        assertEquals("courriel", ToolPolicy.serverOf("courriel_envoyer", servers))
        assertNull(ToolPolicy.serverOf("web_search", servers))
        assertNull(ToolPolicy.serverOf("inconnu", servers))
    }

    @Test
    fun `an unanswered request times out rather than waiting forever`() {
        assertTrue(ToolPolicy.APPROVAL_TIMEOUT_MS > 0)
    }
}
