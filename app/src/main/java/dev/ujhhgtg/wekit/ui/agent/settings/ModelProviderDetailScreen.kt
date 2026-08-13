package dev.ujhhgtg.wekit.ui.agent.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitWindowDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import java.util.UUID

/** Edits one provider (name/url/key) and manages its models (id + reasoning gear + custom JSON). */
@Composable
fun ModelProviderDetailScreen(providerId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var provider by remember { mutableStateOf<ModelProviderEntity?>(null) }

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

        item { SmallTitle(stringResource(R.string.agent_section_connection)) }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    TextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.agent_field_name), useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = stringResource(R.string.agent_base_url), useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = stringResource(R.string.agent_api_key_label),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
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
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.agent_delete_provider),
                            onClick = { scope.launch { WeAgentRepository.deleteModelProvider(p.id); onBack() } },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            text = stringResource(R.string.action_save),
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
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item { SmallTitle(stringResource(R.string.agent_section_models)) }
        if (models.isEmpty()) item { EmptyHint(stringResource(R.string.agent_models_empty)) }
        items(models.size, key = { models[it].id }) { i ->
            val m = models[i]
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = m.displayName.ifBlank { m.modelIdRemote },
                    summary = "id=${m.modelIdRemote}" +
                            (m.reasoningEffort?.let { " · effort=$it" } ?: "") +
                            (m.contextWindow?.let { " · ctx=$it" } ?: "") +
                            (m.maxTokens?.let { " · max=$it" } ?: "") +
                            if (m.supportsVision) " · ${stringResource(R.string.agent_model_supports_vision_badge)}" else "",
                    onClick = { editingModel = m }
                )
            }
        }
        item {
            Button(
                onClick = { editingModel = ModelEntity("", providerId, "", null, null, "", null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) { Text(stringResource(R.string.agent_add_model)) }
        }
        // Auto-import is only meaningful for the OpenAI-style /models endpoint.
        if (p.type != ModelProviderType.ANTHROPIC_MESSAGES) {
            item {
                TextButton(
                    text = stringResource(if (importing) R.string.agent_fetching_models else R.string.agent_auto_import_models),
                    enabled = !importing,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                )
            }
        }
    }

    ImportModelsDialog(
        show = importCandidates != null,
        candidates = importCandidates.orEmpty(),
        existingRemoteIds = models.map { it.modelIdRemote }.toSet(),
        onDismiss = { importCandidates = null },
        onImport = { picked ->
            scope.launch {
                val added = WeAgentRepository.importModels(providerId, picked)
                showToast(currentAgentLocalizedContext(context).getString(R.string.agent_models_imported, added))
            }
            importCandidates = null
        },
    )

    ModelDialog(
        show = editingModel != null,
        existing = editingModel ?: ModelEntity("", providerId, "", null, null, "", null),
        onDismiss = { editingModel = null },
        onDelete = editingModel?.id?.takeIf { it.isNotEmpty() }?.let { id -> { scope.launch { WeAgentRepository.deleteModel(id) }; editingModel = null } },
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
 * are shown checked+disabled; the rest start selected. Confirming imports the selected new ones.
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

    WeKitWindowDialog(show = show, title = stringResource(R.string.agent_import_models_title, candidates.size), onDismissRequest = onDismiss) {
        Column {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.agent_provider_returned_no_models))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates.size, key = { candidates[it] }) { i ->
                        val id = candidates[i]
                        val already = id in existingRemoteIds
                        val checked = already || id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !already) { if (id in selected) selected.remove(id) else selected.add(id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (checked) "☑" else "☐", modifier = Modifier.width(28.dp))
                            Text(
                                if (already) stringResource(R.string.agent_model_already_added, id) else id,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.agent_import_selected_models, selected.size),
                    onClick = { onImport(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ModelDialog(
    show: Boolean,
    existing: ModelEntity,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (remoteId: String, display: String, effort: String?, customJson: String?, contextWindow: Int?, maxTokens: Int?, supportsVision: Boolean) -> Unit,
) {
    // Every field is keyed on [existing] (and [show]): the dialog is composed unconditionally, with a
    // blank placeholder entity while nothing is being edited, so unkeyed state would capture those
    // blanks and 保存 would then overwrite the stored model's whole config with them.
    var remoteId by remember(existing, show) { mutableStateOf(existing.modelIdRemote) }
    var display by remember(existing, show) { mutableStateOf(existing.displayName) }
    var customJson by remember(existing, show) { mutableStateOf(existing.customJsonOverride.orEmpty()) }
    var contextWindow by remember(existing, show) { mutableStateOf(existing.contextWindow?.toString().orEmpty()) }
    var maxTokens by remember(existing, show) { mutableStateOf(existing.maxTokens?.toString().orEmpty()) }
    var supportsVision by remember(existing, show) { mutableStateOf(existing.supportsVision) }
    var effortIndex by remember(existing, show) { mutableIntStateOf(EFFORT_GEARS.indexOf(existing.reasoningEffort ?: "off").coerceAtLeast(0)) }

    WeKitWindowDialog(
        show = show,
        title = stringResource(if (existing.id.isEmpty()) R.string.agent_add_model else R.string.agent_edit_model),
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(value = remoteId, onValueChange = { remoteId = it }, label = stringResource(R.string.agent_model_id_label), useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextField(value = display, onValueChange = { display = it }, label = stringResource(R.string.agent_model_display_name_label), useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            WindowDropdownPreference(
                title = stringResource(R.string.agent_reasoning_effort),
                items = EFFORT_GEARS.map { effortGearLabel(it) },
                selectedIndex = effortIndex,
                onSelectedIndexChange = { effortIndex = it },
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = contextWindow,
                onValueChange = { v -> contextWindow = v.filter { it.isDigit() }.take(9) },
                label = stringResource(R.string.agent_context_window_label),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = maxTokens,
                onValueChange = { v -> maxTokens = v.filter { it.isDigit() }.take(9) },
                label = stringResource(R.string.agent_max_output_tokens_label),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(value = customJson, onValueChange = { customJson = it }, label = stringResource(R.string.agent_custom_json_label), useLabelAsPlaceholder = true, maxLines = 4)
            Spacer(Modifier.height(8.dp))
            SwitchPreference(
                title = stringResource(R.string.agent_supports_vision),
                summary = stringResource(R.string.agent_supports_vision_summary),
                checked = supportsVision,
                onCheckedChange = { supportsVision = it },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                if (onDelete != null) {
                    TextButton(text = stringResource(R.string.action_delete), onClick = onDelete, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        val effort = EFFORT_GEARS[effortIndex].takeIf { it != "off" }
                        onSave(remoteId, display, effort, customJson.ifBlank { null }, contextWindow.toIntOrNull(), maxTokens.toIntOrNull(), supportsVision)
                    },
                    enabled = remoteId.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
