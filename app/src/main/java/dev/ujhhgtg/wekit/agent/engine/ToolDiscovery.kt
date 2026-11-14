package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import dev.ujhhgtg.wekit.agent.tool.ToolVisibility
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles the `discover_tools` meta-tool (§3.3) for dynamic tool loading. Its results carry each
 * tool's full JSON schema, and calling `list_tools`/`search_tools` adds the returned tools to the
 * turn's [discovered] set so they become callable on subsequent requests.
 */
object ToolDiscovery {
    fun handle(
        registry: ToolRegistry,
        args: JsonObject,
        discovered: MutableSet<String>,
        visibility: ToolVisibility,
    ): String {
        val action = args["action"]?.jsonPrimitive?.content ?: return "Error: missing 'action'"
        val providerFilter = args["provider"]?.jsonPrimitive?.content
        val keyword = args["keyword"]?.jsonPrimitive?.content

        return when (action) {
            "list_providers" -> {
                registry
                    .allProviders()
                    .filter { it.isAvailable }
                    .joinToString("\n") { "provider=${it.id} (${it.name}), kind=${it.kind}" }
                    .ifEmpty { "No providers." }
            }

            "list_tools" -> {
                val tools =
                    registry.resolveVisibleTools(visibility).filter {
                        providerFilter == null ||
                            it.provider.id == providerFilter ||
                            it.provider.name == providerFilter
                    }
                tools.forEach { discovered += it.exposedName }
                renderTools(tools)
            }

            "search_tools" -> {
                // A blank keyword would make `contains("")` match everything, dumping the whole
                // catalog (every JSON schema) into context — exactly what DYNAMIC loading exists to
                // avoid. Require a real keyword and point the model at list_tools if it wants all.
                val kw = keyword?.trim()?.lowercase()
                if (kw.isNullOrEmpty()) {
                    return "Error: 'keyword' is required for search_tools and must not be blank. " +
                        "Use action=list_tools (optionally with a 'provider') if you really want the full catalog."
                }
                val tools =
                    registry.resolveVisibleTools(visibility).filter {
                        it.exposedName.lowercase().contains(kw) ||
                            it.description.lowercase().contains(kw)
                    }
                tools.forEach { discovered += it.exposedName }
                renderTools(tools)
            }

            else -> {
                "Error: unknown action '$action'. Use list_providers | list_tools | search_tools."
            }
        }
    }

    private fun renderTools(tools: List<dev.ujhhgtg.wekit.agent.tool.WireTool>): String {
        if (tools.isEmpty()) return "No matching tools."
        return tools.joinToString("\n") { t ->
            "provider=${t.provider.id}, name=${t.exposedName}, description=${t.description}, parameters=${t.jsonSchema}"
        }
    }
}
