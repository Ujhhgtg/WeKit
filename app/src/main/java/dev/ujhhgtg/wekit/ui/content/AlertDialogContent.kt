package dev.ujhhgtg.wekit.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse

// drop-in replacement for AlertDialog that should be used in showComposeDialog()
// to avoid creating 2 Windows for one Dialog.
//
// Mirrors Material 3's AlertDialog content (material3 1.5.0 `AlertDialogContent` /
// `AlertDialogDefaults` / `DialogTokens`): same width bounds, container, paddings, typography,
// colors, and button flow.
//
// Button order follows M3: every button is its own item in a reversed flow row, so within
// confirmButton then dismissButton, the first button written is the rightmost one (and the top
// one when stacked). A slot emitting several buttons lists them from right to left.
@Composable
fun AlertDialogContent(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.sizeIn(minWidth = DialogMinWidth, maxWidth = DialogMaxWidth),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(DialogPadding)) {
            icon?.let {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.secondary,
                ) {
                    Box(Modifier.padding(IconPadding).align(Alignment.CenterHorizontally)) {
                        it()
                    }
                }
            }
            title?.let {
                ProvideContentColorTextStyle(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    textStyle = MaterialTheme.typography.headlineSmall,
                ) {
                    Box(
                        // Align the title to the center when an icon is present.
                        Modifier.padding(TitlePadding)
                            .align(
                                if (icon == null) Alignment.Start else Alignment.CenterHorizontally,
                            ),
                    ) {
                        it()
                    }
                }
            }
            text?.let {
                ProvideContentColorTextStyle(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    textStyle = MaterialTheme.typography.bodyMedium,
                ) {
                    Box(
                        Modifier.weight(weight = 1f, fill = false)
                            .padding(TextPadding)
                            .align(Alignment.Start),
                    ) {
                        it()
                    }
                }
            }
            if (confirmButton != null || dismissButton != null) {
                Box(modifier = Modifier.align(Alignment.End)) {
                    ProvideContentColorTextStyle(
                        contentColor = MaterialTheme.colorScheme.primary,
                        textStyle = MaterialTheme.typography.labelLarge,
                    ) {
                        val buttonPaddingFromMics =
                            LocalMinimumInteractiveComponentSize.current.takeOrElse { 0.dp } -
                                ButtonDefaults.MinHeight
                        DialogButtonsFlowRow(
                            crossAxisSpacing =
                                (ButtonsSpacing - buttonPaddingFromMics).coerceIn(
                                    0.dp,
                                    ButtonsSpacing,
                                ),
                        ) {
                            confirmButton?.invoke()
                            dismissButton?.invoke()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Like M3's `AlertDialogFlowRow`: laid out in the flipped direction so the confirm button, emitted
 * first, sits at the end when side by side and on top when stacked.
 */
@Composable
private fun DialogButtonsFlowRow(crossAxisSpacing: Dp, content: @Composable () -> Unit) {
    val originalLayoutDirection = LocalLayoutDirection.current
    val flipped =
        if (originalLayoutDirection == LayoutDirection.Ltr) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides flipped) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ButtonsSpacing),
            verticalArrangement = Arrangement.spacedBy(crossAxisSpacing),
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides originalLayoutDirection,
                content = content,
            )
        }
    }
}

@Composable
private fun ProvideContentColorTextStyle(
    contentColor: Color,
    textStyle: TextStyle,
    content: @Composable () -> Unit,
) {
    val mergedStyle = LocalTextStyle.current.merge(textStyle)
    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalTextStyle provides mergedStyle,
        content = content,
    )
}

private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp
private val DialogPadding = PaddingValues(all = 24.dp)
private val IconPadding = PaddingValues(bottom = 16.dp)
private val TitlePadding = PaddingValues(bottom = 16.dp)
private val TextPadding = PaddingValues(bottom = 24.dp)
private val ButtonsSpacing = 8.dp
