package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * Lists the built-in tool providers (builtin-wechat / builtin-wechat-sql / builtin-fs). Each drills
 * into a per-provider [ToolPermissionListScreen]. Pinned & undeletable.
 */
@Composable
fun BuiltinProvidersScreen(onBack: () -> Unit, onOpenProvider: (providerId: String) -> Unit) {
    AgentSettingsScaffold(title = stringResource(R.string.agent_builtin_tools_title), onBack = onBack) {
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                BuiltinToolProvider.all.forEach { p ->
                    val displayName = builtinProviderDisplayName(p.id, p.name)
                    ArrowPreference(
                        title = displayName,
                        summary = stringResource(R.string.agent_tool_count_summary, p.id, p.seedInfos().size),
                        onClick = { onOpenProvider(p.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun builtinProviderDisplayName(providerId: String, fallback: String = providerId): String =
    when (providerId) {
        BuiltinToolProvider.WECHAT_ID -> stringResource(R.string.agent_builtin_provider_wechat)
        BuiltinToolProvider.WECHAT_SQL_ID -> stringResource(R.string.agent_builtin_provider_wechat_sql)
        BuiltinToolProvider.FS_ID -> stringResource(R.string.agent_builtin_provider_files)
        BuiltinToolProvider.JVM_ID -> stringResource(R.string.agent_builtin_provider_jvm)
        BuiltinToolProvider.UI_ID -> stringResource(R.string.agent_builtin_provider_ui)
        BuiltinToolProvider.WEBVIEW_ID -> stringResource(R.string.agent_builtin_provider_webview)
        BuiltinToolProvider.TRIGGER_ID -> stringResource(R.string.agent_builtin_provider_triggers)
        BuiltinToolProvider.INFO_ID -> stringResource(R.string.agent_builtin_provider_environment)
        BuiltinToolProvider.NET_ID -> stringResource(R.string.agent_builtin_provider_network)
        else -> fallback
    }

/**
 * Per-provider four-state permission editor (§3.2), reused for both a built-in provider and an MCP
 * server. [tools] are the provider's advertised tools (name + factory default). Changes persist
 * immediately via [WeAgentRepository.setToolMode] and take effect on the next request.
 */
@Composable
fun ToolPermissionListScreen(
    title: String,
    providerId: String,
    tools: List<Pair<String, ToolMode>>,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val perms by WeAgentRepository.observeToolPermissions().collectAsState(initial = emptyList())
    val permMap = perms.associate { (it.providerId to it.toolName) to it.mode }

    AgentSettingsScaffold(title = title, onBack = onBack) {
        if (tools.isEmpty()) item { EmptyHint(stringResource(R.string.agent_no_provider_tools)) }
        items(tools.size, key = { "${providerId}_${tools[it].first}" }) { i ->
            val (name, default) = tools[i]
            val mode = permMap[providerId to name] ?: default
            Card(Modifier.padding(bottom = 6.dp)) {
                ToolModeDropdown(name, mode) { newMode ->
                    scope.launch { WeAgentRepository.setToolMode(providerId, name, newMode) }
                }
            }
        }
    }
}

/** Convenience: builds the (name, factoryDefault) list for a built-in provider by id. */
fun builtinProviderTools(providerId: String): List<Pair<String, ToolMode>> =
    BuiltinToolProvider.all.firstOrNull { it.id == providerId }
        ?.seedInfos()?.map { it.name to it.defaultMode }
        ?: emptyList()

/** Convenience: builds the (name, factoryDefault) list from a set of [ProviderTool]s (MCP). */
fun providerToolPairs(tools: List<ProviderTool>): List<Pair<String, ToolMode>> =
    tools.map { it.name to it.factoryDefaultMode }

private val MODE_ORDER = listOf(ToolMode.ENABLED, ToolMode.MANUAL_APPROVAL, ToolMode.SMART_APPROVAL, ToolMode.DISABLED)

@Composable
private fun ToolMode.label(): String = stringResource(when (this) {
    ToolMode.ENABLED -> R.string.agent_tool_mode_enabled
    ToolMode.MANUAL_APPROVAL -> R.string.agent_tool_mode_manual_approval
    ToolMode.SMART_APPROVAL -> R.string.agent_tool_mode_smart_approval
    ToolMode.DISABLED -> R.string.agent_tool_mode_disabled
})

@Composable
private fun ToolModeDropdown(name: String, mode: ToolMode, onChange: (ToolMode) -> Unit) {
    WindowDropdownPreference(
        title = name,
        items = MODE_ORDER.map { it.label() },
        selectedIndex = MODE_ORDER.indexOf(mode).coerceAtLeast(0),
        onSelectedIndexChange = { onChange(MODE_ORDER[it]) },
    )
}
