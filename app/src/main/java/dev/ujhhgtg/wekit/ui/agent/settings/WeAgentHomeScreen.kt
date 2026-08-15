package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.agent.AgentSettingsRoute
import dev.ujhhgtg.wekit.agent.data.OverlayMode
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.features.items.system.agent.WeAgentOverlayController
import dev.ujhhgtg.wekit.ui.content.MiuixSmallTitle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

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
    var maxRequests by remember { mutableStateOf(WeAgentSettings.DEFAULT_MAX_MODEL_REQUESTS.toString()) }
    var smallModelId by remember { mutableStateOf<String?>(null) }
    var defaultModelId by remember { mutableStateOf<String?>(null) }
    var defaultSystemPromptId by remember { mutableStateOf<String?>(null) }
    var defaultWorkspaceId by remember { mutableStateOf<String?>(null) }

    // These must come from the live DB flows, not a one-shot read: MiuixStackNavigator keeps the whole
    // stack composed, so this screen never leaves composition and a LaunchedEffect(Unit) would never
    // re-run. A model/prompt/workspace added on a child screen has to show up in these dropdowns as
    // soon as the user comes back.
    val models by remember { WeAgentRepository.observeModels() }.collectAsState(initial = emptyList())
    val systemPrompts by remember { WeAgentRepository.observeSystemPrompts() }.collectAsState(initial = emptyList())
    val workspaces by remember { WeAgentRepository.observeWorkspaces() }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        dynamicTools = WeAgentSettings.toolLoadingMode() == dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode.DYNAMIC
        overlayMode = WeAgentSettings.overlayMode()
        sendWhileRunning = WeAgentSettings.sendWhileRunningMode().name
        maxRequests = WeAgentSettings.maxModelRequests().toString()
        smallModelId = WeAgentSettings.smallModelId()
        defaultModelId = WeAgentSettings.defaultModelId()
        defaultSystemPromptId = WeAgentSettings.defaultSystemPromptId()
        defaultWorkspaceId = WeAgentSettings.defaultWorkspaceId()
        loaded = true
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_settings_title), onBack = null) {
        // ---------- 界面 ----------
        item { MiuixSmallTitle(stringResource(R.string.settings_section_interface)) }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                if (loaded) {
                    WindowDropdownPreference(
                        title = stringResource(R.string.agent_overlay_mode_title),
                        summary = stringResource(R.string.agent_overlay_mode_summary),
                        items = OverlayMode.entries.map(overlayModeLabels::getValue),
                        selectedIndex = OverlayMode.entries.indexOf(overlayMode),
                        onSelectedIndexChange = {
                            val mode = OverlayMode.entries[it]
                            overlayMode = mode
                            WeAgentOverlayController.setMode(mode)
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_OVERLAY_MODE, mode.name) }
                        },
                    )
                }
            }
        }

        // ---------- 模型 ----------
        item { MiuixSmallTitle(stringResource(R.string.agent_section_models)) }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.agent_model_providers_title),
                    summary = stringResource(R.string.agent_model_providers_summary),
                    onClick = { onOpen(AgentSettingsRoute.ModelProviders) },
                )
                if (loaded) {
                    // 文本在左，短输入框在右
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.agent_max_requests_per_turn), modifier = Modifier.weight(1f))
                        TextField(
                            value = maxRequests,
                            onValueChange = { v -> maxRequests = v.filter { it.isDigit() }.take(3) },
                            label = "",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    ModelDropdown(
                        title = stringResource(R.string.agent_small_model_title),
                        models = models,
                        selectedId = smallModelId,
                        noneLabel = stringResource(R.string.agent_same_as_primary_model),
                    ) { id ->
                        smallModelId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SMALL_MODEL_ID, id.orEmpty()) }
                    }
                    WindowDropdownPreference(
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

        // ---------- 工具 ----------
        item { MiuixSmallTitle(stringResource(R.string.agent_section_tools)) }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.agent_builtin_tools_title),
                    summary = stringResource(R.string.agent_builtin_tools_summary),
                    onClick = { onOpen(AgentSettingsRoute.BuiltinTools) },
                )
                ArrowPreference(
                    title = stringResource(R.string.agent_mcp_servers_title),
                    summary = stringResource(R.string.agent_mcp_servers_summary),
                    onClick = { onOpen(AgentSettingsRoute.McpServers) },
                )
                if (loaded) {
                    SwitchPreference(
                        title = stringResource(R.string.agent_dynamic_tools_title),
                        summary = stringResource(R.string.agent_dynamic_tools_summary),
                        checked = dynamicTools,
                        onCheckedChange = {
                            dynamicTools = it
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_TOOL_LOADING_MODE, if (it) "DYNAMIC" else "STATIC") }
                        },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.agent_workspaces_title),
                    summary = stringResource(R.string.agent_workspaces_summary),
                    onClick = { onOpen(AgentSettingsRoute.Workspaces) },
                )
                ArrowPreference(
                    title = stringResource(R.string.agent_memory_title),
                    summary = stringResource(R.string.agent_memory_summary),
                    onClick = { onOpen(AgentSettingsRoute.Memory) },
                )
                ArrowPreference(
                    title = stringResource(R.string.agent_external_services_title),
                    summary = stringResource(R.string.agent_external_services_summary),
                    onClick = { onOpen(AgentSettingsRoute.ExternalServices) },
                )
            }
        }

        // ---------- 上下文 ----------
        item { MiuixSmallTitle(stringResource(R.string.agent_section_context)) }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.agent_prompts_title),
                    summary = stringResource(R.string.agent_prompts_summary),
                    onClick = { onOpen(AgentSettingsRoute.Prompts) },
                )
                ArrowPreference(
                    title = stringResource(R.string.agent_skills_title),
                    summary = stringResource(R.string.agent_skills_summary),
                    onClick = { onOpen(AgentSettingsRoute.Skills) },
                )
                ArrowPreference(
                    title = stringResource(R.string.agent_triggers_title),
                    summary = stringResource(R.string.agent_triggers_summary),
                    onClick = { onOpen(AgentSettingsRoute.Triggers) },
                )
            }
        }

        // ---------- 默认 ----------
        if (loaded) {
            item { MiuixSmallTitle(stringResource(R.string.agent_section_defaults)) }
            item {
                Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                    ModelDropdown(
                        title = stringResource(R.string.agent_default_model_title),
                        models = models,
                        selectedId = defaultModelId,
                        noneLabel = stringResource(R.string.agent_use_first_model),
                    ) { id ->
                        defaultModelId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_MODEL_ID, id.orEmpty()) }
                    }
                    GenericDropdown(
                        title = stringResource(R.string.agent_default_system_prompt_title),
                        items = systemPrompts.map { it.id to it.name },
                        selectedId = defaultSystemPromptId,
                        noneLabel = stringResource(R.string.common_none_parenthesized),
                    ) { id ->
                        defaultSystemPromptId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_SYSTEM_PROMPT_ID, id.orEmpty()) }
                    }
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

    // Persist the (validated) request cap as it changes — but only AFTER the initial load has
    // populated the field. Otherwise this effect fires on first composition with the default
    // ("50") and clobbers the stored value before LaunchedEffect(Unit) can read it back, so the
    // setting never appears to persist across screen opens.
    LaunchedEffect(maxRequests, loaded) {
        if (!loaded) return@LaunchedEffect
        // Blank means "still typing" — don't store anything yet.
        val typed = maxRequests.toIntOrNull() ?: return@LaunchedEffect
        val clamped = typed.coerceIn(1, 100)
        // Write the clamp back into the field as well, otherwise the UI would keep showing the raw
        // input (e.g. "500" or "0") while a different value is actually stored.
        if (clamped != typed) maxRequests = clamped.toString()
        WeAgentSettings.set(WeAgentSettings.KEY_MAX_MODEL_REQUESTS, clamped.toString())
    }
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
    WindowDropdownPreference(
        title = title,
        items = labels,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { onSelected(ids[it]) },
    )
}
