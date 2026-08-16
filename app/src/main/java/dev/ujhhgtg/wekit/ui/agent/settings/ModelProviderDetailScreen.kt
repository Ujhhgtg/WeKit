package dev.ujhhgtg.wekit.ui.agent.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Cloud_download
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitBasicDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.launch
import java.util.UUID

/** Edits one provider (name/url/key) and manages its models (id + reasoning gear + custom JSON). */
@Composable
fun ModelProviderDetailScreen(providerId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var provider by remember { mutableStateOf<ModelProviderEntity?>(null) }
    var showDeleteProviderConfirm by remember { mutableStateOf(false) }

    // Connection fields are hoisted to screen scope so both "保存" and "自动导入模型" read the live,
    // possibly-unsaved values. The API key field holds the key exactly as stored — there is no
    // encryption anywhere in the pipeline, so nothing has to be encoded/decoded around it.
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(providerId) {
        val fresh = WeAgentRepository.getModelProvider(providerId)
        provider = fresh
        if (fresh != null) {
            name = fresh.name; baseUrl = fresh.baseUrl; apiKey = fresh.apiKey
        }
    }

    val models by WeAgentRepository.observeModelsForProvider(providerId).collectAsState(initial = emptyList())
    // null = closed; empty-id ModelEntity = adding; existing = editing.
    var editingModel by remember { mutableStateOf<ModelEntity?>(null) }
    // Auto-import state: fetched ids to pick from, plus loading/error.
    var importCandidates by remember { mutableStateOf<List<String>?>(null) }
    var importing by remember { mutableStateOf(false) }
    // API keys are stored in the clear, so at least don't render them in the clear.
    var showApiKey by remember { mutableStateOf(false) }

    val p = provider

    AgentSettingsScaffold(title = p?.name ?: stringResource(R.string.agent_provider_fallback_title), onBack = onBack) {
        if (p == null) {
            item { EmptyHint(stringResource(R.string.agent_loading)) }
            return@AgentSettingsScaffold
        }

        item {
            SegmentedColumn(title = stringResource(R.string.agent_section_connection)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.agent_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(topPadding = 8.dp) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.agent_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(topPadding = 8.dp) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.agent_api_key_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) MaterialSymbols.Outlined.Visibility_off
                                    else MaterialSymbols.Outlined.Visibility,
                                    contentDescription = stringResource(if (showApiKey) R.string.accessibility_hide else R.string.accessibility_show),
                                )
                            }
                        },
                    )
                }
            }
        }
        item {
            AgentActionRow {
                OutlinedButton(
                    onClick = { showDeleteProviderConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
                Button(
                    onClick = {
                        scope.launch {
                            val updated = p.copy(name = name, baseUrl = baseUrl, apiKey = apiKey)
                            WeAgentRepository.upsertModelProvider(updated)
                            ModelProviderManager.invalidate(p.id)
                            // Keep the local copy in sync so the scaffold title reflects a rename
                            // (LaunchedEffect(providerId) only runs once, on first composition).
                            provider = updated
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }

        item { ModelSectionTitle(stringResource(R.string.agent_section_models)) }
        if (models.isEmpty()) {
            item { EmptyHint(stringResource(R.string.agent_empty_models_message)) }
        } else {
            lazySegmentedItems(models, key = { it.id }) { m ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = m.displayName.ifBlank { m.modelIdRemote },
                        description = "id=${m.modelIdRemote}" +
                                (m.reasoningEffort?.let { " · effort=$it" } ?: "") +
                                (m.contextWindow?.let { " · ctx=$it" } ?: "") +
                                (m.maxTokens?.let { " · max=$it" } ?: "") +
                                if (m.supportsVision) " · ${stringResource(R.string.agent_model_supports_vision_badge)}" else "",
                        onClick = { editingModel = m },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
        item {
            AgentActionRow {
                AgentListActionButton(
                    label = stringResource(R.string.agent_add_model),
                    icon = MaterialSymbols.Outlined.Add,
                    enabled = !importing,
                    onClick = { editingModel = ModelEntity("", providerId, "", null, null, "", null) },
                )
                // Auto-import is only meaningful for the OpenAI-style /models endpoint.
                if (p.type != ModelProviderType.ANTHROPIC_MESSAGES) {
                    AgentListActionButton(
                        label = stringResource(R.string.agent_auto_import_models),
                        icon = MaterialSymbols.Outlined.Cloud_download,
                        loading = importing,
                        onClick = {
                            importing = true
                            scope.launch {
                                // Use the live (possibly unsaved) connection fields, per project decision.
                                val result = ModelProviderManager.listRemoteModels(
                                    p.copy(name = name, baseUrl = baseUrl, apiKey = apiKey)
                                )
                                importing = false
                                result.fold(
                                    onSuccess = { importCandidates = it },
                                    onFailure = {
                                        showToast(
                                            currentAgentLocalizedContext(context).getString(
                                                R.string.agent_fetch_models_failed,
                                                it.message,
                                            )
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (p != null) {
        AgentConfirmDialog(
            show = showDeleteProviderConfirm,
            title = stringResource(R.string.agent_delete_provider),
            message = stringResource(R.string.agent_delete_provider_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.dialog_cancel),
            destructive = true,
            onConfirm = {
                showDeleteProviderConfirm = false
                scope.launch {
                    try {
                        WeAgentRepository.deleteModelProvider(p.id)
                        onBack()
                    } catch (e: Exception) {
                        showToast(currentAgentLocalizedContext(context).getString(R.string.agent_delete_failed, e.message))
                    }
                }
            },
            onDismiss = { showDeleteProviderConfirm = false },
        )
    }

    ImportModelsDialog(
        show = importCandidates != null,
        candidates = importCandidates.orEmpty(),
        existingRemoteIds = models.map { it.modelIdRemote }.toSet(),
        onDismiss = { importCandidates = null },
        onImport = { picked ->
            scope.launch {
                val (added, overwritten) = WeAgentRepository.importModels(providerId, picked)
                showToast(
                    currentAgentLocalizedContext(context).getString(
                        R.string.agent_models_imported_result, added, overwritten
                    )
                )
            }
            importCandidates = null
        },
    )

    ModelEditorSheet(
        show = editingModel != null,
        existing = editingModel ?: ModelEntity("", providerId, "", null, null, "", null),
        onDismiss = { editingModel = null },
        onDelete = editingModel?.id?.takeIf { it.isNotEmpty() }?.let { id ->
            {
                scope.launch {
                    try {
                        WeAgentRepository.deleteModel(id)
                        editingModel = null
                    } catch (e: Exception) {
                        showToast(currentAgentLocalizedContext(context).getString(R.string.agent_delete_failed, e.message))
                    }
                }
            }
        },
        onSave = { remoteId, display, effort, customJson, contextWindow, maxTokens, supportsVision ->
            editingModel?.let { m ->
                scope.launch {
                    WeAgentRepository.upsertModel(
                        m.copy(
                            id = m.id.ifEmpty { UUID.randomUUID().toString() },
                            providerId = providerId,
                            modelIdRemote = remoteId,
                            reasoningEffort = effort,
                            customJsonOverride = customJson,
                            displayName = display.ifBlank { remoteId },
                            contextWindow = contextWindow,
                            maxTokens = maxTokens,
                            supportsVision = supportsVision,
                        )
                    )
                }
            }
            editingModel = null
        },
    )
}

/** Mirrors [SegmentedColumn]'s section title styling for sections whose rows are laid out lazily. */
@Composable
private fun ModelSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 16.dp),
    )
}

/** Reasoning-effort gears. "off" means omit the field entirely. */
private val EFFORT_GEARS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

@Composable
private fun effortGearLabel(value: String): String = stringResource(
    when (value) {
        "off" -> R.string.agent_reasoning_effort_off
        "minimal" -> R.string.agent_reasoning_effort_minimal
        "low" -> R.string.agent_reasoning_effort_low
        "medium" -> R.string.agent_reasoning_effort_medium
        "high" -> R.string.agent_reasoning_effort_high
        "xhigh" -> R.string.agent_reasoning_effort_extra_high
        "max" -> R.string.agent_reasoning_effort_maximum
        else -> error("Unknown reasoning effort: $value")
    }
)

private fun currentAgentLocalizedContext(base: Context): Context =
    LocalizedContextFactory.create(
        base,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )

/**
 * Model-import picker: lists ids fetched from the provider's `/models` endpoint. Ids already added
 * start unchecked (selecting one overwrites its config) and carry an "(已导入)" suffix; the rest
 * start selected. Confirming imports every selected id.
 */
@Composable
private fun ImportModelsDialog(
    show: Boolean,
    candidates: List<String>,
    existingRemoteIds: Set<String>,
    onDismiss: () -> Unit,
    onImport: (List<String>) -> Unit,
) {
    // Pre-select every not-yet-added id. Keyed on [candidates] because the dialog is composed
    // unconditionally: on first composition nothing has been fetched yet, so an unkeyed remember
    // would freeze an empty selection (and carry the previous run's ticks into the next import).
    val selected = remember(candidates) {
        mutableStateListOf<String>().apply { addAll(candidates.filter { it !in existingRemoteIds }) }
    }

    WeKitBasicDialog(show = show, title = stringResource(R.string.agent_import_models_title, candidates.size), onDismissRequest = onDismiss) {
        Column {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.agent_provider_returned_no_models))
            } else {
                Text(
                    text = stringResource(R.string.agent_import_overwrite_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                candidates.forEach { id ->
                    val already = id in existingRemoteIds
                    val checked = id in selected
                    // The whole row toggles; the checkbox is a pure indicator with no semantics of its own.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (id in selected) selected.remove(id) else selected.add(id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                        Text(
                            if (already) stringResource(R.string.agent_model_already_added, id) else id,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onImport(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) { Text(stringResource(R.string.agent_import_selected_models, selected.size)) }
            }
        }
    }
}

@Composable
private fun ModelEditorSheet(
    show: Boolean,
    existing: ModelEntity,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (remoteId: String, display: String, effort: String?, customJson: String?, contextWindow: Int?, maxTokens: Int?, supportsVision: Boolean) -> Unit,
) {
    // Every field is keyed on [existing] (and [show]): the sheet is composed unconditionally, with a
    // blank placeholder entity while nothing is being edited, so unkeyed state would capture those
    // blanks and 保存 would then overwrite the stored model's whole config with them.
    var remoteId by remember(existing, show) { mutableStateOf(existing.modelIdRemote) }
    var display by remember(existing, show) { mutableStateOf(existing.displayName) }
    var customJson by remember(existing, show) { mutableStateOf(existing.customJsonOverride.orEmpty()) }
    var contextWindow by remember(existing, show) { mutableStateOf(existing.contextWindow?.toString().orEmpty()) }
    var maxTokens by remember(existing, show) { mutableStateOf(existing.maxTokens?.toString().orEmpty()) }
    var supportsVision by remember(existing, show) { mutableStateOf(existing.supportsVision) }
    var effort by remember(existing, show) { mutableStateOf(existing.reasoningEffort ?: "off") }
    var showDeleteConfirm by remember(existing) { mutableStateOf(false) }

    AgentEditorSheet(
        show = show,
        title = stringResource(if (existing.id.isEmpty()) R.string.agent_add_model else R.string.agent_edit_model),
        onDismiss = onDismiss,
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.action_delete)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            remoteId, display, effort.takeIf { it != "off" }, customJson.ifBlank { null },
                            contextWindow.toIntOrNull(), maxTokens.toIntOrNull(), supportsVision,
                        )
                    },
                    enabled = remoteId.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) {
        OutlinedTextField(
            value = remoteId,
            onValueChange = { remoteId = it },
            label = { Text(stringResource(R.string.agent_model_id_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = display,
            onValueChange = { display = it },
            label = { Text(stringResource(R.string.agent_model_display_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        DropDownMenuWidget(
            icon = null,
            iconPlaceholder = false,
            title = stringResource(R.string.agent_reasoning_effort),
            description = null,
            value = effort,
            options = EFFORT_GEARS.map { DropdownOption(it, effortGearLabel(it)) },
            onValueChange = { effort = it },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = contextWindow,
            onValueChange = { v -> contextWindow = v.filter { it.isDigit() }.take(9) },
            label = { Text(stringResource(R.string.agent_context_window_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = maxTokens,
            onValueChange = { v -> maxTokens = v.filter { it.isDigit() }.take(9) },
            label = { Text(stringResource(R.string.agent_max_output_tokens_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = customJson,
            onValueChange = { customJson = it },
            label = { Text(stringResource(R.string.agent_custom_json_label)) },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        SwitchWidget(
            title = stringResource(R.string.agent_supports_vision),
            description = stringResource(R.string.agent_supports_vision_summary),
            checked = supportsVision,
            onCheckedChange = { supportsVision = it },
        )
    }

    AgentConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.action_delete),
        message = stringResource(R.string.agent_delete_model_confirm),
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
