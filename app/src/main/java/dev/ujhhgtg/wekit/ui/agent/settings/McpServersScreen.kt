package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import dev.ujhhgtg.wekit.R
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

/** Lists MCP servers (row → detail) and adds new ones via the detail screen's creation mode (§4). */
@Composable
fun McpServersScreen(onBack: () -> Unit, onOpenServer: (serverId: String) -> Unit) {
    val allProviders by WeAgentRepository.observeProviders().collectAsState(initial = emptyList())
    val servers = allProviders.filter { it.kind == ProviderKind.MCP }
    val liveProviders by McpClientManager.providers.collectAsState()

    AgentSettingsScaffold(title = stringResource(R.string.agent_mcp_servers_title), onBack = onBack) {
        if (servers.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_mcp_title),
                    message = stringResource(R.string.agent_empty_mcp_message),
                    actionLabel = stringResource(R.string.agent_add_server),
                    onAction = { onOpenServer("") },
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
                        onClick = { onOpenServer("") },
                    )
                }
            }
        }
    }
}

/**
 * MCP server detail: refresh/status, delete (moved here from the list), and a per-tool permission
 * list like the built-in providers (§4). Tools come from the live connected provider, if any.
 * A blank [serverId] starts a new server as an in-memory draft: status/tools/delete are hidden,
 * 保存 persists it and switches the screen to edit mode in place, and leaving with a savable
 * draft asks for confirmation first.
 */
@Composable
fun McpServerDetailScreen(serverId: String, onBack: () -> Unit) {
    val creating = serverId.isBlank()
    val allProviders by WeAgentRepository.observeProviders().collectAsState(initial = emptyList())
    // Draft shown while creating; once saved, [savedId] repoints the screen at the Room entity.
    var draft by remember {
        mutableStateOf(ProviderEntity("", ProviderKind.MCP, "", McpTransport.STREAMABLE_HTTP, "", null, true))
    }
    // Saveable so that re-entering this entry after the in-place save reloads the saved server.
    var savedId by rememberSaveable { mutableStateOf("") }
    // True once the entity exists in Room: loaded for edit, or assigned by 保存 during creation.
    // The route id stays blank after in-place creation, so this — not `creating` — gates edit mode.
    val editing = !creating || savedId.isNotBlank()
    val activeId = if (creating) savedId else serverId
    val server = allProviders.firstOrNull { it.id == activeId }
    val srv = server ?: draft.takeIf { creating && !editing }
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val perms by WeAgentRepository.observeToolPermissions().collectAsState(initial = emptyList())
    val permMap = perms.associate { it.providerId to it.toolName to it.mode }

    val liveProviders by McpClientManager.providers.collectAsState()
    val status = rememberMcpStatus(liveProviders.firstOrNull { it.id == activeId })
    val tools = status.tools

    /**
     * Connection-parameter edits of an existing server rebuild the live client; a rename is
     * display-only. Creation-mode edits only touch the draft until 保存.
     */
    fun commitServer(rebuild: Boolean, transform: (ProviderEntity) -> ProviderEntity) {
        if (!editing) {
            draft = transform(draft)
            return
        }
        val current = server ?: return
        scope.launch {
            WeAgentRepository.upsertMcpProvider(transform(current))
            if (rebuild) McpClientManager.reload(activeId)
        }
    }

    val savable = srv?.endpointUrl?.isNotBlank() == true
    val guardedBack = rememberCreationBackGuard(!editing && savable, onBack)

    AgentSettingsScaffold(
        title = if (!editing) stringResource(R.string.agent_add_mcp_server)
        else server?.name ?: stringResource(R.string.agent_mcp_servers_title),
        onBack = guardedBack,
    ) {
        srv?.let { s ->
            item {
                SegmentedColumn(title = stringResource(R.string.agent_section_connection)) {
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_field_name),
                            value = s.name,
                            onValueChange = { value -> commitServer(rebuild = false) { it.copy(name = value) } },
                            dialogTitle = stringResource(R.string.agent_field_name),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_server_url),
                            value = s.endpointUrl.orEmpty(),
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
                            value = s.transport ?: McpTransport.STREAMABLE_HTTP,
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
                            value = s.headersJson.orEmpty(),
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

        if (!editing) {
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.action_save),
                        icon = MaterialSymbols.Outlined.Save,
                        enabled = savable,
                        onClick = {
                            val current = srv!!
                            scope.launch {
                                val id = UUID.randomUUID().toString()
                                // A blank name falls back to the URL, like the old add dialog did.
                                WeAgentRepository.upsertMcpProvider(
                                    current.copy(id = id, name = current.name.ifBlank { current.endpointUrl!! }),
                                )
                                // Stay in place: savedId switches the screen to edit mode and
                                // reveals status/tools as the Room flow emits the new row.
                                savedId = id
                            }
                        },
                    )
                }
            }
        } else {
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
                            onClick = { scope.launch { McpClientManager.refreshTools(activeId) } },
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
                lazySegmentedItems(tools, key = { "${activeId}_${it.name}" }) { t ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        DropDownMenuWidget(
                            icon = null,
                            iconPlaceholder = false,
                            title = t.name,
                            description = null,
                            value = permMap[activeId to t.name] ?: t.factoryDefaultMode,
                            options = MODE_ORDER.map { DropdownOption(it, it.toolModeLabel()) },
                            onValueChange = { newMode ->
                                scope.launch { WeAgentRepository.setToolMode(activeId, t.name, newMode) }
                            },
                        )
                    }
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
                    WeAgentRepository.deleteMcpProvider(activeId)
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
