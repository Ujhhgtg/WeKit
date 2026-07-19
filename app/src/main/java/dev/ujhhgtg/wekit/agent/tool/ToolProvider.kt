package dev.ujhhgtg.wekit.agent.tool

import kotlinx.serialization.json.JsonObject

/**
 * A source of tools for the agent. Either the fixed "builtin" provider (backed by the
 * `@WeKitOperation` registry) or one MCP server (§3.4). Both are structurally identical to the model.
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
 * A tool as advertised by a [ToolProvider], before permission resolution. [factoryDefaultMode]
 * is the out-of-the-box mode used only to seed the permission table for a never-seen tool.
 */
data class ProviderTool(
    val name: String,
    val description: String,
    val jsonSchema: JsonObject,
    val factoryDefaultMode: ToolMode,
)
