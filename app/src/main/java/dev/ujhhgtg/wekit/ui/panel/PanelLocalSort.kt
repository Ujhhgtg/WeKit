package dev.ujhhgtg.wekit.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Sort
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.panel.LocalSortMode

@Composable
internal fun panelLocalSortAction(
    mode: LocalSortMode,
    enabled: Boolean = true,
    onModeChange: (LocalSortMode) -> Unit,
    onStartCustomOrder: () -> Unit,
) = PanelAction(
    icon = MaterialSymbols.Outlined.Sort,
    label = stringResource(mode.labelRes),
    enabled = enabled,
    showLabel = true,
    onLongClick = onStartCustomOrder.takeIf { mode == LocalSortMode.CUSTOM && enabled },
    onClick = { onModeChange(mode.next()) },
)

@Composable
internal fun panelReorderActions(
    onCancel: () -> Unit,
    onSave: () -> Unit,
) = listOf(
    PanelAction(
        MaterialSymbols.Outlined.Close,
        stringResource(R.string.dialog_cancel),
        headerStart = true,
        onClick = onCancel,
    ),
    PanelAction(
        MaterialSymbols.Outlined.Save,
        stringResource(R.string.action_save),
        showLabel = true,
        onClick = onSave,
    ),
)

private val LocalSortMode.labelRes: Int
    get() = when (this) {
        LocalSortMode.NAME -> R.string.panel_sort_name
        LocalSortMode.MODIFIED -> R.string.panel_sort_modified
        LocalSortMode.RECENT -> R.string.panel_sort_recent
        LocalSortMode.FREQUENT -> R.string.panel_sort_frequent
        LocalSortMode.CUSTOM -> R.string.panel_sort_custom
    }

internal fun <T> List<T>.moveItem(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
