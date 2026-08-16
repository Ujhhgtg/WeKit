package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.net.ExternalServiceId
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import kotlinx.coroutines.launch

/**
 * External Services settings screen — lets the user configure API keys for network tools:
 * Exa Search and Brave Search. Saving a key immediately makes the corresponding tool visible to
 * the model (via [BuiltinToolProvider.exaKeyPresent] / [BuiltinToolProvider.braveKeyPresent]).
 */
@Composable
fun ExternalServicesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var exaKey by remember { mutableStateOf("") }
    var braveKey by remember { mutableStateOf("") }
    // Track whether the keys have been loaded; show nothing until ready to avoid flicker.
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        exaKey = WeAgentRepository.getExternalServiceKey(ExternalServiceId.EXA) ?: ""
        braveKey = WeAgentRepository.getExternalServiceKey(ExternalServiceId.BRAVE) ?: ""
        loaded = true
    }

    /** Confirming a key persists it immediately; confirming an empty key clears it. */
    fun commitKey(id: String, onPresent: (Boolean) -> Unit, value: String) {
        scope.launch {
            WeAgentRepository.setExternalServiceKey(id, value.ifBlank { null })
            onPresent(value.isNotBlank())
        }
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_external_services_title), onBack = onBack) {
        if (!loaded) {
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
            SegmentedColumn(title = stringResource(R.string.external_service_exa_name)) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.external_service_api_key),
                        value = exaKey,
                        onValueChange = { value ->
                            exaKey = value
                            commitKey(ExternalServiceId.EXA, { BuiltinToolProvider.exaKeyPresent = it }, value)
                        },
                        dialogTitle = stringResource(R.string.external_service_api_key),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        keyboardType = KeyboardType.Password,
                        password = true,
                        valueHint = stringResource(R.string.external_service_exa_description),
                    )
                }
            }
        }
        item {
            SegmentedColumn(
                title = stringResource(R.string.external_service_brave_name),
                modifier = Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.external_service_api_key),
                        value = braveKey,
                        onValueChange = { value ->
                            braveKey = value
                            commitKey(ExternalServiceId.BRAVE, { BuiltinToolProvider.braveKeyPresent = it }, value)
                        },
                        dialogTitle = stringResource(R.string.external_service_api_key),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        keyboardType = KeyboardType.Password,
                        password = true,
                        valueHint = stringResource(R.string.external_service_brave_description),
                    )
                }
            }
        }
    }
}
