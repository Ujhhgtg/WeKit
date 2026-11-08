package dev.ujhhgtg.wekit.bootstrap.ui.content.m3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle

@Composable
fun BaseWidget(
    title: String,
    icon: ImageVector,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    colors: ListItemColors =
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
            contentColor = MaterialTheme.colorScheme.onSurface,
            leadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            supportingContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            trailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
) {
    val shape = LocalSegmentedItemShape.current
    val haptic = LocalHapticFeedback.current
    val leading: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null)
    }
    val supporting: (@Composable () -> Unit)? = description?.let { text ->
        { Text(text = text, style = MaterialTheme.typography.bodyMedium) }
    }
    val headline: @Composable () -> Unit = {
        Text(text = title, style = titleStyle)
    }
    if (onClick != null) {
        ListItem(
            selected = false,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            shapes =
                ListItemDefaults.shapes(
                    shape = shape,
                    pressedShape = RoundedCornerShape(CornerRadius),
                    selectedShape = shape,
                    focusedShape = shape,
                    hoveredShape = shape,
                ),
            colors = colors,
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailingContent,
            content = headline,
        )
    } else {
        ListItem(
            modifier = Modifier.clip(shape),
            colors = colors,
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailingContent,
            content = headline,
        )
    }
}
