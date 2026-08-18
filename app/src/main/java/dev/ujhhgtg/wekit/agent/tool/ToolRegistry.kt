package dev.ujhhgtg.wekit.agent.tool

import dev.ujhhgtg.wekit.agent.tool.ToolRegistry.Companion.DISCOVER_TOOLS_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Resolves the factory-defaulted, user-overridable [ToolMode] for a given provider + tool.
 * Backed by the Room `tool_permissions` table at runtime (Phase 2); a permissive default impl is
 * used when persistence is unavailable.
 */
fun interface ToolPermissionSource {
    fun modeFor(providerId: String, toolName: String, factoryDefault: ToolMode): ToolMode
}

/** How tools are advertised to the model for a request (§3.3). */
enum class ToolLoadingMode { STATIC, DYNAMIC }

/**
 * Per-turn gating of the conditionally-advertised builtin tools (§3.4).
 *
 * This is carried on [dev.ujhhgtg.wekit.agent.engine.TurnConfig] and threaded into every tool-list
 * build rather than living in a process-global flag: sessions run concurrently (a foreground chat
 * plus one or more trigger-fired background turns), and the tool list is rebuilt on every request.
 * With a global, whichever session resolved its config last decided the vision gate for ALL of them
 * — a non-vision background turn could strip `ui-screenshot` out from under a running vision turn,
 * or hand it to a non-vision model whose provider then rejects the injected images with HTTP 400.
 */
data class ToolVisibility(
    /** The turn's model declares vision support, so `ui-screenshot` may be advertised. */
    val visionTools: Boolean,
    /** A workspace is resolved for this turn or memory is enabled, so the fs file tools may be advertised. */
    val fsTools: Boolean,
) {
    companion object {
        /**
         * Visibility outside a resolved turn (settings previews, defaults). Vision is off — nothing
         * has declared a vision model — and fs follows [BuiltinToolProvider.fsToolsVisible], which
         * reflects the global memory setting only: within a running turn, fs visibility is resolved
         * per turn from the session's effective workspace + memory and snapshotted into that turn's
         * [ToolVisibility], so it can't be clobbered by a concurrent session.
         */
        fun fromGlobals(): ToolVisibility =
            ToolVisibility(visionTools = false, fsTools = BuiltinToolProvider.fsToolsVisible)
    }
}

/**
 * A tool as it will be sent to the model, after permission resolution and name qualification.
 * [exposedName] is what the model calls; it maps back to a concrete [provider] + [bareName].
 */
data class WireTool(
    val exposedName: String,
    val description: String,
    val jsonSchema: JsonObject,
    val mode: ToolMode,
    val provider: ToolProvider,
    val bareName: String,
)

/**
 * The heart of §3: unifies the builtin provider and every connected MCP provider, applies the
 * four-state permission model, and produces the request-time tool list in either static-injection
 * or dynamic-discovery mode. Not tied to a single conversation — the engine holds per-turn
 * discovery state separately (see [discoveredThisTurn]).
 */
class ToolRegistry(
    private val permissions: ToolPermissionSource,
    providers: List<ToolProvider> = BuiltinToolProvider.all,
) {
    private val providers = providers.toMutableList()

    fun setMcpProviders(mcpProviders: List<ToolProvider>) {
        providers.removeAll { it.kind == ProviderKind.MCP }
        providers.addAll(mcpProviders)
    }

    fun allProviders(): List<ToolProvider> = providers.toList()

    /** Qualified name a tool is exposed under: bare for builtin, namespaced for MCP. */
    private fun exposedName(provider: ToolProvider, bare: String): String =
        if (provider.kind == ProviderKind.BUILTIN) bare else "mcp__${provider.id}__$bare"

    /**
     * Every non-disabled, available tool across providers, with resolved modes. [visibility] gates
     * the conditionally-advertised builtin tools for this turn (see [ToolVisibility]); providers
     * themselves list everything they own, so gating lives in exactly one place.
     */
    fun resolveVisibleTools(visibility: ToolVisibility = ToolVisibility.fromGlobals()): List<WireTool> = buildList {
        for (provider in providers) {
            if (!provider.isAvailable) continue
            for (tool in provider.listTools()) {
                if (!isAdvertised(provider, tool.name, visibility)) continue
                val mode = permissions.modeFor(provider.id, tool.name, tool.factoryDefaultMode)
                if (mode == ToolMode.DISABLED) continue
                add(
                    WireTool(
                        exposedName = exposedName(provider, tool.name),
                        description = tool.description,
                        jsonSchema = tool.jsonSchema,
                        mode = mode,
                        provider = provider,
                        bareName = tool.name,
                    )
                )
            }
        }
    }

    /**
     * Whether a builtin tool is advertised at all under [visibility]. Non-builtin (MCP) providers are
     * never gated this way. Kept name-based so gating never touches permission seeding — the rows are
     * still seeded, the tools are simply not offered to the model this turn.
     */
    private fun isAdvertised(provider: ToolProvider, bareName: String, visibility: ToolVisibility): Boolean {
        if (provider.kind != ProviderKind.BUILTIN) return true
        if (!visibility.fsTools && bareName in BuiltinToolProvider.FS_TOOL_NAMES) return false
        if (!visibility.visionTools && bareName in BuiltinToolProvider.VISION_TOOL_NAMES) return false
        return true
    }

    /**
     * The tools to inject into a request. In [ToolLoadingMode.STATIC], all visible tools. In
     * [ToolLoadingMode.DYNAMIC], only the `discover_tools` meta-tool plus whatever the model has
     * discovered so far this turn ([discoveredThisTurn], holding exposed names).
     */
    fun requestTools(
        mode: ToolLoadingMode,
        discoveredThisTurn: Set<String>,
        visibility: ToolVisibility = ToolVisibility.fromGlobals(),
    ): List<WireTool> =
        when (mode) {
            ToolLoadingMode.STATIC -> resolveVisibleTools(visibility)
            ToolLoadingMode.DYNAMIC -> buildList {
                add(discoverToolsMeta())
                resolveVisibleTools(visibility).filterTo(this) { it.exposedName in discoveredThisTurn }
            }
        }

    /** Look up a resolved tool by the name the model called. */
    fun findByExposedName(
        exposedName: String,
        visibility: ToolVisibility = ToolVisibility.fromGlobals(),
    ): WireTool? = resolveVisibleTools(visibility).firstOrNull { it.exposedName == exposedName }

    /**
     * Execute a resolved tool. Permission gating is the engine's responsibility; this performs the
     * actual call once approved. [DISCOVER_TOOLS_NAME] is handled by the engine, not here.
     */
    suspend fun execute(tool: WireTool, arguments: JsonObject): String =
        tool.provider.execute(tool.bareName, arguments)

    // -------- discover_tools meta-tool (§3.3) --------

    fun discoverToolsMeta(): WireTool = WireTool(
        exposedName = DISCOVER_TOOLS_NAME,
        description = "Discover available tools. action=list_providers lists tool providers; " +
                "action=list_tools returns tools (optionally filtered by provider) with full JSON schemas; " +
                "action=search_tools fuzzy-matches name/description by keyword. Returned tools become callable.",
        jsonSchema = DISCOVER_TOOLS_SCHEMA,
        mode = ToolMode.ENABLED,
        // Meta-tool: handled by the engine (ToolDiscovery), never executed via this provider — the
        // field is only for display, so any built-in provider works.
        provider = providers.first { it.kind == ProviderKind.BUILTIN },
        bareName = DISCOVER_TOOLS_NAME,
    )

    companion object {
        const val DISCOVER_TOOLS_NAME = "discover_tools"

        private val DISCOVER_TOOLS_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "One of: list_providers, list_tools, search_tools")
                }
                putJsonObject("provider") {
                    put("type", "string")
                    put("description", "Optional provider id/name to scope list_tools")
                }
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "Optional keyword for search_tools")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("action"))))
        }
    }
}
