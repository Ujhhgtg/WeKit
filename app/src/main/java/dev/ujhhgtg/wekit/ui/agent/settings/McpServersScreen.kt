package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Refresh
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitBasicDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.McpTransport
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.mcp.McpClientManager
import dev.ujhhgtg.wekit.agent.mcp.McpConnectionState
import dev.ujhhgtg.wekit.agent.mcp.McpProviderStatus
import dev.ujhhgtg.wekit.agent.mcp.McpToolProvider
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.utils.android.showToast
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
        if (servers.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_mcp_title),
                    message = stringResource(R.string.agent_empty_mcp_message),
                    actionLabel = stringResource(R.string.agent_add_server),
                    onAction = { showAdd.value = true },
                )
            }
        } else {
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
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_add_server),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = { showAdd.value = true },
                    )
                }
            }
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
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val perms by WeAgentRepository.observeToolPermissions().collectAsState(initial = emptyList())
    val permMap = perms.associate { it.providerId to it.toolName to it.mode }

    val liveProviders by McpClientManager.providers.collectAsState()
    val status = rememberMcpStatus(liveProviders.firstOrNull { it.id == serverId })
    val tools = status.tools

    /** Connection-parameter edits rebuild the live client; a rename is display-only. */
    fun commitServer(rebuild: Boolean, transform: (ProviderEntity) -> ProviderEntity) {
        val current = server ?: return
        scope.launch {
            WeAgentRepository.upsertMcpProvider(transform(current))
            if (rebuild) McpClientManager.reload(serverId)
        }
    }

    AgentSettingsScaffold(title = server?.name ?: stringResource(R.string.agent_mcp_servers_title), onBack = onBack) {
        server?.let { srv ->
            item {
                SegmentedColumn(title = stringResource(R.string.agent_section_connection)) {
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_field_name),
                            value = srv.name,
                            onValueChange = { value -> commitServer(rebuild = false) { it.copy(name = value) } },
                            dialogTitle = stringResource(R.string.agent_field_name),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_server_url),
                            value = srv.endpointUrl.orEmpty(),
                            onValueChange = { value -> commitServer(rebuild = true) { it.copy(endpointUrl = value) } },
                            dialogTitle = stringResource(R.string.agent_server_url),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                    item {
                        DropDownMenuWidget(
                            icon = null,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_transport),
                            description = null,
                            value = srv.transport ?: McpTransport.STREAMABLE_HTTP,
                            options = listOf(
                                DropdownOption(McpTransport.STREAMABLE_HTTP, "Streamable HTTP"),
                                DropdownOption(McpTransport.SSE, "SSE"),
                            ),
                            onValueChange = { value -> commitServer(rebuild = true) { it.copy(transport = value) } },
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_custom_headers_json),
                            value = srv.headersJson.orEmpty(),
                            onValueChange = { value ->
                                commitServer(rebuild = true) { it.copy(headersJson = value.ifBlank { null }) }
                            },
                            dialogTitle = stringResource(R.string.agent_custom_headers_json),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            singleLine = false,
                        )
                    }
                }
            }
        }

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
                        trailingContent = { Icon(MaterialSymbols.Outlined.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }

        item {
            AgentActionRow {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            }
        }

        if (tools.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_mcp_tools_title),
                    message = stringResource(R.string.agent_empty_mcp_tools_message),
                )
            }
        } else {
            item { McpSectionTitle(stringResource(R.string.agent_tool_permissions_title)) }
            lazySegmentedItems(tools, key = { "${serverId}_${it.name}" }) { t ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DropDownMenuWidget(
                        icon = null,
                        iconPlaceholder = false,
                        title = t.name,
                        description = null,
                        value = permMap[serverId to t.name] ?: t.factoryDefaultMode,
                        options = MODE_ORDER.map { DropdownOption(it, it.toolModeLabel()) },
                        onValueChange = { newMode ->
                            scope.launch { WeAgentRepository.setToolMode(serverId, t.name, newMode) }
                        },
                    )
                }
            }
        }
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.agent_delete_server),
        message = stringResource(R.string.agent_delete_server_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            scope.launch {
                try {
                    WeAgentRepository.deleteMcpProvider(serverId)
                    onBack()
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

/** Mirrors [SegmentedColumn]'s section title styling for sections whose rows are laid out lazily. */
@Composable
private fun McpSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 16.dp),
    )
}

@Composable
private fun mcpStateLabel(state: McpConnectionState): String = stringResource(
    when (state) {
        McpConnectionState.DISCONNECTED -> R.string.agent_mcp_state_disconnected
        McpConnectionState.CONNECTING -> R.string.agent_mcp_state_connecting
        McpConnectionState.CONNECTED -> R.string.agent_mcp_state_connected
        McpConnectionState.FAILED -> R.string.agent_mcp_state_failed
    }
)

@Composable
private fun AddMcpDialog(
    show: MutableState<Boolean>,
    onConfirm: (name: String, transport: McpTransport, url: String, headersJson: String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    var url by remember(show.value) { mutableStateOf("") }
    var headers by remember(show.value) { mutableStateOf("") }
    var transport by remember(show.value) { mutableStateOf(McpTransport.STREAMABLE_HTTP) }

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
            DropDownMenuWidget(
                icon = null,
                iconPlaceholder = false,
                title = stringResource(R.string.agent_transport),
                description = null,
                value = transport,
                options = listOf(
                    DropdownOption(McpTransport.STREAMABLE_HTTP, "Streamable HTTP"),
                    DropdownOption(McpTransport.SSE, "SSE"),
                ),
                onValueChange = { transport = it },
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
                    onClick = { onConfirm(name, transport, url, headers); show.value = false },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_add)) }
            }
        }
    }
}
