// SPDX-License-Identifier: GPL-3.0-only
package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.wekit.ui.content.WeColorPickerDialog
import dev.ujhhgtg.wekit.ui.content.checkerboard
import dev.ujhhgtg.wekit.ui.content.formatArgbHex
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

@Composable
fun ColorPickerWidget(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconPlaceholder: Boolean = false,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val color = value.toColorInt()
    val showPicker = {
        showComposeDialog(context) {
            WeColorPickerDialog(
                initial = color,
                onDismiss = onDismiss,
                onConfirm = { picked ->
                    onValueChange(formatArgbHex(picked))
                    onDismiss()
                },
            )
        }
    }

    BaseWidget(
        modifier = modifier,
        icon = icon,
        iconPlaceholder = iconPlaceholder,
        title = title,
        description = value,
        enabled = enabled,
        onClick = showPicker,
        onTrailingClick = showPicker,
        trailingContent = {
            Box(
                Modifier
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .checkerboard(4.dp)
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        },
    )
}
