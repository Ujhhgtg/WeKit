package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.agent.AgentSettingsRoute
import dev.ujhhgtg.wekit.agent.data.OverlayMode
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.features.items.system.agent.WeAgentOverlayController
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import kotlinx.coroutines.launch

/**
 * WeAgent settings home.
 */
@Composable
fun WeAgentHomeScreen(onOpen: (AgentSettingsRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val overlayModeLabels = mapOf(
        OverlayMode.DISABLED to stringResource(R.string.agent_overlay_mode_disabled),
        OverlayMode.FOREGROUND_ONLY to stringResource(R.string.agent_overlay_mode_foreground_only),
        OverlayMode.ALWAYS to stringResource(R.string.agent_overlay_mode_always),
    )

    var loaded by remember { mutableStateOf(false) }
    var dynamicTools by remember { mutableStateOf(false) }
    var overlayMode by remember { mutableStateOf(OverlayMode.ALWAYS) }
    var sendWhileRunning by remember { mutableStateOf("QUEUE_AFTER_TURN") }
    var smallModelId by remember { mutableStateOf<String?>(null) }
    var defaultModelId by remember { mutableStateOf<String?>(null) }
    var defaultSystemPromptId by remember { mutableStateOf<String?>(null) }
    var defaultWorkspaceId by remember { mutableStateOf<String?>(null) }

    // These must come from the live DB flows, not a one-shot read: a model/prompt/workspace added
    // on a child screen has to show up in these dropdowns as soon as the user comes back, no
    // matter how the nav host composes covered entries.
    val models by remember { WeAgentRepository.observeModels() }.collectAsState(initial = emptyList())
    val systemPrompts by remember { WeAgentRepository.observeSystemPrompts() }.collectAsState(initial = emptyList())
    val workspaces by remember { WeAgentRepository.observeWorkspaces() }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        dynamicTools = WeAgentSettings.toolLoadingMode() == dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode.DYNAMIC
        overlayMode = WeAgentSettings.overlayMode()
        sendWhileRunning = WeAgentSettings.sendWhileRunningMode().name
        smallModelId = WeAgentSettings.smallModelId()
        defaultModelId = WeAgentSettings.defaultModelId()
        defaultSystemPromptId = WeAgentSettings.defaultSystemPromptId()
        defaultWorkspaceId = WeAgentSettings.defaultWorkspaceId()
        loaded = true
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_settings_title), onBack = null) {
        // ---------- 界面 ----------
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_interface)) {
                if (loaded) {
                    item {
                        AgentDropdownRow(
                            title = stringResource(R.string.agent_overlay_mode_title),
                            items = OverlayMode.entries.map(overlayModeLabels::getValue),
                            selectedIndex = OverlayMode.entries.indexOf(overlayMode),
                            onSelectedIndexChange = {
                                val mode = OverlayMode.entries[it]
                                overlayMode = mode
                                WeAgentOverlayController.setMode(mode)
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_OVERLAY_MODE, mode.name) }
                            },
                            summary = stringResource(R.string.agent_overlay_mode_summary),
                        )
                    }
                }
            }
        }

        // ---------- 模型 ----------
        item {
            SegmentedColumn(title = stringResource(R.string.agent_section_models)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_model_providers_title),
                        description = stringResource(R.string.agent_model_providers_summary),
                        onClick = { onOpen(AgentSettingsRoute.ModelProviders) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                if (loaded) {
                    item {
                        ModelDropdown(
                            title = stringResource(R.string.agent_small_model_title),
                            models = models,
                            selectedId = smallModelId,
                            noneLabel = stringResource(R.string.agent_same_as_primary_model),
                        ) { id ->
                            smallModelId = id
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SMALL_MODEL_ID, id.orEmpty()) }
                        }
                    }
                    item {
                        AgentDropdownRow(
                            title = stringResource(R.string.agent_send_while_running_title),
                            items = listOf(
                                stringResource(R.string.agent_send_queue_after_turn),
                                stringResource(R.string.agent_send_steer_next_request),
                            ),
                            selectedIndex = if (sendWhileRunning == "QUEUE_AS_STEER") 1 else 0,
                            onSelectedIndexChange = {
                                val mode = if (it == 1) "QUEUE_AS_STEER" else "QUEUE_AFTER_TURN"
                                sendWhileRunning = mode
                                WeAgentService.sendWhileRunningMode.value =
                                    if (mode == "QUEUE_AS_STEER") WeAgentService.SendWhileRunningMode.QUEUE_AS_STEER
                                    else WeAgentService.SendWhileRunningMode.QUEUE_AFTER_TURN
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SEND_WHILE_RUNNING, mode) }
                            },
                        )
                    }
                }
            }
        }

        // ---------- 工具 ----------
        item {
            SegmentedColumn(title = stringResource(R.string.agent_section_tools)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_builtin_tools_title),
                        description = stringResource(R.string.agent_builtin_tools_summary),
                        onClick = { onOpen(AgentSettingsRoute.BuiltinTools) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_mcp_servers_title),
                        description = stringResource(R.string.agent_mcp_servers_summary),
                        onClick = { onOpen(AgentSettingsRoute.McpServers) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                if (loaded) {
                    item {
                        SwitchWidget(
                            title = stringResource(R.string.agent_dynamic_tools_title),
                            description = stringResource(R.string.agent_dynamic_tools_summary),
                            checked = dynamicTools,
                            onCheckedChange = {
                                dynamicTools = it
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_TOOL_LOADING_MODE, if (it) "DYNAMIC" else "STATIC") }
                            },
                        )
                    }
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_workspaces_title),
                        description = stringResource(R.string.agent_workspaces_summary),
                        onClick = { onOpen(AgentSettingsRoute.Workspaces) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_memory_title),
                        description = stringResource(R.string.agent_memory_summary),
                        onClick = { onOpen(AgentSettingsRoute.Memory) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_external_services_title),
                        description = stringResource(R.string.agent_external_services_summary),
                        onClick = { onOpen(AgentSettingsRoute.ExternalServices) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }

        // ---------- 上下文 ----------
        item {
            SegmentedColumn(title = stringResource(R.string.agent_section_context)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_prompts_title),
                        description = stringResource(R.string.agent_prompts_summary),
                        onClick = { onOpen(AgentSettingsRoute.Prompts) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_skills_title),
                        description = stringResource(R.string.agent_skills_summary),
                        onClick = { onOpen(AgentSettingsRoute.Skills) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_triggers_title),
                        description = stringResource(R.string.agent_triggers_summary),
                        onClick = { onOpen(AgentSettingsRoute.Triggers) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }

        // ---------- 默认 ----------
        if (loaded) {
            item {
                SegmentedColumn(
                    title = stringResource(R.string.agent_section_defaults),
                    modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                ) {
                    item {
                        ModelDropdown(
                            title = stringResource(R.string.agent_default_model_title),
                            models = models,
                            selectedId = defaultModelId,
                            noneLabel = stringResource(R.string.agent_use_first_model),
                        ) { id ->
                            defaultModelId = id
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_MODEL_ID, id.orEmpty()) }
                        }
                    }
                    item {
                        GenericDropdown(
                            title = stringResource(R.string.agent_default_system_prompt_title),
                            items = systemPrompts.map { it.id to it.name },
                            selectedId = defaultSystemPromptId,
                            noneLabel = stringResource(R.string.common_none_parenthesized),
                        ) { id ->
                            defaultSystemPromptId = id
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_SYSTEM_PROMPT_ID, id.orEmpty()) }
                        }
                    }
                    item {
                        GenericDropdown(
                            title = stringResource(R.string.agent_default_workspace_title),
                            items = workspaces.map { it.id to it.name },
                            selectedId = defaultWorkspaceId,
                            noneLabel = stringResource(R.string.common_none_parenthesized),
                        ) { id ->
                            defaultWorkspaceId = id
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_WORKSPACE_ID, id.orEmpty()) }
                        }
                    }
                }
            }
        }
    }
}

/** Row opening an M3 [AlertDialog] radio list — the agent-settings counterpart of a dropdown preference. */
@Composable
internal fun AgentDropdownRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String? = null,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { index, label ->
                        RadioButtonWidget(
                            title = label,
                            selected = index == selectedIndex,
                            onClick = {
                                showDialog = false
                                onSelectedIndexChange(index)
                            },
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    BaseWidget(
        title = title,
        description = summary ?: items[selectedIndex],
        onClick = { showDialog = true },
        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun ModelDropdown(
    title: String,
    models: List<ModelEntity>,
    selectedId: String?,
    noneLabel: String,
    onSelected: (String?) -> Unit,
) = GenericDropdown(
    title = title,
    items = models.map { it.id to it.displayName.ifBlank { it.modelIdRemote } },
    selectedId = selectedId,
    noneLabel = noneLabel,
    onSelected = onSelected,
)

/** Dropdown over (id, label) pairs with an optional leading "none" entry mapping to null. */
@Composable
private fun GenericDropdown(
    title: String,
    items: List<Pair<String, String>>,
    selectedId: String?,
    noneLabel: String?,
    onSelected: (String?) -> Unit,
) {
    val ids = buildList { if (noneLabel != null) add(null); items.forEach { add(it.first) } }
    val labels = buildList { if (noneLabel != null) add(noneLabel); items.forEach { add(it.second) } }
    val selectedIndex = ids.indexOf(selectedId).coerceAtLeast(0)
    AgentDropdownRow(
        title = title,
        items = labels,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { onSelected(ids[it]) },
    )
}
