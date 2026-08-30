@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.ui.content.m3BackdropLayer
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.launch
import java.util.UUID

private val PROMPT_TAB_LABELS = listOf(
    R.string.agent_system_prompts,
    R.string.agent_per_turn_prompts,
    R.string.agent_conditional_prompts,
    R.string.agent_preset_prompts,
)

/**
 * Prompts (§6): four paged lists behind one tab row —
 *  - 系统提示词: named prompts, bound per-session; no switch (exist / not).
 *  - 每轮提示词: each has a global enable switch (prepended to every user message when on).
 *  - 条件提示词: each has a global enable switch (regex-matched against replies when on).
 *  - 预设提示词: reusable snippets to insert into the input; no switch.
 * Tapping a row body edits it; per-turn/conditional switch areas toggle without opening the editor.
 */
@Composable
fun PromptsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val systemPrompts by WeAgentRepository.observeSystemPrompts().collectAsState(initial = emptyList())
    val perTurn by WeAgentRepository.observePerTurnPrompts().collectAsState(initial = emptyList())
    val conditionals by WeAgentRepository.observeConditionalPrompts().collectAsState(initial = emptyList())
    val presets by WeAgentRepository.observePresetPrompts().collectAsState(initial = emptyList())

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { PROMPT_TAB_LABELS.size })
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val barBackdrop = rememberMaterial3BlurBackdrop()

    // Pager → tab: swiping a page updates the selected tab.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page -> if (page != selectedTab) selectedTab = page }
    }

    // Editors: null = closed. Empty-id entity = adding new.
    var editSystem by remember { mutableStateOf<SystemPromptEntity?>(null) }
    var editPerTurn by remember { mutableStateOf<PerTurnPromptEntity?>(null) }
    var editConditional by remember { mutableStateOf<ConditionalPromptEntity?>(null) }
    var editPreset by remember { mutableStateOf<PresetPromptEntity?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            // The blur layer spans the app bar and the tab row beneath it, mirroring LogsPager.
            Column(modifier = Modifier.m3AppBarBlur(barBackdrop)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.agent_prompts_title)) },
                    navigationIcon = {
                        Row {
                            ExpressiveBackButton(onClick = onBack)
                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = barBackdrop.m3AppBarColor(),
                        scrolledContainerColor = barBackdrop.m3AppBarColor(),
                    ),
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                    ) {
                        PROMPT_TAB_LABELS.forEachIndexed { index, labelRes ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = {
                                    selectedTab = index
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                text = { Text(stringResource(labelRes)) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .m3BackdropLayer(barBackdrop),
            beyondViewportPageCount = PROMPT_TAB_LABELS.size - 1,
        ) { page ->
            when (page) {
                0 -> SystemPromptsPage(
                    contentPadding = innerPadding,
                    prompts = systemPrompts,
                    onEdit = { editSystem = it },
                    onAdd = { editSystem = SystemPromptEntity("", "", "") },
                )

                1 -> PerTurnPromptsPage(
                    contentPadding = innerPadding,
                    prompts = perTurn,
                    onToggle = { p, on ->
                        scope.launch { WeAgentRepository.upsertPerTurnPrompt(p.copy(enabled = on)) }
                    },
                    onEdit = { editPerTurn = it },
                    onAdd = { editPerTurn = PerTurnPromptEntity("", "", "", true) },
                )

                2 -> ConditionalPromptsPage(
                    contentPadding = innerPadding,
                    prompts = conditionals,
                    onToggle = { c, on ->
                        scope.launch { WeAgentRepository.upsertConditionalPrompt(c.copy(enabled = on)) }
                    },
                    onEdit = { editConditional = it },
                    onAdd = { editConditional = ConditionalPromptEntity("", "", "", true) },
                )

                3 -> PresetPromptsPage(
                    contentPadding = innerPadding,
                    prompts = presets,
                    onEdit = { editPreset = it },
                    onAdd = { editPreset = PresetPromptEntity("", "", "") },
                )
            }
        }
    }

    // -------- Editors --------
    SystemPromptEditor(
        existing = editSystem ?: SystemPromptEntity("", "", ""),
        show = editSystem != null,
        onDismiss = { editSystem = null },
        onSave = { name, content ->
            val entity = editSystem!!
            scope.launch {
                try {
                    WeAgentRepository.upsertSystemPrompt(
                        entity.copy(id = entity.id.ifEmpty { UUID.randomUUID().toString() }, name = name, content = content)
                    )
                    editSystem = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_save_failed, e.message))
                }
            }
        },
        onDelete = editSystem?.id?.takeIf { it.isNotEmpty() }?.let { id -> {
            scope.launch {
                try {
                    WeAgentRepository.deleteSystemPrompt(id)
                    editSystem = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        } },
    )
    PerTurnPromptEditor(
        existing = editPerTurn ?: PerTurnPromptEntity("", "", "", true),
        show = editPerTurn != null,
        onDismiss = { editPerTurn = null },
        onSave = { title, content ->
            val entity = editPerTurn!!
            scope.launch {
                try {
                    WeAgentRepository.upsertPerTurnPrompt(
                        entity.copy(id = entity.id.ifEmpty { UUID.randomUUID().toString() }, title = title, content = content)
                    )
                    editPerTurn = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_save_failed, e.message))
                }
            }
        },
        onDelete = editPerTurn?.id?.takeIf { it.isNotEmpty() }?.let { id -> {
            scope.launch {
                try {
                    WeAgentRepository.deletePerTurnPrompt(id)
                    editPerTurn = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        } },
    )
    ConditionalPromptEditor(
        existing = editConditional ?: ConditionalPromptEntity("", "", "", true),
        show = editConditional != null,
        onDismiss = { editConditional = null },
        onSave = { regex, content ->
            val entity = editConditional!!
            scope.launch {
                try {
                    WeAgentRepository.upsertConditionalPrompt(
                        entity.copy(id = entity.id.ifEmpty { UUID.randomUUID().toString() }, regex = regex, content = content)
                    )
                    editConditional = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_save_failed, e.message))
                }
            }
        },
        onDelete = editConditional?.id?.takeIf { it.isNotEmpty() }?.let { id -> {
            scope.launch {
                try {
                    WeAgentRepository.deleteConditionalPrompt(id)
                    editConditional = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        } },
    )
    PresetPromptEditor(
        existing = editPreset ?: PresetPromptEntity("", "", ""),
        show = editPreset != null,
        onDismiss = { editPreset = null },
        onSave = { title, content ->
            val entity = editPreset!!
            scope.launch {
                try {
                    WeAgentRepository.upsertPresetPrompt(
                        entity.copy(id = entity.id.ifEmpty { UUID.randomUUID().toString() }, title = title, content = content)
                    )
                    editPreset = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_save_failed, e.message))
                }
            }
        },
        onDelete = editPreset?.id?.takeIf { it.isNotEmpty() }?.let { id -> {
            scope.launch {
                try {
                    WeAgentRepository.deletePresetPrompt(id)
                    editPreset = null
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        } },
    )
}

// -------- Pages --------

@Composable
private fun SystemPromptsPage(
    contentPadding: PaddingValues,
    prompts: List<SystemPromptEntity>,
    onEdit: (SystemPromptEntity) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (prompts.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_prompts_system),
                    actionLabel = stringResource(R.string.agent_add_system_prompt),
                    onAction = onAdd,
                )
            }
        } else {
            items(prompts.size, key = { prompts[it].id }) { i ->
                val p = prompts[i]
                SegmentedColumn {
                    item {
                        BaseWidget(title = p.name, description = p.content.take(48), onClick = { onEdit(p) })
                    }
                }
            }
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_add_system_prompt),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

@Composable
private fun PerTurnPromptsPage(
    contentPadding: PaddingValues,
    prompts: List<PerTurnPromptEntity>,
    onToggle: (PerTurnPromptEntity, Boolean) -> Unit,
    onEdit: (PerTurnPromptEntity) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (prompts.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_prompts_per_turn),
                    actionLabel = stringResource(R.string.agent_add_per_turn_prompt),
                    onAction = onAdd,
                )
            }
        } else {
            items(prompts.size, key = { prompts[it].id }) { i ->
                val p = prompts[i]
                SegmentedColumn {
                    item {
                        SwitchWidget(
                            title = p.title.ifBlank { p.content.take(24) },
                            description = p.content.take(48),
                            checked = p.enabled,
                            onCheckedChange = { on -> onToggle(p, on) },
                            onClick = { onEdit(p) },
                            trailingDivider = true,
                        )
                    }
                }
            }
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_add_per_turn_prompt),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConditionalPromptsPage(
    contentPadding: PaddingValues,
    prompts: List<ConditionalPromptEntity>,
    onToggle: (ConditionalPromptEntity, Boolean) -> Unit,
    onEdit: (ConditionalPromptEntity) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (prompts.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_prompts_conditional),
                    actionLabel = stringResource(R.string.agent_add_conditional_prompt),
                    onAction = onAdd,
                )
            }
        } else {
            items(prompts.size, key = { prompts[it].id }) { i ->
                val c = prompts[i]
                SegmentedColumn {
                    item {
                        SwitchWidget(
                            title = "/${c.regex}/",
                            description = c.content.take(48),
                            checked = c.enabled,
                            onCheckedChange = { on -> onToggle(c, on) },
                            onClick = { onEdit(c) },
                            trailingDivider = true,
                        )
                    }
                }
            }
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_add_conditional_prompt),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetPromptsPage(
    contentPadding: PaddingValues,
    prompts: List<PresetPromptEntity>,
    onEdit: (PresetPromptEntity) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (prompts.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_prompts_preset),
                    actionLabel = stringResource(R.string.agent_add_preset_prompt),
                    onAction = onAdd,
                )
            }
        } else {
            items(prompts.size, key = { prompts[it].id }) { i ->
                val p = prompts[i]
                SegmentedColumn {
                    item {
                        BaseWidget(title = p.title, description = p.content.take(48), onClick = { onEdit(p) })
                    }
                }
            }
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_add_preset_prompt),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

// -------- Editors --------

@Composable
private fun SystemPromptEditor(
    existing: SystemPromptEntity,
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, content: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    // Keyed on [existing] and [show]: the editor is composed unconditionally with a blank
    // placeholder while nothing is open, so unkeyed state would save blanks over the prompt.
    var name by remember(existing, show) { mutableStateOf(existing.name) }
    var content by remember(existing, show) { mutableStateOf(existing.content) }
    var showDeleteConfirm by remember(existing) { mutableStateOf(false) }

    AgentEditorSheet(
        show = show,
        title = stringResource(if (existing.id.isEmpty()) R.string.agent_add_system_prompt else R.string.agent_edit_system_prompt),
        onDismiss = onDismiss,
        bottomBar = {
            EditorBottomBar(
                showDelete = onDelete != null,
                onDelete = { showDeleteConfirm = true },
                onDismiss = onDismiss,
                saveEnabled = content.isNotBlank(),
                onSave = { onSave(name, content) },
            )
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.agent_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.agent_system_prompt_content)) },
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.action_delete),
        message = stringResource(R.string.agent_delete_prompt_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            onDelete?.invoke()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun PerTurnPromptEditor(
    existing: PerTurnPromptEntity,
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(existing, show) { mutableStateOf(existing.title) }
    var content by remember(existing, show) { mutableStateOf(existing.content) }
    var showDeleteConfirm by remember(existing) { mutableStateOf(false) }

    AgentEditorSheet(
        show = show,
        title = stringResource(if (existing.id.isEmpty()) R.string.agent_add_per_turn_prompt else R.string.agent_edit_per_turn_prompt),
        onDismiss = onDismiss,
        bottomBar = {
            EditorBottomBar(
                showDelete = onDelete != null,
                onDelete = { showDeleteConfirm = true },
                onDismiss = onDismiss,
                saveEnabled = content.isNotBlank(),
                onSave = { onSave(title, content) },
            )
        },
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.agent_optional_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.agent_per_turn_prompt_content)) },
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.action_delete),
        message = stringResource(R.string.agent_delete_prompt_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            onDelete?.invoke()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun ConditionalPromptEditor(
    existing: ConditionalPromptEntity,
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (regex: String, content: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var regex by remember(existing, show) { mutableStateOf(existing.regex) }
    var content by remember(existing, show) { mutableStateOf(existing.content) }
    var showDeleteConfirm by remember(existing) { mutableStateOf(false) }
    val regexError = remember(regex) { runCatching { Regex(regex) }.exceptionOrNull() }

    AgentEditorSheet(
        show = show,
        title = stringResource(if (existing.id.isEmpty()) R.string.agent_add_conditional_prompt else R.string.agent_edit_conditional_prompt),
        onDismiss = onDismiss,
        bottomBar = {
            EditorBottomBar(
                showDelete = onDelete != null,
                onDelete = { showDeleteConfirm = true },
                onDismiss = onDismiss,
                saveEnabled = content.isNotBlank() && regexError == null,
                onSave = { onSave(regex, content) },
            )
        },
    ) {
        OutlinedTextField(
            value = regex,
            onValueChange = { regex = it },
            label = { Text(stringResource(R.string.agent_trigger_regex)) },
            singleLine = true,
            isError = regexError != null,
            supportingText = {
                if (regexError != null) {
                    Text(stringResource(R.string.agent_regex_invalid, regexError.message ?: ""))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.agent_injected_content)) },
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.action_delete),
        message = stringResource(R.string.agent_delete_prompt_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            onDelete?.invoke()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun PresetPromptEditor(
    existing: PresetPromptEntity,
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(existing, show) { mutableStateOf(existing.title) }
    var content by remember(existing, show) { mutableStateOf(existing.content) }
    var showDeleteConfirm by remember(existing) { mutableStateOf(false) }

    AgentEditorSheet(
        show = show,
        title = stringResource(if (existing.id.isEmpty()) R.string.agent_add_preset_prompt else R.string.agent_edit_preset_prompt),
        onDismiss = onDismiss,
        bottomBar = {
            EditorBottomBar(
                showDelete = onDelete != null,
                onDelete = { showDeleteConfirm = true },
                onDismiss = onDismiss,
                saveEnabled = content.isNotBlank(),
                onSave = { onSave(title, content) },
            )
        },
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.agent_title_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.agent_preset_content)) },
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.action_delete),
        message = stringResource(R.string.agent_delete_prompt_confirm),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            onDelete?.invoke()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun EditorBottomBar(
    showDelete: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDelete) {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.action_save)) }
    }
}
