package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.agent.tool.ProviderTool
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems

/**
 * Lists the built-in tool providers. Each drills into a per-provider [ToolListScreen]. Pinned &
 * undeletable.
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
                            description = stringResource(R.string.agent_tool_count_summary, p.id, p.listTools().size),
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
 * Read-only tool list for one provider (built-in or MCP), showing each tool's description.
 * Tools have no per-tool permission anymore — approval is governed by the session permission
 * level (§3.1); this screen only lets the user inspect what a provider offers.
 */
@Composable
fun ToolListScreen(
    title: String,
    tools: List<ProviderTool>,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = title, onBack = onBack) {
        if (tools.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_mcp_tools_title),
                    message = stringResource(R.string.agent_no_provider_tools),
                )
            }
        } else {
            lazySegmentedItems(tools, key = { it.name }) { tool ->
                BaseWidget(
                    iconPlaceholder = false,
                    title = tool.name,
                    description = tool.description.ifBlank { null },
                )
            }
        }
    }
}

/** Convenience: builds the tool list for a built-in provider by id. */
fun builtinProviderTools(providerId: String): List<ProviderTool> =
    BuiltinToolProvider.all.firstOrNull { it.id == providerId }?.listTools() ?: emptyList()
