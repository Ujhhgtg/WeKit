package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import kotlinx.coroutines.launch

/**
 * Lists the built-in tool providers (builtin-wechat / builtin-wechat-sql / builtin-fs). Each drills
 * into a per-provider [ToolPermissionListScreen]. Pinned & undeletable.
 */
@Composable
fun BuiltinProvidersScreen(onBack: () -> Unit, onOpenProvider: (providerId: String) -> Unit) {
    AgentSettingsScaffold(title = stringResource(R.string.agent_builtin_tools_title), onBack = onBack) {
        item {
            SegmentedColumn {
                BuiltinToolProvider.all.forEach { p ->
                    item(key = p.id) {
                        val displayName = builtinProviderDisplayName(p.id, p.name)
                        BaseWidget(
                            iconPlaceholder = false,
                            title = displayName,
                            description = stringResource(R.string.agent_tool_count_summary, p.id, p.seedInfos().size),
                            onClick = { onOpenProvider(p.id) },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
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
        if (tools.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_mcp_tools_title),
                    message = stringResource(R.string.agent_no_provider_tools),
                )
            }
        } else {
            lazySegmentedItems(tools, key = { "${providerId}_${it.first}" }) { (name, default) ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DropDownMenuWidget(
                        icon = null,
                        iconPlaceholder = false,
                        title = name,
                        description = null,
                        value = permMap[providerId to name] ?: default,
                        options = MODE_ORDER.map { DropdownOption(it, it.toolModeLabel()) },
                        onValueChange = { newMode ->
                            scope.launch { WeAgentRepository.setToolMode(providerId, name, newMode) }
                        },
                    )
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

internal val MODE_ORDER = listOf(ToolMode.ENABLED, ToolMode.MANUAL_APPROVAL, ToolMode.SMART_APPROVAL, ToolMode.DISABLED)

@Composable
internal fun ToolMode.toolModeLabel(): String = stringResource(when (this) {
    ToolMode.ENABLED -> R.string.agent_tool_mode_enabled
    ToolMode.MANUAL_APPROVAL -> R.string.agent_tool_mode_manual_approval
    ToolMode.SMART_APPROVAL -> R.string.agent_tool_mode_smart_approval
    ToolMode.DISABLED -> R.string.agent_tool_mode_disabled
})
