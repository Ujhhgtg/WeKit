package dev.ujhhgtg.wekit.ui.agent.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.net.ExternalServiceId
import dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
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
    var exaSaving by remember { mutableStateOf(false) }
    var braveSaving by remember { mutableStateOf(false) }
    // Track whether each field has been loaded; show nothing until ready to avoid flicker.
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        exaKey = WeAgentRepository.getExternalServiceKey(ExternalServiceId.EXA) ?: ""
        braveKey = WeAgentRepository.getExternalServiceKey(ExternalServiceId.BRAVE) ?: ""
        loaded = true
    }

    AgentSettingsScaffold(title = stringResource(R.string.agent_external_services_title), onBack = onBack) {
        if (!loaded) {
            item { EmptyHint(stringResource(R.string.common_loading)) }
            return@AgentSettingsScaffold
        }

        item {
            ServiceKeyCard(
                title = stringResource(R.string.external_service_exa_name),
                description = stringResource(R.string.external_service_exa_description),
                key = exaKey,
                saving = exaSaving,
                onKeyChange = { exaKey = it },
                onClear = {
                    exaSaving = true
                    scope.launch {
                        try {
                            WeAgentRepository.setExternalServiceKey(ExternalServiceId.EXA, null)
                            BuiltinToolProvider.exaKeyPresent = false
                        } finally {
                            exaSaving = false
                        }
                    }
                },
                onSave = {
                    exaSaving = true
                    scope.launch {
                        try {
                            WeAgentRepository.setExternalServiceKey(ExternalServiceId.EXA, exaKey)
                            BuiltinToolProvider.exaKeyPresent = exaKey.isNotBlank()
                        } finally {
                            exaSaving = false
                        }
                    }
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }

        item {
            ServiceKeyCard(
                title = stringResource(R.string.external_service_brave_name),
                description = stringResource(R.string.external_service_brave_description),
                key = braveKey,
                saving = braveSaving,
                onKeyChange = { braveKey = it },
                onClear = {
                    braveSaving = true
                    scope.launch {
                        try {
                            WeAgentRepository.setExternalServiceKey(ExternalServiceId.BRAVE, null)
                            BuiltinToolProvider.braveKeyPresent = false
                        } finally {
                            braveSaving = false
                        }
                    }
                },
                onSave = {
                    braveSaving = true
                    scope.launch {
                        try {
                            WeAgentRepository.setExternalServiceKey(ExternalServiceId.BRAVE, braveKey)
                            BuiltinToolProvider.braveKeyPresent = braveKey.isNotBlank()
                        } finally {
                            braveSaving = false
                        }
                    }
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            )
        }
    }
}

/** A card for a single external service showing its name, description, and an API-key input. */
@Composable
private fun ServiceKeyCard(
    title: String,
    description: String,
    key: String,
    saving: Boolean,
    onKeyChange: (String) -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showKey by remember { mutableStateOf(false) }

    BaseItemContainer(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key,
                onValueChange = onKeyChange,
                label = { Text(stringResource(R.string.external_service_api_key)) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) MaterialSymbols.Outlined.Visibility_off
                            else MaterialSymbols.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (showKey) R.string.accessibility_hide else R.string.accessibility_show,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (key.isNotBlank()) {
                    Button(
                        onClick = {
                            onClear()
                            onKeyChange("")
                        },
                        enabled = !saving,
                        modifier = Modifier.width(80.dp),
                    ) { Text(stringResource(R.string.action_clear)) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.width(80.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
