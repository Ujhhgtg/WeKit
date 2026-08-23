package dev.ujhhgtg.wekit.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Done_all
import com.composables.icons.materialsymbols.outlined.Select_all
import dev.ujhhgtg.wekit.R

@Composable
internal fun <T> panelMultiSelectActions(
    items: List<T>,
    selectedKeys: Set<String>,
    key: (T) -> String,
    terminalIcon: ImageVector,
    terminalLabel: String,
    onClose: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onTerminalAction: (List<T>) -> Unit,
): List<PanelAction> = listOf(
    PanelAction(
        MaterialSymbols.Outlined.Close,
        stringResource(R.string.dialog_close),
        headerStart = true,
        onClick = onClose,
    ),
    PanelAction(
        MaterialSymbols.Outlined.Select_all,
        stringResource(R.string.panel_action_select_all),
        enabled = items.isNotEmpty(),
        showLabel = true,
    ) {
        onSelectionChange(items.mapTo(linkedSetOf(), key))
    },
    PanelAction(
        MaterialSymbols.Outlined.Deselect,
        stringResource(R.string.panel_action_invert_selection),
        enabled = items.isNotEmpty(),
        showLabel = true,
    ) {
        onSelectionChange(invertPanelSelection(selectedKeys, items, key))
    },
    PanelAction(
        MaterialSymbols.Outlined.Done_all,
        stringResource(R.string.panel_action_select_range),
        enabled = selectedKeys.size > 1,
        showLabel = true,
    ) {
        onSelectionChange(closePanelSelectionRange(selectedKeys, items, key))
    },
    PanelAction(
        terminalIcon,
        terminalLabel,
        enabled = selectedKeys.isNotEmpty(),
        showLabel = true,
    ) {
        onTerminalAction(items.filter { key(it) in selectedKeys })
    },
)

internal fun <T> invertPanelSelection(
    current: Set<String>,
    items: List<T>,
    key: (T) -> String,
): Set<String> {
    val candidates = items.mapTo(linkedSetOf(), key)
    return candidates.filterNotTo(linkedSetOf()) { it in current }
}

internal fun <T> closePanelSelectionRange(
    current: Set<String>,
    items: List<T>,
    key: (T) -> String,
): Set<String> {
    val selectedIndexes = items.indices.filter { key(items[it]) in current }
    if (selectedIndexes.size <= 1) return current
    return current + (selectedIndexes.first()..selectedIndexes.last()).map { key(items[it]) }
}
