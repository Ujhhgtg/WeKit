package dev.ujhhgtg.wekit.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// drop-in replacement for AlertDialog that should be used in showComposeDialog()
// to avoid creating multiple Windows
@Composable
fun AlertDialogContent(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = modifier
            .padding(18.dp)
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            icon()
                        }
                    }
                }
                title?.let {
                    val customStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                    CompositionLocalProvider(LocalTextStyle provides customStyle) {
                        it()
                    }
                }
            }

            HorizontalDivider()

            text?.let {
                val bodyStyle = MaterialTheme.typography.bodyMedium
                val bodyColor = MaterialTheme.colorScheme.onSurface

                Box(modifier = Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(
                        LocalTextStyle provides bodyStyle,
                        LocalContentColor provides bodyColor
                    ) {
                        it()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                val buttonTextStyle = MaterialTheme.typography.labelLarge
                CompositionLocalProvider(LocalTextStyle provides buttonTextStyle) {
                    dismissButton?.invoke()
                    confirmButton?.invoke()
                }
            }
        }
    }
}
