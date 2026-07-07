package dev.ujhhgtg.wekit.agent.tool

import dev.ujhhgtg.wekit.features.core.AgentTool
import kotlinx.serialization.json.JsonObject

/**
 * The built-in tool providers (§3.4), split by the `@AgentTool(group=…)` tag into three fixed
 * providers so the settings UI can present them separately and permissions are stored per provider:
 *
 *  - `builtin-wechat`      — WeChat operations (send/read/group/moments/…)
 *  - `builtin-wechat-sql`  — raw database SQL (query / execute)
 *  - `builtin-fs`          — workspace/memory file tools + `load_skill`
 *
 * All are always available and pinned/undeletable in settings. Within `builtin-fs`, the file tools
 * ([FS_TOOL_NAMES]) are hidden from the model unless a workspace or memory is enabled ([fsToolsVisible]);
 * `load_skill` stays visible regardless (skills are their own dynamic-discovery mechanism).
 */
class BuiltinToolProvider(
    override val id: String,
    override val name: String,
    private val descriptors: List<AgentToolDescriptor>,
) : ToolProvider {

    override val kind: ProviderKind = ProviderKind.BUILTIN
    override val isAvailable: Boolean = true

    private val byName: Map<String, AgentToolDescriptor> = descriptors.associateBy { it.name }

    /** Tool name + factory-default mode, for permission seeding (includes hidden fs tools). */
    fun seedInfos(): List<BuiltinToolInfo> =
        descriptors.map { BuiltinToolInfo(it.name, ToolMode.defaultFor(it.sideEffect)) }

    override fun listTools(): List<ProviderTool> =
        descriptors
            .filter { fsToolsVisible || it.name !in FS_TOOL_NAMES }
            .filter { visionToolsVisible || it.name !in VISION_TOOL_NAMES }
            .map { d ->
                ProviderTool(
                    name = d.name,
                    description = d.description,
                    jsonSchema = d.buildJsonSchema(),
                    factoryDefaultMode = ToolMode.defaultFor(d.sideEffect),
                )
            }

    override suspend fun execute(toolName: String, arguments: JsonObject): String {
        val descriptor = byName[toolName] ?: return "Unknown builtin tool: $toolName"
        return try {
            descriptor.invoker(AgentToolArgs(arguments))
        } catch (e: AgentToolArgs.AgentToolArgException) {
            "Invalid arguments for '$toolName': ${e.message}"
        } catch (e: Throwable) {
            "Tool '$toolName' failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    data class BuiltinToolInfo(val name: String, val defaultMode: ToolMode)

    companion object {
        const val WECHAT_ID = AgentTool.BUILTIN_WECHAT
        const val WECHAT_SQL_ID = AgentTool.BUILTIN_WECHAT_SQL
        const val FS_ID = AgentTool.BUILTIN_FS
        const val JVM_ID = AgentTool.BUILTIN_JVM
        const val UI_ID = AgentTool.BUILTIN_UI
        const val WEBVIEW_ID = AgentTool.BUILTIN_WEBVIEW
        const val TRIGGER_ID = AgentTool.BUILTIN_TRIGGER

        private val DISPLAY_NAMES = mapOf(
            WECHAT_ID to "微信操作",
            WECHAT_SQL_ID to "数据库 SQL",
            FS_ID to "文件与技能",
            JVM_ID to "JVM 反射",
            UI_ID to "界面工具",
            WEBVIEW_ID to "WebView",
            TRIGGER_ID to "触发器",
        )

        /**
         * File-tool names within `builtin-fs`, hidden from the model unless a workspace or memory is
         * enabled. Kept as a name set so gating never touches permission seeding (rows are still
         * seeded; the tools are simply not advertised while disabled). `load_skill` is NOT here — it
         * is always visible.
         */
        val FS_TOOL_NAMES = setOf(
            "read_file", "list_dir", "search_files",
            "write_file", "append_file", "delete_file", "move_file",
        )

        /** Set by WeAgentService from settings: true when workspace OR memory is enabled. */
        @Volatile
        var fsToolsVisible: Boolean = false

        /**
         * Set by WeAgentService per-turn from the session model's `supportsVision` flag.
         * `ui-screenshot` is only advertised to the model when true — sending images to a
         * non-vision model would error at the provider.
         */
        @Volatile
        var visionToolsVisible: Boolean = false

        /** Screenshot tool name — hidden unless the session model supports vision. */
        val VISION_TOOL_NAMES = setOf("ui-screenshot")

        /** All built-in providers, one per `@AgentTool` group present in the generated registry. */
        val all: List<BuiltinToolProvider> by lazy {
            AgentToolsProvider.ALL_TOOLS
                .groupBy { it.group }
                .toSortedMap()
                .map { (group, tools) ->
                    BuiltinToolProvider(
                        id = group,
                        name = DISPLAY_NAMES[group] ?: group,
                        descriptors = tools,
                    )
                }
        }

        /** All built-in provider ids (for the settings list). */
        val allIds: List<String> get() = all.map { it.id }
    }
}
