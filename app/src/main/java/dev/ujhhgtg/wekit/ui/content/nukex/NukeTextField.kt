package dev.ujhhgtg.wekit.ui.content.nukex

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NukeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = if (singleLine) 1 else 1,
    maxLines: Int = if (singleLine) 1 else 5,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = NukeTheme.colors
    var focused by remember { mutableStateOf(false) }
    val hasText = value.isNotEmpty()

    val cornerRadius by animateDpAsState(
        targetValue = if (focused) 13.dp else 11.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "TextFieldCornerRadius",
    )
    val borderThickness by animateDpAsState(
        targetValue = if (focused && enabled) 2.dp else 0.dp,
        animationSpec = tween(140),
        label = "TextFieldBorderThickness",
    )
    val scaleX by animateFloatAsState(
        targetValue = if (focused && enabled) 0.996f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "TextFieldScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (focused && enabled) 1.012f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "TextFieldScaleY",
    )
    val contentOffset by animateDpAsState(
        targetValue = if (focused && enabled) 1.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "TextFieldContentOffset",
    )
    val placeholderAlpha by animateFloatAsState(
        targetValue = when {
            hasText -> 0f
            focused -> 0.52f
            else -> 0.68f
        },
        animationSpec = tween(160),
        label = "TextFieldPlaceholderAlpha",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.45f
            focused || hasText -> 1f
            else -> 0.72f
        },
        animationSpec = tween(160),
        label = "TextFieldIconAlpha",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused && enabled) {
            colors.accent
        } else {
            colors.accent.copy(alpha = 0f)
        },
        animationSpec = tween(180),
        label = "TextFieldBorderColor",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surface.copy(alpha = 0.58f)
            colors.isLight -> Color.Black.copy(alpha = 0.05f)
            else -> Color.Black.copy(alpha = 0.35f)
        },
        animationSpec = tween(180),
        label = "TextFieldContainerColor",
    )

    val shape = NukeSquircleShape(cornerRadius)
    val selectionColors = remember(colors.accent) {
        TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = 0.20f),
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = {
                onValueChange(
                    if (singleLine) {
                        it.replace('\n', ' ').replace('\r', ' ')
                    } else {
                        it
                    }
                )
            },
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .heightIn(min = if (singleLine) 42.dp else 48.dp)
                .graphicsLayer {
                    alpha = if (enabled) 1f else 0.62f
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
                .clip(shape)
                .background(containerColor)
                .border(borderThickness, borderColor, shape)
                .padding(
                    horizontal = 12.dp,
                    vertical = if (singleLine) 9.dp else 11.dp,
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = TextStyle(
                color = if (enabled) colors.textPrimary else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .offset(x = contentOffset)
                        .fillMaxWidth(),
                    verticalAlignment = if (singleLine) {
                        Alignment.CenterVertically
                    } else {
                        Alignment.Top
                    },
                ) {
                    if (leadingContent != null) {
                        Box(Modifier.graphicsLayer { alpha = iconAlpha }) {
                            leadingContent()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 18.dp),
                        contentAlignment = if (singleLine) {
                            Alignment.CenterStart
                        } else {
                            Alignment.TopStart
                        },
                    ) {
                        if (!hasText && placeholder.isNotEmpty()) {
                            NukeText(
                                text = placeholder,
                                modifier = Modifier.graphicsLayer { alpha = placeholderAlpha },
                                color = if (focused) colors.accent else colors.textSecondary,
                                fontSize = 14,
                                lineHeight = 18,
                                maxLines = if (singleLine) 1 else maxLines,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.graphicsLayer { alpha = iconAlpha }) {
                            trailingContent()
                        }
                    }
                }
            },
        )
    }
}

@Composable
fun NukeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
) {
    NukeTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingContent = {
            NukeGlyph(
                kind = NukeGlyphKind.Search,
                color = NukeTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
fun NukeDialogTextField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    NukeText(
        text = label,
        color = NukeTheme.colors.textSecondary,
        fontSize = 13,
        lineHeight = 18,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(8.dp))
    NukeTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}
