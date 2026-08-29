package dev.ujhhgtg.wekit.agent.tool

/**
 * Session-level tool permission (§3.1). Replaces the old per-tool mode: every tool call is judged
 * by the session's effective [PermissionLevel] combined with the tool's side-effect flag
 * ([AgentToolDescriptor.sideEffect] for built-ins; MCP tools always count as side-effecting).
 *
 * - [REQUEST_APPROVAL]: side-effect-free tools run directly; everything else waits for a manual
 *   decision.
 * - [AUTO_EDIT]: like REQUEST_APPROVAL, but the builtin `edit` tool also runs directly.
 * - [AUTO_APPROVAL]: side-effect-free tools run directly; the rest get the small-model smart review.
 * - [FULL_ACCESS]: every tool runs directly.
 *
 * Declaration order is the user-facing order (settings picker + panel menu).
 */
enum class PermissionLevel {
    REQUEST_APPROVAL,
    AUTO_EDIT,
    AUTO_APPROVAL,
    FULL_ACCESS,
}
