package dev.ujhhgtg.wekit.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Send
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.EDGE_TTS_VOICES
import dev.ujhhgtg.wekit.features.items.chat.panel.CloneVoice

enum class TtsMode { SYSTEM, EDGE, CLONE }

@Composable
fun TtsContent(
    mode: TtsMode,
    text: String,
    converted: Boolean,
    selectedClone: CloneVoice?,
    selectedEdgeVoice: String,
    onModeChange: (TtsMode) -> Unit,
    onTextChange: (String) -> Unit,
    onSelectEdgeVoice: (String) -> Unit,
    onChooseOrManage: () -> Unit,
    onConvert: () -> Unit,
    onPreviewConverted: () -> Unit,
    onSendConverted: () -> Unit,
    onSynthesize: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { TtsModeOption(stringResource(R.string.tts_mode_system), mode == TtsMode.SYSTEM) { onModeChange(TtsMode.SYSTEM) } }
                item { TtsModeOption(stringResource(R.string.tts_mode_edge), mode == TtsMode.EDGE) { onModeChange(TtsMode.EDGE) } }
                item { TtsModeOption(stringResource(R.string.tts_mode_clone), mode == TtsMode.CLONE) { onModeChange(TtsMode.CLONE) } }
            }
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text(stringResource(R.string.tts_text_label)) },
                supportingText = {
                    Text(stringResource(R.string.tts_text_counter, text.codePointCount(0, text.length), 256))
                },
                trailingIcon = if (text.isNotEmpty()) ({
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.tts_clear_text))
                    }
                }) else null,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (mode == TtsMode.EDGE) {
            item { Text(stringResource(R.string.tts_choose_voice), style = MaterialTheme.typography.titleSmall) }
            items(EDGE_TTS_VOICES, key = { it.id }) { voice ->
                ListItem(
                    modifier = Modifier.clickable { onSelectEdgeVoice(voice.id) },
                    colors = panelListItemColors(),
                    content = { Text(stringResource(voice.titleRes)) },
                    leadingContent = {
                        RadioButton(
                            selected = selectedEdgeVoice == voice.id,
                            onClick = { onSelectEdgeVoice(voice.id) },
                        )
                    },
                )
            }
        } else if (mode == TtsMode.CLONE) {
            item {
                Column {
                    Text(stringResource(R.string.tts_current_voice), style = MaterialTheme.typography.titleSmall)
                    Text(selectedClone?.name ?: stringResource(R.string.panel_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onChooseOrManage) { Text(stringResource(R.string.tts_choose_or_manage_voice)) }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (converted) {
                    OutlinedButton(onClick = onPreviewConverted, modifier = Modifier.weight(1f)) {
                        Icon(MaterialSymbols.Outlined.Play_arrow, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.panel_action_preview), Modifier.padding(start = 8.dp))
                    }
                    Button(onClick = onSendConverted, modifier = Modifier.weight(1f)) {
                        Icon(MaterialSymbols.Outlined.Send, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.panel_action_send), Modifier.padding(start = 8.dp))
                    }
                } else {
                    OutlinedButton(onClick = onConvert, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.tts_convert))
                    }
                    Button(onClick = onSynthesize, modifier = Modifier.weight(1f)) {
                        Icon(MaterialSymbols.Outlined.Send, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.tts_convert_and_send), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick)) {
        RadioButton(selected, onClick)
        Text(label)
    }
}
