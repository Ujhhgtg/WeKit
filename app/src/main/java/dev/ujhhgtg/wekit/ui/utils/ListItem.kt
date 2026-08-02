package dev.ujhhgtg.wekit.ui.utils

import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem as MaterialListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Compatibility wrapper around [MaterialListItem].
 *
 * material3 1.5.0-alpha24 renamed the main slot of the non-clickable [MaterialListItem] overload
 * from `headlineContent` to a trailing `content` lambda. WeKit's call sites were migrated to
 * `content`, but the project stays on material3 1.5.0-alpha19 where the parameter is still
 * `headlineContent`. This wrapper keeps the `content` name used by all call sites and forwards it
 * to the material3 implementation.
 */
@Composable
fun ListItem(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
) {
    MaterialListItem(
        headlineContent = content,
        modifier = modifier,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    )
}
