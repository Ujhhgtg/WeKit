package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitWindowDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import kotlinx.coroutines.launch
import java.util.UUID

/** Lists model providers; opens each for editing; adds a new one via dialog (§5.1/§5.2). */
@Composable
fun ModelProvidersScreen(
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
) {
    val providers by WeAgentRepository.observeModelProviders().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val showAdd = remember { mutableStateOf(false) }

    AgentSettingsScaffold(title = stringResource(R.string.agent_model_providers_title), onBack = onBack) {
        if (providers.isEmpty()) {
            item { EmptyHint(stringResource(R.string.agent_model_providers_empty)) }
        }
        items(providers.size, key = { providers[it].id }) { i ->
            val p = providers[i]
            SegmentedColumn {
                item {
                    BaseWidget(
                        title = p.name.ifBlank { p.baseUrl },
                        description = stringResource(R.string.agent_provider_summary, p.type.label(), p.baseUrl),
                        onClick = { onOpenProvider(p.id) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
        item {
            Button(
                onClick = { showAdd.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text(stringResource(R.string.agent_add_provider)) }
        }
    }

    AddProviderDialog(showAdd) { name, type, baseUrl, apiKey ->
        scope.launch {
            WeAgentRepository.upsertModelProvider(
                ModelProviderEntity(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                )
            )
        }
    }
}

@Composable
private fun AddProviderDialog(
    show: androidx.compose.runtime.MutableState<Boolean>,
    onConfirm: (name: String, type: ModelProviderType, baseUrl: String, apiKey: String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    var baseUrl by remember(show.value) { mutableStateOf("https://api.openai.com/v1") }
    var apiKey by remember(show.value) { mutableStateOf("") }
    var typeIndex by remember(show.value) { mutableIntStateOf(0) }
    // API keys are stored in the clear, so at least don't render them in the clear.
    var showApiKey by remember(show.value) { mutableStateOf(false) }
    val types = listOf(
        ModelProviderType.OPENAI_CHAT_COMPLETION,
        ModelProviderType.OPENAI_RESPONSES,
        ModelProviderType.ANTHROPIC_MESSAGES,
        ModelProviderType.GEMINI_GENERATE_CONTENT,
        ModelProviderType.GEMINI_INTERACTIONS
    )
    val selectedType = types[typeIndex]
    val typeLabels = types.map { it.label() }
    val selectedTypeLabel = typeLabels[typeIndex]

    WeKitWindowDialog(
        show = show.value,
        title = stringResource(R.string.agent_add_model_provider),
        onDismissRequest = { show.value = false },
    ) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.agent_base_url)) },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.external_service_api_key)) },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) MaterialSymbols.Outlined.Visibility_off
                            else MaterialSymbols.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (showApiKey) R.string.accessibility_hide else R.string.accessibility_show,
                            ),
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            AgentDropdownRow(
                title = stringResource(R.string.agent_provider_api_type),
                items = typeLabels,
                selectedIndex = typeIndex,
                onSelectedIndexChange = { typeIndex = it },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { show.value = false },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        onConfirm(name.ifBlank { selectedTypeLabel }, selectedType, baseUrl, apiKey)
                        show.value = false
                    },
                    enabled = baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_add)) }
            }
        }
    }
}

@Composable
fun ModelProviderType.label(): String = stringResource(when (this) {
    ModelProviderType.OPENAI_CHAT_COMPLETION -> R.string.agent_provider_type_openai_chat_completion
    ModelProviderType.OPENAI_RESPONSES -> R.string.agent_provider_type_openai_responses
    ModelProviderType.ANTHROPIC_MESSAGES -> R.string.agent_provider_type_anthropic_messages
    ModelProviderType.GEMINI_GENERATE_CONTENT -> R.string.agent_provider_type_gemini_generate_content
    ModelProviderType.GEMINI_INTERACTIONS -> R.string.agent_provider_type_gemini_interactions
})
