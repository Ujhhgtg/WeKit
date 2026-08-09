package dev.ujhhgtg.wekit.ui.panel

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Close
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.launch

data class PanelPackChoice(
    val id: String,
    val title: String,
    val itemCount: Int,
)

/**
 * Opens the panel shell in a deliberately narrow mode used by message-menu save actions.
 * The caller can only choose an existing local pack or create one; the full panel navigation
 * is not exposed until the save operation has completed.
 */
fun showPanelPackPicker(
    context: Context,
    title: String,
    createLabel: String,
    itemCountLabel: (Int) -> String,
    packIcon: ImageVector,
    packs: List<PanelPackChoice>,
    onCreatePack: suspend (String) -> Result<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    showPanelDialog(context, onDismiss) {
        PanelPackPickerContent(
            title = title,
            createLabel = createLabel,
            itemCountLabel = itemCountLabel,
            packIcon = packIcon,
            packs = packs,
            onCreatePack = onCreatePack,
            onSelect = onSelect,
            onDismiss = ::dismiss,
        )
    }
}

@Composable
private fun PanelPackPickerContent(
    title: String,
    createLabel: String,
    itemCountLabel: (Int) -> String,
    packIcon: ImageVector,
    packs: List<PanelPackChoice>,
    onCreatePack: suspend (String) -> Result<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentLocalizedContext by rememberUpdatedState(LocalContext.current)
    var creating by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(false) }

    fun selectPack(packId: String) {
        onSelect(packId)
        onDismiss()
    }

    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.dialog_close))
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable(enabled = !creating) { prompt = true },
                            colors = panelListItemColors(),
                            leadingContent = { Icon(MaterialSymbols.Outlined.Add, null) },
                            content = { Text(createLabel) },
                            supportingContent = { Text(stringResource(R.string.panel_pack_picker_create_hint)) },
                        )
                    }
                    items(packs, key = { it.id }) { pack ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !creating) { selectPack(pack.id) },
                            colors = panelListItemColors(),
                            leadingContent = { Icon(packIcon, null) },
                            content = { Text(pack.title) },
                            supportingContent = { Text(itemCountLabel(pack.itemCount)) },
                        )
                    }
                    if (packs.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.panel_pack_picker_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }
            }

            if (prompt) {
                PanelTextPrompt(
                    title = createLabel,
                    label = stringResource(R.string.panel_pack_name),
                    confirmText = stringResource(R.string.panel_action_create),
                    onDismiss = { if (!creating) prompt = false },
                    onConfirm = { name ->
                        if (creating) return@PanelTextPrompt
                        creating = true
                        scope.launch {
                            val result = onCreatePack(name)
                            creating = false
                            result.fold(
                                onSuccess = { packId ->
                                    prompt = false
                                    selectPack(packId)
                                },
                                onFailure = { error ->
                                    showPanelErrorToast(
                                        error.message
                                            ?: currentLocalizedContext.getString(R.string.panel_pack_create_failed),
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

private suspend fun showPanelErrorToast(message: String) {
    showToastSuspend(message)
}
