@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import dev.ujhhgtg.wekit.R

@Composable
fun ExpressiveBackButton(
    modifier: Modifier = Modifier,
    icon: ImageVector = MaterialSymbols.Outlined.Arrow_back,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String = stringResource(R.string.accessibility_back),
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
