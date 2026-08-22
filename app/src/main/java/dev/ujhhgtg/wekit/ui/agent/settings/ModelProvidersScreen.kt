package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn

/**
 * Lists model providers; opens each for editing; adds a new one via the detail screen's creation
 * mode (§5.1/§5.2).
 */
@Composable
fun ModelProvidersScreen(
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
) {
    val providers by WeAgentRepository.observeModelProviders().collectAsState(initial = emptyList())

    AgentSettingsScaffold(title = stringResource(R.string.agent_model_providers_title), onBack = onBack) {
        if (providers.isEmpty()) {
            item {
                AgentEmptyState(
                    title = stringResource(R.string.agent_empty_providers_title),
                    message = stringResource(R.string.agent_empty_providers_message),
                    actionLabel = stringResource(R.string.agent_add_provider),
                    onAction = { onOpenProvider("") },
                )
            }
        }
        items(providers, key = { it.id }) { p ->
            SegmentedColumn {
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = p.name.ifBlank { p.baseUrl },
                        description = stringResource(R.string.agent_provider_summary, p.type.label(), p.baseUrl),
                        onClick = { onOpenProvider(p.id) },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
        item {
            AgentActionRow {
                AgentListActionButton(
                    label = stringResource(R.string.agent_add_provider),
                    icon = MaterialSymbols.Outlined.Add,
                    onClick = { onOpenProvider("") },
                )
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
