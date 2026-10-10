package com.trucdecomptable.ollamachat.data.tools

/** What the user is being asked to allow. */
data class ApprovalRequest(
    val toolName: String,
    /** The MCP server it came from, null for a built-in tool. */
    val serverName: String?,
    /** The arguments, as the model sent them, for the user to read. */
    val arguments: String,
)

enum class ApprovalDecision {
    ONCE,
    ALWAYS,
    DENY,
}

/**
 * Asks the user. Implemented by the UI layer, because only it can ask.
 *
 * The default refuses: a repository with no one to ask must not decide that
 * silence means yes.
 */
interface ToolApprover {
    suspend fun request(request: ApprovalRequest): ApprovalDecision

    companion object {
        val DenyAll = object : ToolApprover {
            override suspend fun request(request: ApprovalRequest) = ApprovalDecision.DENY
        }
    }
}
