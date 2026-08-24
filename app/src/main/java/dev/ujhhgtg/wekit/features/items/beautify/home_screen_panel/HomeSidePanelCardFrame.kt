package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    @StringRes editDescriptionRes: Int = R.string.home_side_panel_edit_card,
    @StringRes deleteDescriptionRes: Int = R.string.home_side_panel_delete_card,
    badgeContent: (@Composable () -> Unit)? = null,
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
                if (badgeContent != null) {
                    badgeContent()
                } else {
                    HomeSidePanelCardBadge(
                        editMode = editMode,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        editDescriptionRes = editDescriptionRes,
                        deleteDescriptionRes = deleteDescriptionRes,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelCardBadge(
    editMode: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    @StringRes editDescriptionRes: Int = R.string.home_side_panel_edit_card,
    @StringRes deleteDescriptionRes: Int = R.string.home_side_panel_delete_card,
) {
    val visible = editMode && (onEdit != null || onDelete != null)
    val visibility = remember { MutableTransitionState(false) }
    var retainedEdit by remember { mutableStateOf(onEdit != null) }
    var retainedDelete by remember { mutableStateOf(onDelete != null) }
    SideEffect {
        if (visible) {
            retainedEdit = onEdit != null
            retainedDelete = onDelete != null
        }
    }
    visibility.targetState = visible
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.82f),
        exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = 0.82f),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 2.dp,
        ) {
            Row {
                if (retainedEdit) {
                    HomeSidePanelBadgeButton(
                        onClick = onEdit.takeIf { visible },
                        contentDescription = stringResource(editDescriptionRes),
                        icon = MaterialSymbols.Outlined.Edit,
                    )
                }
                if (retainedDelete) {
                    HomeSidePanelBadgeButton(
                        onClick = onDelete.takeIf { visible },
                        contentDescription = stringResource(deleteDescriptionRes),
                        icon = MaterialSymbols.Outlined.Close,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelBadgeButton(
    onClick: (() -> Unit)?,
    contentDescription: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val modifier = Modifier.size(36.dp)
    if (onClick != null) {
        IconButton(
            onClick = onClick,
            modifier = modifier.semantics { this.contentDescription = contentDescription },
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    } else {
        Box(
            modifier = modifier.clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    }
}
