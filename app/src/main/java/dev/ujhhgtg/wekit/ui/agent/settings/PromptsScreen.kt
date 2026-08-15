package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.ui.content.WeKitWindowDialog
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Prompts (§6), flattened — the "role/profile" concept is gone. One page with four sections:
 *  - 系统提示词: named prompts, bound per-session; no switch (exist / not).
 *  - 每轮提示词: each has a global enable switch (prepended to every user message when on).
 *  - 条件提示词: each has a global enable switch (regex-matched against replies when on).
 *  - 预设提示词: reusable snippets to insert into the input; no switch.
 * Tapping an item edits it; each section has an add button at the bottom.
 */
@Composable
fun PromptsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val systemPrompts by WeAgentRepository.observeSystemPrompts().collectAsState(initial = emptyList())
    val perTurn by WeAgentRepository.observePerTurnPrompts().collectAsState(initial = emptyList())
    val conditionals by WeAgentRepository.observeConditionalPrompts().collectAsState(initial = emptyList())
    val presets by WeAgentRepository.observePresetPrompts().collectAsState(initial = emptyList())

    // Editors: null = closed. Empty-id entity = adding new.
    var editSystem by remember { mutableStateOf<SystemPromptEntity?>(null) }
    var editPerTurn by remember { mutableStateOf<PerTurnPromptEntity?>(null) }
    var editConditional by remember { mutableStateOf<ConditionalPromptEntity?>(null) }
    var editPreset by remember { mutableStateOf<PresetPromptEntity?>(null) }

    AgentSettingsScaffold(title = stringResource(R.string.agent_prompts_title), onBack = onBack) {
        // -------- 系统提示词 --------
        item {
            SegmentedColumn(title = stringResource(R.string.agent_system_prompts)) {
                if (systemPrompts.isEmpty()) item { EmptyHint(stringResource(R.string.agent_system_prompts_empty)) }
                systemPrompts.forEach { sp ->
                    item {
                        BaseWidget(
                            title = sp.name,
                            description = sp.content.take(48),
                            onClick = { editSystem = sp },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
                item(topPadding = 8.dp) { AddRow(stringResource(R.string.agent_add_system_prompt)) { editSystem = SystemPromptEntity("", "", "") } }
            }
        }

        // -------- 每轮提示词 --------
        item {
            SegmentedColumn(title = stringResource(R.string.agent_per_turn_prompts)) {
                if (perTurn.isEmpty()) item { EmptyHint(stringResource(R.string.agent_per_turn_prompts_empty)) }
                perTurn.forEach { p ->
                    item {
                        SwitchWidget(
                            title = p.title.ifBlank { p.content.take(24) },
                            description = p.content.take(48),
                            checked = p.enabled,
                            onCheckedChange = { on -> scope.launch { WeAgentRepository.upsertPerTurnPrompt(p.copy(enabled = on)) } },
                        )
                    }
                    item { TextButton(onClick = { editPerTurn = p }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.action_edit)) } }
                }
                item(topPadding = 8.dp) { AddRow(stringResource(R.string.agent_add_per_turn_prompt)) { editPerTurn = PerTurnPromptEntity("", "", "", true) } }
            }
        }

        // -------- 条件提示词 --------
        item {
            SegmentedColumn(title = stringResource(R.string.agent_conditional_prompts)) {
                if (conditionals.isEmpty()) item { EmptyHint(stringResource(R.string.agent_conditional_prompts_empty)) }
                conditionals.forEach { c ->
                    item {
                        SwitchWidget(
                            title = "/${c.regex}/",
                            description = c.content.take(48),
                            checked = c.enabled,
                            onCheckedChange = { on -> scope.launch { WeAgentRepository.upsertConditionalPrompt(c.copy(enabled = on)) } },
                        )
                    }
                    item { TextButton(onClick = { editConditional = c }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.action_edit)) } }
                }
                item(topPadding = 8.dp) { AddRow(stringResource(R.string.agent_add_conditional_prompt)) { editConditional = ConditionalPromptEntity("", "", "", true) } }
            }
        }

        // -------- 预设提示词 --------
        item {
            SegmentedColumn(
                modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                title = stringResource(R.string.agent_preset_prompts),
            ) {
                if (presets.isEmpty()) item { EmptyHint(stringResource(R.string.agent_preset_prompts_empty)) }
                presets.forEach { p ->
                    item {
                        BaseWidget(
                            title = p.title,
                            description = p.content.take(48),
                            onClick = { editPreset = p },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
                item(topPadding = 8.dp) { AddRow(stringResource(R.string.agent_add_preset_prompt)) { editPreset = PresetPromptEntity("", "", "") } }
            }
        }
    }

    // -------- Editors --------
    TwoFieldEditor(
        show = editSystem != null,
        title = stringResource(if (editSystem?.id.isNullOrEmpty()) R.string.agent_add_system_prompt else R.string.agent_edit_system_prompt),
        field1Label = stringResource(R.string.agent_field_name), field1 = editSystem?.name.orEmpty(),
        field2Label = stringResource(R.string.agent_system_prompt_content), field2 = editSystem?.content.orEmpty(), field2MaxLines = 12,
        onDismiss = { editSystem = null },
        onDelete = editSystem?.id?.takeIf { it.isNotEmpty() }?.let { id -> { scope.launch { WeAgentRepository.deleteSystemPrompt(id) }; editSystem = null } },
        onSave = { name, content ->
            editSystem?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertSystemPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            name = name,
                            content = content
                        )
                    )
                }
            }
            editSystem = null
        },
    )
    TwoFieldEditor(
        show = editPerTurn != null,
        title = stringResource(if (editPerTurn?.id.isNullOrEmpty()) R.string.agent_add_per_turn_prompt else R.string.agent_edit_per_turn_prompt),
        field1Label = stringResource(R.string.agent_optional_title), field1 = editPerTurn?.title.orEmpty(),
        field2Label = stringResource(R.string.agent_per_turn_prompt_content), field2 = editPerTurn?.content.orEmpty(), field2MaxLines = 8,
        onDismiss = { editPerTurn = null },
        onDelete = editPerTurn?.id?.takeIf { it.isNotEmpty() }
            ?.let { id -> { scope.launch { WeAgentRepository.deletePerTurnPrompt(id) }; editPerTurn = null } },
        onSave = { title, content ->
            editPerTurn?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertPerTurnPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            title = title,
                            content = content
                        )
                    )
                }
            }
            editPerTurn = null
        },
    )
    TwoFieldEditor(
        show = editConditional != null,
        title = stringResource(if (editConditional?.id.isNullOrEmpty()) R.string.agent_add_conditional_prompt else R.string.agent_edit_conditional_prompt),
        field1Label = stringResource(R.string.agent_trigger_regex), field1 = editConditional?.regex.orEmpty(),
        field2Label = stringResource(R.string.agent_injected_content), field2 = editConditional?.content.orEmpty(), field2MaxLines = 8,
        onDismiss = { editConditional = null },
        onDelete = editConditional?.id?.takeIf { it.isNotEmpty() }
            ?.let { id -> { scope.launch { WeAgentRepository.deleteConditionalPrompt(id) }; editConditional = null } },
        onSave = { regex, content ->
            editConditional?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertConditionalPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            regex = regex,
                            content = content
                        )
                    )
                }
            }
            editConditional = null
        },
    )
    TwoFieldEditor(
        show = editPreset != null,
        title = stringResource(if (editPreset?.id.isNullOrEmpty()) R.string.agent_add_preset_prompt else R.string.agent_edit_preset_prompt),
        field1Label = stringResource(R.string.agent_title_label), field1 = editPreset?.title.orEmpty(),
        field2Label = stringResource(R.string.agent_preset_content), field2 = editPreset?.content.orEmpty(), field2MaxLines = 8,
        onDismiss = { editPreset = null },
        onDelete = editPreset?.id?.takeIf { it.isNotEmpty() }?.let { id -> { scope.launch { WeAgentRepository.deletePresetPrompt(id) }; editPreset = null } },
        onSave = { title, content ->
            editPreset?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertPresetPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            title = title,
                            content = content
                        )
                    )
                }
            }
            editPreset = null
        },
    )
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Text(label) }
}

/**
 * A generic two-field editor dialog (name/title + a multiline body), with optional delete. Used for
 * all four prompt kinds since they share the same shape.
 */
@Composable
private fun TwoFieldEditor(
    show: Boolean,
    title: String,
    field1Label: String,
    field1: String,
    field2Label: String,
    field2: String,
    field2MaxLines: Int,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String) -> Unit,
) {
    // Keyed on the incoming values (and [show]): all four editors are composed unconditionally, so on
    // first composition nothing is being edited and [field1]/[field2] are blank. Unkeyed state would
    // freeze those blanks and 保存 would then wipe the prompt's name/regex with an empty string.
    var f1 by remember(field1, show) { mutableStateOf(field1) }
    var f2 by remember(field2, show) { mutableStateOf(field2) }
    WeKitWindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            OutlinedTextField(
                value = f1,
                onValueChange = { f1 = it },
                label = { Text(field1Label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = f2,
                onValueChange = { f2 = it },
                label = { Text(field2Label) },
                maxLines = field2MaxLines,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_delete)) }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onSave(f1, f2) },
                    enabled = f2.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
