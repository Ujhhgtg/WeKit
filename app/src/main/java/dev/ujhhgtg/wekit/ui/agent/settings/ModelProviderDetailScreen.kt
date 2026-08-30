package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Cloud_download
import com.composables.icons.materialsymbols.outlined.Save
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.model.local.LocalLlama
import dev.ujhhgtg.wekit.agent.model.local.LOCAL_LLAMA_MIN_CONTEXT_WINDOW
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaModels
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.WeKitBasicDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Edits one provider (name/url/key/type) with instant-apply rows and manages its models. A blank
 * [providerId] starts a new provider as an in-memory draft: the models section is hidden, 保存
 * persists it and switches the screen to edit mode in place (models appear, no navigation), and
 * leaving with a savable draft asks for confirmation first.
 */
@Composable
fun ModelProviderDetailScreen(
    providerId: String,
    onOpenModel: (providerId: String, modelId: String) -> Unit,
    onBack: () -> Unit,
) {
    val creating = providerId.isBlank()
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    var provider by remember { mutableStateOf<ModelProviderEntity?>(null) }
    var showDeleteProviderConfirm by remember { mutableStateOf(false) }
    // Creation only: the id assigned by 保存. Saveable so that re-entering this entry after the
    // in-place save (e.g. back from a model page) reloads the saved provider, not a fresh draft.
    var savedId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(providerId, savedId) {
        provider = when {
            creating && savedId.isNotBlank() -> WeAgentRepository.getModelProvider(savedId)
            creating -> ModelProviderEntity("", ModelProviderType.OPENAI_CHAT_COMPLETION, "", "https://api.openai.com/v1", "")
            else -> WeAgentRepository.getModelProvider(providerId)
        }
    }

    val p = provider

    // True once the entity exists in Room: loaded for edit, or assigned by 保存 during creation.
    // The route id stays blank after in-place creation, so this — not `creating` — gates edit mode.
    val editing = !creating || savedId.isNotBlank()

    // The id backing this screen: the route's, or the one assigned on save during creation (the
    // route entry keeps its blank id, so the models list must key off this instead).
    val activeId = if (creating) savedId else providerId
    val models by remember(activeId) { WeAgentRepository.observeModelsForProvider(activeId) }
        .collectAsState(initial = emptyList())
    // Auto-import state: fetched ids to pick from, plus loading.
    var importCandidates by remember { mutableStateOf<List<String>?>(null) }
    var importing by remember { mutableStateOf(false) }

    /**
     * Every confirmed row edit of an existing provider is persisted immediately — there is no
     * draft state and no save button in edit mode; during creation, edits only update the draft.
     * The API key is stored exactly as typed (no encryption anywhere in the pipeline).
     */
    fun commitProvider(transform: (ModelProviderEntity) -> ModelProviderEntity) {
        val current = provider ?: return
        if (current.type == ModelProviderType.LOCAL_LLAMA) return
        if (!editing) {
            provider = transform(current)
            return
        }
        scope.launch {
            val updated = transform(current)
            WeAgentRepository.upsertModelProvider(updated)
            ModelProviderManager.invalidate(current.id)
            // Keep the local copy in sync so the scaffold title reflects a rename
            // (LaunchedEffect(providerId) only runs once, on first composition).
            provider = updated
        }
    }

    val savable = p?.baseUrl?.isNotBlank() == true
    val noncanonicalLocal = p?.type == ModelProviderType.LOCAL_LLAMA
    val guardedBack = rememberCreationBackGuard(!editing && savable, onBack)

    AgentSettingsScaffold(
        title = if (!editing) stringResource(R.string.agent_add_model_provider)
        else p?.name ?: stringResource(R.string.agent_provider_fallback_title),
        onBack = guardedBack,
    ) {
        if (p == null) {
            item {
                Box(
                    Modifier.fillParentMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            return@AgentSettingsScaffold
        }

        item {
            SegmentedColumn(title = stringResource(R.string.agent_section_connection)) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.agent_field_name),
                        value = p.name,
                        onValueChange = { value -> commitProvider { it.copy(name = value) } },
                        dialogTitle = stringResource(R.string.agent_field_name),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = !noncanonicalLocal,
                    )
                }
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.agent_base_url),
                        value = p.baseUrl,
                        onValueChange = { value -> commitProvider { it.copy(baseUrl = value) } },
                        dialogTitle = stringResource(R.string.agent_base_url),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = !noncanonicalLocal,
                        keyboardType = KeyboardType.Uri,
                    )
                }
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.agent_api_key_label),
                        value = p.apiKey,
                        onValueChange = { value -> commitProvider { it.copy(apiKey = value) } },
                        dialogTitle = stringResource(R.string.agent_api_key_label),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = !noncanonicalLocal,
                        keyboardType = KeyboardType.Password,
                        password = true,
                    )
                }
                item {
                    DropDownMenuWidget(
                        icon = null,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_provider_api_type),
                        description = null,
                        value = p.type,
                        options = (GENERIC_MODEL_PROVIDER_TYPES +
                                listOfNotNull(ModelProviderType.LOCAL_LLAMA.takeIf { noncanonicalLocal }))
                            .map { DropdownOption(it, it.label()) },
                        enabled = !noncanonicalLocal,
                        onValueChange = { value -> commitProvider { it.copy(type = value) } },
                    )
                }
            }
        }

        if (!editing) {
            item {
                // A blank name falls back to the provider type label, like the old add dialog did.
                val fallbackName = p.type.label()
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.action_save),
                        icon = MaterialSymbols.Outlined.Save,
                        enabled = savable,
                        onClick = {
                            val draft = p
                            scope.launch {
                                val saved = draft.copy(
                                    id = UUID.randomUUID().toString(),
                                    name = draft.name.ifBlank { fallbackName },
                                )
                                WeAgentRepository.upsertModelProvider(saved)
                                // Stay in place: assigning the id switches the screen to edit mode
                                // and reveals the models section.
                                savedId = saved.id
                                provider = saved
                            }
                        },
                    )
                }
            }
        } else if (!noncanonicalLocal) {
            item {
                AgentActionRow {
                    OutlinedButton(
                        onClick = { showDeleteProviderConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.action_delete)) }
                }
            }

            item { ModelSectionTitle(stringResource(R.string.agent_section_models)) }
            if (models.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.agent_empty_models_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
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
                            onClick = { onOpenModel(activeId, m.id) },
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
                        onClick = { onOpenModel(activeId, "") },
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
                                    val result = ModelProviderManager.listRemoteModels(p)
                                    importing = false
                                    result.fold(
                                        // distinct(): duplicate ids would produce duplicate LazyColumn keys in the import picker
                                        onSuccess = { importCandidates = it.distinct() },
                                        onFailure = {
                                            showToast(
                                                localizedContext.getString(
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
    }

    if (p != null && !noncanonicalLocal) {
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
                        showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
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
                val (added, overwritten) = WeAgentRepository.importModels(activeId, picked)
                showToast(
                    localizedContext.getString(
                        R.string.agent_models_imported_result, added, overwritten
                    )
                )
            }
            importCandidates = null
        },
    )
}

private val GENERIC_MODEL_PROVIDER_TYPES =
    ModelProviderType.entries.filterNot { it == ModelProviderType.LOCAL_LLAMA }

/**
 * Per-model settings. Editing is instant-apply; a blank [modelId] starts a new model kept as an
 * in-memory draft: other rows stay disabled until a model id is entered, 保存 persists it and
 * returns to the provider page, and leaving with a savable draft asks for confirmation first.
 */
@Composable
fun ModelDetailScreen(providerId: String, modelId: String, onBack: () -> Unit) {
    val creating = modelId.isBlank()
    val locked = providerId == LocalLlama.PROVIDER_ID
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    // Blank modelId = adding (a draft until saved); otherwise null until the entity loads.
    var model by remember { mutableStateOf<ModelEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCtxDialog by remember { mutableStateOf(false) }
    var localCommitPending by remember { mutableStateOf(false) }

    LaunchedEffect(modelId) {
        model = if (creating) {
            ModelEntity("", providerId, "", null, null, "", null)
        } else {
            WeAgentRepository.getModel(modelId)
        }
    }

    /** Persists one field immediately in edit mode; during creation the edit only updates the draft. */
    fun commitModel(transform: (ModelEntity) -> ModelEntity) {
        val current = model ?: return
        if (creating) {
            if (locked) return
            model = transform(current)
            return
        }
        if (locked) {
            if (localCommitPending) return
            val updated = transform(current).copy(providerId = providerId)
            model = updated
            localCommitPending = true
            scope.launch {
                try {
                    WeAgentRepository.upsertModel(updated)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model = current
                    showToast(
                        localizedContext.getString(
                            R.string.agent_save_failed,
                            e.message ?: e.javaClass.simpleName,
                        )
                    )
                } finally {
                    localCommitPending = false
                }
            }
            return
        }
        scope.launch {
            val updated = transform(current).copy(
                id = current.id.ifEmpty { UUID.randomUUID().toString() },
                providerId = providerId,
            )
            WeAgentRepository.upsertModel(updated)
            model = updated
        }
    }

    val m = model
    val installedLocalModel = remember(locked, m?.modelIdRemote) {
        if (locked) {
            LocalLlamaModels.listInstalled().firstOrNull { it.id == m?.modelIdRemote }
        } else {
            null
        }
    }
    // Other fields describe a concrete remote model, so they wait for a non-blank model id.
    val ready = m?.modelIdRemote?.isNotBlank() == true
    val guardedBack = rememberCreationBackGuard(creating && ready, onBack)

    AgentSettingsScaffold(
        title = stringResource(if (creating) R.string.agent_add_model else R.string.agent_edit_model),
        onBack = guardedBack,
    ) {
        if (m == null) {
            item {
                Box(
                    Modifier.fillParentMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            return@AgentSettingsScaffold
        }

        item {
            SegmentedColumn {
                item {
                    if (locked) {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_model_id_label),
                            description = m.modelIdRemote,
                        )
                    } else {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_model_id_label),
                            value = m.modelIdRemote,
                            onValueChange = { value ->
                                commitModel { raw ->
                                    val next = raw.copy(modelIdRemote = value)
                                    if (next.displayName.isBlank() || next.displayName == next.modelIdRemote) {
                                        next.copy(displayName = value)
                                    } else {
                                        next
                                    }
                                }
                            },
                            dialogTitle = stringResource(R.string.agent_model_id_label),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                        )
                    }
                }
                item {
                    if (locked) {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_model_display_name_label),
                            description = m.displayName,
                        )
                    } else {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_model_display_name_label),
                            value = m.displayName,
                            onValueChange = { value -> commitModel { it.copy(displayName = value) } },
                            dialogTitle = stringResource(R.string.agent_model_display_name_label),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            enabled = ready,
                        )
                    }
                }
                item {
                    DropDownMenuWidget(
                        icon = null,
                        iconPlaceholder = false,
                        title = stringResource(R.string.agent_reasoning_effort),
                        description = null,
                        value = m.reasoningEffort ?: "off",
                        options = EFFORT_GEARS.map { DropdownOption(it, effortGearLabel(it)) },
                        enabled = ready && (!locked || !localCommitPending),
                        onValueChange = { value ->
                            commitModel { it.copy(reasoningEffort = value.takeIf { it != "off" }) }
                        },
                    )
                }
                item {
                    if (locked) {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_context_window_label),
                            description = (m.contextWindow
                                ?: installedLocalModel?.defaultContextWindow
                                ?: 32768).toString() + " · " +
                                    stringResource(R.string.local_llm_backend_restart_note),
                            enabled = ready && !localCommitPending,
                            onClick = { showCtxDialog = true },
                        )
                    } else {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_context_window_label),
                            value = m.contextWindow?.toString().orEmpty(),
                            onValueChange = { value ->
                                commitModel { it.copy(contextWindow = value.filter(Char::isDigit).take(9).toIntOrNull()) }
                            },
                            dialogTitle = stringResource(R.string.agent_context_window_label),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            enabled = ready,
                            keyboardType = KeyboardType.Number,
                            filter = { it.filter(Char::isDigit).take(9) },
                        )
                    }
                }
                item {
                    if (locked) {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_max_output_tokens_label),
                            description = (installedLocalModel?.maxTokens ?: m.maxTokens)?.toString().orEmpty(),
                        )
                    } else {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_max_output_tokens_label),
                            value = m.maxTokens?.toString().orEmpty(),
                            onValueChange = { value ->
                                commitModel { it.copy(maxTokens = value.filter(Char::isDigit).take(9).toIntOrNull()) }
                            },
                            dialogTitle = stringResource(R.string.agent_max_output_tokens_label),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            enabled = ready,
                            keyboardType = KeyboardType.Number,
                            filter = { it.filter(Char::isDigit).take(9) },
                        )
                    }
                }
                if (!locked) {
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.agent_custom_json_label),
                            value = m.customJsonOverride.orEmpty(),
                            onValueChange = { value -> commitModel { it.copy(customJsonOverride = value.ifBlank { null }) } },
                            dialogTitle = stringResource(R.string.agent_custom_json_label),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            enabled = ready,
                            singleLine = false,
                        )
                    }
                    item {
                        SwitchWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.agent_supports_vision),
                            description = stringResource(R.string.agent_supports_vision_summary),
                            enabled = ready,
                            checked = m.supportsVision,
                            onCheckedChange = { value -> commitModel { it.copy(supportsVision = value) } },
                        )
                    }
                }
            }
        }

        if (creating && !locked) {
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.action_save),
                        icon = MaterialSymbols.Outlined.Save,
                        enabled = ready,
                        onClick = {
                            val draft = m
                            scope.launch {
                                WeAgentRepository.upsertModel(
                                    draft.copy(id = UUID.randomUUID().toString(), providerId = providerId),
                                )
                                onBack()
                            }
                        },
                    )
                }
            }
        } else if (!locked) {
            item {
                AgentActionRow {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }

    if (locked && m != null && installedLocalModel != null) {
        LocalCtxDialog(
            show = showCtxDialog,
            initial = m.contextWindow ?: installedLocalModel.defaultContextWindow,
            defaultValue = installedLocalModel.defaultContextWindow,
            maxValue = installedLocalModel.maxContextWindow,
            onDismiss = { showCtxDialog = false },
            onConfirm = { contextWindow ->
                showCtxDialog = false
                commitModel { it.copy(contextWindow = contextWindow) }
            },
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
            scope.launch {
                try {
                    model?.id?.takeIf { it.isNotBlank() }?.let { WeAgentRepository.deleteModel(it) }
                    onBack()
                } catch (e: Exception) {
                    showToast(localizedContext.getString(R.string.agent_delete_failed, e.message))
                }
            }
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun LocalCtxDialog(
    show: Boolean,
    initial: Int,
    defaultValue: Int,
    maxValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    if (!show) return
    var value by remember(initial) { mutableStateOf(initial.toString()) }
    val parsed = value.toIntOrNull()
    val valid = parsed != null && parsed in LOCAL_LLAMA_MIN_CONTEXT_WINDOW..maxValue

    BasicAlertDialog(onDismissRequest = onDismiss) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.agent_context_window_label)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.local_llm_ctx_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filter(Char::isDigit).take(9) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = !valid,
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.local_llm_ctx_bounds,
                                    LOCAL_LLAMA_MIN_CONTEXT_WINDOW,
                                    maxValue,
                                    defaultValue,
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = { onConfirm(requireNotNull(parsed)) },
                ) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
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
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    lazySegmentedItems(candidates, key = { it }) { id ->
                        val already = id in existingRemoteIds
                        val checked = id in selected
                        // The whole row toggles; the checkbox is a pure indicator with no semantics of its own.
                        BaseWidget(
                            iconPlaceholder = false,
                            title = if (already) stringResource(R.string.agent_model_already_added, id) else id,
                            onClick = { if (id in selected) selected.remove(id) else selected.add(id) },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    modifier = Modifier.clearAndSetSemantics { },
                                )
                            },
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
