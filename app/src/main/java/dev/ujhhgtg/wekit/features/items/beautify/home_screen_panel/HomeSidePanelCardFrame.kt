package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Edit
import dev.ujhhgtg.wekit.R

@Composable
internal fun HomeSidePanelCardFrame(
    cardId: String,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    colors: CardColors,
    editMode: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    key(cardId) {
        Box(modifier = modifier) {
            Card(
                modifier = cardModifier,
                shape = shape,
                colors = colors,
            ) {
                content()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            ) {
                HomeSidePanelCardBadge(
                    editMode = editMode,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
internal fun HomeSidePanelCardBadge(
    editMode: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    if (!editMode || (onEdit == null && onDelete == null)) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 2.dp,
    ) {
        Row {
            onEdit?.let { edit ->
                HomeSidePanelBadgeButton(
                    onClick = edit,
                    contentDescription = stringResource(R.string.action_edit),
                    icon = MaterialSymbols.Outlined.Edit,
                )
            }
            onDelete?.let { delete ->
                HomeSidePanelBadgeButton(
                    onClick = delete,
                    contentDescription = stringResource(R.string.action_delete),
                    icon = MaterialSymbols.Outlined.Close,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelBadgeButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
    }
}
