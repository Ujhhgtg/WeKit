package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitBasicDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.McpTransport
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.mcp.McpClientManager
import dev.ujhhgtg.wekit.agent.mcp.McpProviderStatus
import dev.ujhhgtg.wekit.agent.mcp.McpToolProvider
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Observes one live provider's connection state / last error / tool list.
 *
 * [McpToolProvider] publishes these as a [kotlinx.coroutines.flow.StateFlow] because it mutates them
 * from its connect/refresh coroutines; collecting here is what makes both the list row and the
 * detail page update the moment a refresh or reconnect lands instead of only on re-entry. [provider]
 * is null while the server has no live client yet, which reads as DISCONNECTED.
 */
@Composable
private fun rememberMcpStatus(provider: McpToolProvider?): McpProviderStatus =
    produceState(McpProviderStatus(), provider) {
        val p = provider
        if (p == null) {
            value = McpProviderStatus()
            return@produceState
        }
        p.status.collect { value = it }
    }.value

/** Lists MCP servers (row → detail; no inline delete) and adds new ones (§4). */
@Composable
fun McpServersScreen(onBack: () -> Unit, onOpenServer: (serverId: String) -> Unit) {
    val allProviders by WeAgentRepository.observeProviders().collectAsState(initial = emptyList())
    val servers = allProviders.filter { it.kind == ProviderKind.MCP }
    val liveProviders by McpClientManager.providers.collectAsState()
    val scope = rememberCoroutineScope()
    val showAdd = remember { mutableStateOf(false) }

    AgentSettingsScaffold(title = stringResource(R.string.agent_mcp_servers_title), onBack = onBack) {
        if (servers.isEmpty()) item { EmptyHint(stringResource(R.string.agent_mcp_servers_empty)) }
        items(servers.size, key = { servers[it].id }) { i ->
            val s = servers[i]
            val status = rememberMcpStatus(liveProviders.firstOrNull { it.id == s.id })
            SegmentedColumn {
                item {
                    BaseWidget(
                        title = s.name.ifBlank { s.endpointUrl ?: s.id },
                        description = status.lastError?.let {
                            stringResource(
                                R.string.agent_mcp_server_summary_error,
                                s.transport?.name ?: "?",
                                mcpStateLabel(status.state),
                                it,
                            )
                        } ?: stringResource(
                            R.string.agent_mcp_server_summary,
                            s.transport?.name ?: "?",
                            mcpStateLabel(status.state),
                        ),
                        onClick = { onOpenServer(s.id) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
        item {
            Button(
                onClick = { showAdd.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text(stringResource(R.string.agent_add_server)) }
        }
    }

    AddMcpDialog(showAdd) { name, transport, url, headersJson ->
        scope.launch {
            WeAgentRepository.upsertMcpProvider(
                ProviderEntity(
                    id = UUID.randomUUID().toString(),
                    kind = ProviderKind.MCP,
                    name = name.ifBlank { url },
                    transport = transport,
                    endpointUrl = url,
                    headersJson = headersJson.ifBlank { null },
                    enabled = true,
                )
            )
        }
    }
}

/**
 * MCP server detail: refresh/status, delete (moved here from the list), and a per-tool permission
 * list like the built-in providers (§4). Tools come from the live connected provider, if any.
 */
@Composable
fun McpServerDetailScreen(serverId: String, onBack: () -> Unit) {
    val allProviders by WeAgentRepository.observeProviders().collectAsState(initial = emptyList())
    val server = allProviders.firstOrNull { it.id == serverId }
    val scope = rememberCoroutineScope()
    val perms by WeAgentRepository.observeToolPermissions().collectAsState(initial = emptyList())
    val permMap = perms.associate { it.providerId to it.toolName to it.mode }

    val liveProviders by McpClientManager.providers.collectAsState()
    val status = rememberMcpStatus(liveProviders.firstOrNull { it.id == serverId })
    val tools = status.tools

    AgentSettingsScaffold(title = server?.name ?: stringResource(R.string.agent_mcp_servers_title), onBack = onBack) {
        item {
            SegmentedColumn {
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_connection_status),
                        description = stringResource(
                            R.string.agent_connection_summary,
                            status.lastError?.let {
                                stringResource(R.string.agent_mcp_status_error, mcpStateLabel(status.state), it)
                            } ?: mcpStateLabel(status.state),
                        ),
                        onClick = { scope.launch { McpClientManager.refreshTools(serverId) } },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                server?.let { srv ->
                    item {
                        BaseWidget(
                            title = stringResource(R.string.agent_address),
                            description = stringResource(
                                R.string.agent_mcp_address_summary,
                                srv.transport?.name ?: "?",
                                srv.endpointUrl.orEmpty(),
                            ),
                            onClick = {},
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
                item(topPadding = 12.dp) {
                    TextButton(
                        onClick = { scope.launch { WeAgentRepository.deleteMcpProvider(serverId) }; onBack() },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.agent_delete_server)) }
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.agent_tool_permissions_title)) {
                if (tools.isEmpty()) item { EmptyHint(stringResource(R.string.agent_mcp_tools_empty)) }
                tools.forEach { t ->
                    item(key = "${serverId}_${t.name}") {
                        val mode = permMap[serverId to t.name] ?: t.factoryDefaultMode
                        McpToolModeDropdown(t.name, mode) { newMode ->
                            scope.launch { WeAgentRepository.setToolMode(serverId, t.name, newMode) }
                        }
                    }
                }
            }
        }
    }
}

private val MCP_MODE_ORDER = listOf(
    dev.ujhhgtg.wekit.agent.tool.ToolMode.ENABLED,
    dev.ujhhgtg.wekit.agent.tool.ToolMode.MANUAL_APPROVAL,
    dev.ujhhgtg.wekit.agent.tool.ToolMode.SMART_APPROVAL,
    dev.ujhhgtg.wekit.agent.tool.ToolMode.DISABLED,
)

@Composable
private fun dev.ujhhgtg.wekit.agent.tool.ToolMode.mcpLabel(): String = stringResource(
    when (this) {
        dev.ujhhgtg.wekit.agent.tool.ToolMode.ENABLED -> R.string.agent_tool_mode_enabled
        dev.ujhhgtg.wekit.agent.tool.ToolMode.MANUAL_APPROVAL -> R.string.agent_tool_mode_manual_approval
        dev.ujhhgtg.wekit.agent.tool.ToolMode.SMART_APPROVAL -> R.string.agent_tool_mode_smart_approval
        dev.ujhhgtg.wekit.agent.tool.ToolMode.DISABLED -> R.string.agent_tool_mode_disabled
    }
)

@Composable
private fun mcpStateLabel(state: dev.ujhhgtg.wekit.agent.mcp.McpConnectionState): String = stringResource(
    when (state) {
        dev.ujhhgtg.wekit.agent.mcp.McpConnectionState.DISCONNECTED -> R.string.agent_mcp_state_disconnected
        dev.ujhhgtg.wekit.agent.mcp.McpConnectionState.CONNECTING -> R.string.agent_mcp_state_connecting
        dev.ujhhgtg.wekit.agent.mcp.McpConnectionState.CONNECTED -> R.string.agent_mcp_state_connected
        dev.ujhhgtg.wekit.agent.mcp.McpConnectionState.FAILED -> R.string.agent_mcp_state_failed
    }
)

@Composable
private fun McpToolModeDropdown(name: String, mode: dev.ujhhgtg.wekit.agent.tool.ToolMode, onChange: (dev.ujhhgtg.wekit.agent.tool.ToolMode) -> Unit) {
    AgentDropdownRow(
        title = name,
        items = MCP_MODE_ORDER.map { it.mcpLabel() },
        selectedIndex = MCP_MODE_ORDER.indexOf(mode).coerceAtLeast(0),
        onSelectedIndexChange = { onChange(MCP_MODE_ORDER[it]) },
    )
}

@Composable
private fun AddMcpDialog(
    show: MutableState<Boolean>,
    onConfirm: (name: String, transport: McpTransport, url: String, headersJson: String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    var url by remember(show.value) { mutableStateOf("") }
    var headers by remember(show.value) { mutableStateOf("") }
    var transportIndex by remember(show.value) { mutableIntStateOf(0) }
    val transports = listOf(McpTransport.STREAMABLE_HTTP, McpTransport.SSE)

    WeKitBasicDialog(show = show.value, title = stringResource(R.string.agent_add_mcp_server), onDismissRequest = { show.value = false }) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.agent_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.agent_server_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AgentDropdownRow(
                title = stringResource(R.string.agent_transport),
                items = listOf("Streamable HTTP", "SSE"),
                selectedIndex = transportIndex,
                onSelectedIndexChange = { transportIndex = it },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = headers,
                onValueChange = { headers = it },
                label = { Text(stringResource(R.string.agent_custom_headers_json)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { show.value = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { onConfirm(name, transports[transportIndex], url, headers); show.value = false },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_add)) }
            }
        }
    }
}
