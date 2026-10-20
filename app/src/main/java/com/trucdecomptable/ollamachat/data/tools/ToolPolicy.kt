package com.trucdecomptable.ollamachat.data.tools

import org.json.JSONObject

/**
 * Decides which tool calls the user has to approve before they run.
 *
 * The rule is about where data can go, not about what a tool is called.
 *
 * - The app knows exactly what its own tools do. Most stay on the phone, or
 *   send text only to fixed services (a search engine, a weather API) that an
 *   attacker cannot read.
 * - fetch_url is different: the model chooses the address. A page planted
 *   with instructions can make it put the conversation into a URL and send it
 *   anywhere, with no click from anyone. It used to be classed as a read and
 *   ran unasked, which made it the one open door the gate was built to close.
 * - An MCP tool is unknown by construction: `files_read` and `files_delete`
 *   have the same shape, and the description comes from the server. Anything
 *   the app did not write is treated as able to do anything, an unrecognised
 *   name included.
 */
object ToolPolicy {

    enum class Tier {
        /** Stays on the phone and changes nothing. */
        READ,

        /** Sends model-chosen text, but only to a fixed, known service. */
        THIRD_PARTY,

        /** Reaches an address the model chose: an exfiltration channel. */
        NETWORK,

        /** Writes something the app owns, visible and undoable in the app. */
        LOCAL_WRITE,

        /** Comes from an MCP server, or is simply unknown. Could do anything. */
        EXTERNAL,
    }

    private val READ_ONLY = setOf("get_current_time", "calculate")
    private val THIRD_PARTY_TOOLS = setOf("web_search", "get_weather")
    private val NETWORK_TOOLS = setOf("fetch_url")
    private val LOCAL_WRITES = setOf("save_memory")

    /** Every tool the app itself implements. A native name always wins over MCP. */
    val NATIVE: Set<String> = READ_ONLY + THIRD_PARTY_TOOLS + NETWORK_TOOLS + LOCAL_WRITES

    /** How long an unanswered request waits before it counts as a refusal. */
    const val APPROVAL_TIMEOUT_MS = 2 * 60_000L

    fun tierOf(toolName: String, mcpPrefixes: Collection<String>): Tier = when {
        toolName in READ_ONLY -> Tier.READ
        toolName in THIRD_PARTY_TOOLS -> Tier.THIRD_PARTY
        toolName in NETWORK_TOOLS -> Tier.NETWORK
        toolName in LOCAL_WRITES -> Tier.LOCAL_WRITE
        serverOf(toolName, mcpPrefixes) != null -> Tier.EXTERNAL
        // Not one of ours and not from a server we know: still not a reason to
        // assume it is harmless.
        else -> Tier.EXTERNAL
    }

    /**
     * Whether the user has to say yes first.
     *
     * fetch_url runs unasked only on an address that already existed before
     * the model could have written anything into it: one the user typed, or
     * one a search returned this turn, compared character for character. A
     * trusted domain is not enough, because the data can ride in the path or
     * a subdomain; nor is a trusted address with a dot or a slash more at the
     * end, because how many the model adds spells out data too. The
     * always-allowed list does not apply to it either: "always" would mean the
     * door stays open for every future page.
     *
     * [LOCAL_WRITE] is deliberately not gated: `save_memory` writes a line the
     * user can read and delete in the settings, and the trace shows it.
     */
    fun needsApproval(
        toolName: String,
        arguments: String,
        mcpPrefixes: Collection<String>,
        gateEnabled: Boolean,
        allowed: Set<String>,
        trustedUrls: Set<String> = emptySet(),
    ): Boolean {
        if (!gateEnabled) return false
        return when (tierOf(toolName, mcpPrefixes)) {
            Tier.READ, Tier.THIRD_PARTY, Tier.LOCAL_WRITE -> false
            Tier.NETWORK -> urlArgument(arguments)?.let { it in trustedUrls } != true
            Tier.EXTERNAL -> toolName !in allowed
        }
    }

    /** Only an MCP tool can be allowed for good; see [needsApproval]. */
    fun canAlwaysAllow(toolName: String, mcpPrefixes: Collection<String>): Boolean =
        tierOf(toolName, mcpPrefixes) == Tier.EXTERNAL

    /**
     * The MCP server a prefixed tool name belongs to, if any. The longest
     * matching name wins, so a server called "a" cannot capture the tools of
     * one called "a_b".
     */
    fun serverOf(toolName: String, mcpPrefixes: Collection<String>): String? =
        mcpPrefixes.filter { toolName.startsWith("${it}_") }.maxByOrNull { it.length }

    /**
     * True when a new MCP server name would be ambiguous. Tool names are
     * "server_tool": a server called "fetch" would seem to provide fetch_url,
     * two servers of one name share a single "always" list and one of them
     * receives every call, and "a" next to "a_b" can each claim the other's
     * tools. An underscore in a new name is refused for that last reason.
     */
    fun mcpNameClashes(name: String, existing: Collection<String>): Boolean =
        name in existing || '_' in name ||
            NATIVE.any { it.startsWith("${name}_") } ||
            existing.any { it.startsWith("${name}_") || name.startsWith("${it}_") }

    /**
     * One search result as the app writes it into the conversation: a single
     * line ending with the result's address, then its extract. Titles and
     * extracts are flattened onto one line each, so text a site wrote can
     * never forge a line of its own.
     */
    fun searchResultLine(index: Int, title: String, url: String, snippet: String): String =
        "$index. ${oneLine(title)} — ${url.trim()}\n   ${oneLine(snippet)}"

    /** Every kind of space and control character, line and paragraph separators included. */
    private fun oneLine(text: String) = text.replace(Regex("[\\s\\p{Z}\\p{Cc}]+"), " ").trim()

    /** Only "\n" ends a line: U+0085, U+2028 and the like would let a title start one. */
    private val RESULT_LINE =
        Regex("""^\d+\. .* — (https?://\S+)$""", setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES))

    /**
     * The result addresses of a block written with [searchResultLine], and
     * nothing else: an address a title or an extract mentions is the site's
     * own text, and trusting it let any indexed page plant a list of
     * addresses to be read without asking.
     */
    fun searchResultUrls(text: String): Set<String> =
        RESULT_LINE.findAll(text).map { it.groupValues[1] }.toSet()

    /** The "url" argument of a fetch_url call, or null when there is none. */
    fun urlArgument(arguments: String): String? = runCatching {
        JSONObject(arguments).optString("url", "").trim().ifBlank { null }
    }.getOrNull()

    private val URL_PATTERN = Regex("""https?://[^\s<>"'`\]}]+""")

    /** Addresses written in [text], without what the sentence around them adds. */
    fun extractUrls(text: String): Set<String> =
        URL_PATTERN.findAll(text).map { trimSentence(it.value) }.toSet()

    /**
     * For an address read from prose only: the punctuation a sentence puts
     * after it, and a closing parenthesis it never opened, as in
     * "(voir https://site/page)". Never applied to the address the model asks
     * for, which must equal one of these exactly.
     */
    internal fun trimSentence(url: String): String {
        var current = url
        while (true) {
            val trimmed = current.trimEnd('.', ',', ';', ':', '!', '?', '»')
            val unbalanced = trimmed.endsWith(')') && trimmed.count { it == ')' } > trimmed.count { it == '(' }
            val next = if (unbalanced) trimmed.dropLast(1) else trimmed
            if (next == current) return current
            current = next
        }
    }
}
