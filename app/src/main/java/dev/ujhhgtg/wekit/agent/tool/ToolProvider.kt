package dev.ujhhgtg.wekit.agent.tool

import kotlinx.serialization.json.JsonObject

/**
 * A source of tools for the agent. Either the fixed "builtin" provider (backed by the
 * `@AgentTool` registry) or one MCP server (§3.4). Both are structurally identical to the model.
 */
interface ToolProvider {
    /** Stable id used as the `providerId` in the Room permission table. */
    val id: String

    /** Human-readable name shown in settings and used to namespace tools. */
    val name: String

    val kind: ProviderKind

    /** Whether this provider is currently usable (enabled + connected for MCP). */
    val isAvailable: Boolean

    /** Snapshot of the tools this provider currently exposes. */
    fun listTools(): List<ProviderTool>

    /** Execute a tool by its bare name with the given JSON arguments, returning a model-readable result. */
    suspend fun execute(toolName: String, arguments: JsonObject): String
}

enum class ProviderKind { BUILTIN, MCP }

/**
 * A tool as advertised by a [ToolProvider]. [sideEffect] drives the session permission level's
 * approval behavior: side-effect-free tools run directly in every level, side-effecting tools are
 * gated (manually or by smart review) unless the session grants full access.
 */
data class ProviderTool(
    val name: String,
    val description: String,
    val jsonSchema: JsonObject,
    val sideEffect: Boolean,
)
