@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.ui.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemElevation
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.ListItem as MaterialListItem

/**
 * Compatibility wrapper around [MaterialListItem].
 */
@Composable
inline fun ListItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    noinline leadingContent: @Composable (() -> Unit)? = null,
    noinline trailingContent: @Composable (() -> Unit)? = null,
    noinline overlineContent: @Composable (() -> Unit)? = null,
    noinline supportingContent: @Composable (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = ListItemDefaults.verticalAlignment(),
    shapes: ListItemShapes = ListItemDefaults.shapes(),
    colors: ListItemColors = ListItemDefaults.colors(),
    elevation: ListItemElevation = ListItemDefaults.elevation(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    noinline content: @Composable () -> Unit,
) {
    MaterialListItem(
        modifier = modifier,
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = verticalAlignment,
        shapes = shapes,
        colors = colors,
        elevation = elevation,
        contentPadding = contentPadding,
        content = content
    )
}
