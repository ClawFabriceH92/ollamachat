package com.trucdecomptable.ollamachat.data.tools

/**
 * Decides which tool calls the user has to approve before they run.
 *
 * The app knows exactly what its own six tools do. It knows nothing at all
 * about a tool served over MCP: `files_read` and `files_delete` are the same
 * shape, and the description comes from the server, not from us. So the rule
 * is provenance, not naming — anything the app did not write is treated as
 * able to do anything, and an unrecognised name falls into that case too
 * rather than slipping through.
 */
object ToolPolicy {

    enum class Tier {
        /** Reads something; no effect outside this phone. */
        READ,

        /** Writes something the app owns, visible and undoable in the app. */
        LOCAL_WRITE,

        /** Comes from an MCP server, or is simply unknown. Could do anything. */
        EXTERNAL,
    }

    private val READ_ONLY = setOf(
        "web_search",
        "fetch_url",
        "get_current_time",
        "calculate",
        "get_weather",
    )

    private val LOCAL_WRITES = setOf("save_memory")

    /** How long an unanswered request waits before it counts as a refusal. */
    const val APPROVAL_TIMEOUT_MS = 2 * 60_000L

    fun tierOf(toolName: String, mcpPrefixes: Collection<String>): Tier = when {
        toolName in READ_ONLY -> Tier.READ
        toolName in LOCAL_WRITES -> Tier.LOCAL_WRITE
        mcpPrefixes.any { toolName.startsWith("${it}_") } -> Tier.EXTERNAL
        // Not one of ours and not from a server we know: still not a reason to
        // assume it is harmless.
        else -> Tier.EXTERNAL
    }

    /**
     * [LOCAL_WRITE] is deliberately not gated. `save_memory` writes a line the
     * user can read and delete in the settings, and prompting mid-generation
     * for it would stall the answer every time while buying nothing: the tool
     * trace already shows it happened.
     */
    fun needsApproval(
        toolName: String,
        mcpPrefixes: Collection<String>,
        gateEnabled: Boolean,
        allowed: Set<String>,
    ): Boolean {
        if (!gateEnabled) return false
        if (toolName in allowed) return false
        return tierOf(toolName, mcpPrefixes) == Tier.EXTERNAL
    }

    /** The MCP server a prefixed tool name belongs to, if any. */
    fun serverOf(toolName: String, mcpPrefixes: Collection<String>): String? =
        mcpPrefixes.firstOrNull { toolName.startsWith("${it}_") }
}
