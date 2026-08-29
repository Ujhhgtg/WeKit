package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.composables.icons.materialsymbols.outlined.Auto_stories
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Construction
import com.composables.icons.materialsymbols.outlined.Edit_note
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Terminal
import com.composables.icons.materialsymbols.outlined.Key
import com.composables.icons.materialsymbols.outlined.Notes
import com.composables.icons.materialsymbols.outlined.Notifications_active
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Shield
import com.composables.icons.materialsymbols.outlined.Smart_display
import com.composables.icons.materialsymbols.outlined.Smart_toy
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.agent.AgentSettingsRoute
import dev.ujhhgtg.wekit.agent.data.OverlayMode
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.tool.PermissionLevel
import dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.features.items.system.agent.WeAgentOverlayController
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import kotlinx.coroutines.launch

/**
 * WeAgent settings home.
 */
@Composable
fun WeAgentHomeScreen(onOpen: (AgentSettingsRoute) -> Unit) {
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var dynamicTools by remember { mutableStateOf(false) }
    var overlayMode by remember { mutableStateOf(OverlayMode.DISABLED) }
    var sendWhileRunning by remember { mutableStateOf(WeAgentService.SendWhileRunningMode.QUEUE_AFTER_TURN) }
    var smallModelId by remember { mutableStateOf<String?>(null) }
    var defaultModelId by remember { mutableStateOf<String?>(null) }
    var defaultSystemPromptId by remember { mutableStateOf<String?>(null) }
    var defaultPermissionLevel by remember { mutableStateOf(PermissionLevel.REQUEST_APPROVAL) }

    // These must come from the live DB flows, not a one-shot read: a model/prompt/environment added
    // on a child screen has to show up in these dropdowns as soon as the user comes back, no
    // matter how the nav host composes covered entries.
    // Null until the flow's first emission: the selector rows below must not compose against a
    // not-yet-loaded option list, since a persisted non-null id would not match any option.
    val models by remember { WeAgentRepository.observeModels() }
        .collectAsState(initial = null)
    val systemPrompts by remember { WeAgentRepository.observeSystemPrompts() }
        .collectAsState(initial = null)

    LaunchedEffect(Unit) {
        dynamicTools = WeAgentSettings.toolLoadingMode() == ToolLoadingMode.DYNAMIC
        overlayMode = WeAgentSettings.overlayMode()
        sendWhileRunning = WeAgentSettings.sendWhileRunningMode()
        smallModelId = WeAgentSettings.smallModelId()
        defaultModelId = WeAgentSettings.defaultModelId()
        defaultSystemPromptId = WeAgentSettings.defaultSystemPromptId()
        defaultPermissionLevel = WeAgentSettings.defaultPermissionLevel()
        loaded = true
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_settings_title), onBack = null) {
        // ---------- 界面 ----------
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_interface)) {
                if (loaded) {
                    item {
                        DropDownMenuWidget(
                            icon = MaterialSymbols.Outlined.Smart_display,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_overlay_mode_title),
                            description = null,
                            value = overlayMode,
                            options = OverlayMode.entries.map { DropdownOption(it, it.labelRes()) },
                            onValueChange = { mode ->
                                overlayMode = mode
                                WeAgentOverlayController.setMode(mode)
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_OVERLAY_MODE, mode.name) }
                            },
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
                        icon = MaterialSymbols.Outlined.Cloud,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_model_providers_title),
                        description = stringResource(R.string.agent_model_providers_summary),
                        onClick = { onOpen(AgentSettingsRoute.ModelProviders) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                if (loaded && models != null) {
                    item {
                        DropDownMenuWidget(
                            icon = MaterialSymbols.Outlined.Bolt,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_small_model_title),
                            description = null,
                            value = staleToNull(smallModelId, models!!.map { it.id }),
                            options = listOf(DropdownOption<String?>(null, stringResource(R.string.agent_same_as_primary_model))) +
                                models!!.map { DropdownOption(it.id, it.displayName.ifBlank { it.modelIdRemote }) },
                            onValueChange = { id ->
                                smallModelId = id
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SMALL_MODEL_ID, id.orEmpty()) }
                            },
                        )
                    }
                    item {
                        DropDownMenuWidget(
                            icon = MaterialSymbols.Outlined.Send,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_send_while_running_title),
                            description = null,
                            value = sendWhileRunning,
                            options = WeAgentService.SendWhileRunningMode.entries.map { DropdownOption(it, it.labelRes()) },
                            onValueChange = { mode ->
                                sendWhileRunning = mode
                                WeAgentService.sendWhileRunningMode.value = mode
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SEND_WHILE_RUNNING, mode.name) }
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
                        icon = MaterialSymbols.Outlined.Construction,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_builtin_tools_title),
                        description = stringResource(R.string.agent_builtin_tools_summary),
                        onClick = { onOpen(AgentSettingsRoute.BuiltinTools) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Extension,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_mcp_servers_title),
                        description = stringResource(R.string.agent_mcp_servers_summary),
                        onClick = { onOpen(AgentSettingsRoute.McpServers) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                if (loaded) {
                    item {
                        SwitchWidget(
                            icon = MaterialSymbols.Outlined.Search,
                            iconPlaceholder = false,
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
                        icon = MaterialSymbols.Outlined.Terminal,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_linux_environments_title),
                        description = stringResource(R.string.agent_linux_environments_summary),
                        onClick = { onOpen(AgentSettingsRoute.LinuxEnvironments) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Key,
                        iconPlaceholder = false,
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
                        icon = MaterialSymbols.Outlined.Edit_note,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_prompts_title),
                        description = stringResource(R.string.agent_prompts_summary),
                        onClick = { onOpen(AgentSettingsRoute.Prompts) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Auto_stories,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_skills_title),
                        description = stringResource(R.string.agent_skills_summary),
                        onClick = { onOpen(AgentSettingsRoute.Skills) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                item {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Notifications_active,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_triggers_title),
                        description = stringResource(R.string.agent_triggers_summary),
                        onClick = { onOpen(AgentSettingsRoute.Triggers) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }

        // ---------- 默认 ----------
        if (loaded && models != null && systemPrompts != null) {
            item {
                SegmentedColumn(
                    title = stringResource(R.string.agent_section_defaults),
                    modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                ) {
                    item {
                        DropDownMenuWidget(
                            icon = MaterialSymbols.Outlined.Smart_toy,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_default_model_title),
                            description = null,
                            value = staleToNull(defaultModelId, models!!.map { it.id }),
                            options = listOf(DropdownOption<String?>(null, stringResource(R.string.agent_use_first_model))) +
                                models!!.map { DropdownOption(it.id, it.displayName.ifBlank { it.modelIdRemote }) },
                            onValueChange = { id ->
                                defaultModelId = id
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_MODEL_ID, id.orEmpty()) }
                            },
                        )
                    }
                    item {
                        DropDownMenuWidget(
                            icon = MaterialSymbols.Outlined.Shield,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_default_permission_level_title),
                            description = null,
                            value = defaultPermissionLevel,
                            options = PermissionLevel.entries.map { DropdownOption(it, it.labelRes()) },
                            onValueChange = { level ->
                                defaultPermissionLevel = level
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_PERMISSION_LEVEL, level.name) }
                            },
                        )
                    }
                    item {
                        DropDownMenuWidget(
                            icon = MaterialSymbols.Outlined.Notes,
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_default_system_prompt_title),
                            description = null,
                            value = staleToNull(defaultSystemPromptId, systemPrompts!!.map { it.id }),
                            options = listOf(DropdownOption<String?>(null, stringResource(R.string.common_none_parenthesized))) +
                                systemPrompts!!.map { DropdownOption(it.id, it.name) },
                            onValueChange = { id ->
                                defaultSystemPromptId = id
                                scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_SYSTEM_PROMPT_ID, id.orEmpty()) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Maps an id whose entity no longer exists in the live list (e.g. deleted on a child screen
 * while it was still the persisted default) to null so the dropdown renders its no-selection
 * option instead of failing to find the value in [DropDownMenuWidget]'s option list.
 */
private fun <T> staleToNull(v: T?, list: List<T>): T? = if (v != null && v !in list) null else v

/** Localized picker label for [OverlayMode]; declaration order is the picker order. */
@Composable
private fun OverlayMode.labelRes(): String = stringResource(when (this) {
    OverlayMode.DISABLED -> R.string.agent_overlay_mode_disabled
    OverlayMode.FOREGROUND_ONLY -> R.string.agent_overlay_mode_foreground_only
    OverlayMode.ALWAYS -> R.string.agent_overlay_mode_always
})

/** Localized picker label for the send-while-running behavior; declaration order is the picker order. */
@Composable
private fun WeAgentService.SendWhileRunningMode.labelRes(): String = stringResource(when (this) {
    WeAgentService.SendWhileRunningMode.QUEUE_AFTER_TURN -> R.string.agent_send_queue_after_turn
    WeAgentService.SendWhileRunningMode.QUEUE_AS_STEER -> R.string.agent_send_steer_next_request
})

/** Localized picker label for the session permission levels; declaration order is the picker order. */
@Composable
private fun PermissionLevel.labelRes(): String = stringResource(when (this) {
    PermissionLevel.REQUEST_APPROVAL -> R.string.agent_permission_level_request_approval
    PermissionLevel.AUTO_EDIT -> R.string.agent_permission_level_auto_edit
    PermissionLevel.AUTO_APPROVAL -> R.string.agent_permission_level_auto_approval
    PermissionLevel.FULL_ACCESS -> R.string.agent_permission_level_full_access
})
