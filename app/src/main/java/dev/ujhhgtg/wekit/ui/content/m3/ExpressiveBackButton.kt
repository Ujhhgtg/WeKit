
package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import dev.ujhhgtg.wekit.R

/** Whether scrolled content currently sits under the enclosing top bar's blur area. */
val LocalTopBarContentOverlapped = compositionLocalOf { false }

@Composable
fun ExpressiveBackButton(
    modifier: Modifier = Modifier,
    icon: ImageVector = MaterialSymbols.Outlined.Arrow_back,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String = stringResource(R.string.accessibility_back),
    onClick: () -> Unit,
) {
    // Drop the tonal background once content scrolls beneath the translucent bar, so the button
    // doesn't read as an opaque blob over the blurred content.
    val animatedContainerColor by animateColorAsState(
        targetValue = if (LocalTopBarContentOverlapped.current) containerColor.copy(alpha = 0f) else containerColor,
        label = "backButtonContainer",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = animatedContainerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
