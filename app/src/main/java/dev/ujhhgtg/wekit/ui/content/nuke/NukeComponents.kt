package dev.ujhhgtg.wekit.ui.content.nuke

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.min

enum class NukeGlyphKind {
    Send,
    Person,
    Search,
    Star,
    CheckCircle,
    Heart,
    Info,
    Settings,
    Update,
    Code,
    Home,
    Gift,
    Restart,
    Check,
    Error,
    Chevron,
    Back,
    Close,
}

@Composable
fun NukeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: ((Offset) -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = NukeTheme.colors
    val containerColor by animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(160),
        label = "TopAppBarContainerColor",
    )
    val dividerAlpha by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(160),
        label = "TopAppBarDividerAlpha",
    )
    val titleOffset by animateDpAsState(
        targetValue = 2.dp,
        animationSpec = tween(160),
        label = "TopAppBarTitleOffset",
    )
    val titleScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(160),
        label = "TopAppBarTitleScale",
    )
    Box(
        modifier
            .fillMaxWidth()
            .background(containerColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .nukeJellyClickable(
                        role = Role.Button,
                        // The click modifier already gives a root-space coordinate. Keeping that
                        // actual touch origin lets the reveal exit contract use the back button.
                        onClick = onBack,
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                NukeGlyph(
                    kind = NukeGlyphKind.Back,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 2.dp),
                )
            }
        }
        NukeText(
            text = title,
            modifier = Modifier
                .align(Alignment.Center)
                // Keep the title centered in the actual app bar, independent of either side.
                // This also leaves substantially more display width than the former 1/3 column.
                .fillMaxWidth()
                .padding(horizontal = 52.dp)
                .graphicsLayer {
                    translationY = titleOffset.toPx()
                    scaleX = titleScale
                    scaleY = titleScale
                },
            color = colors.textPrimary,
            fontSize = 18,
            lineHeight = null,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer { alpha = dividerAlpha }
                .background(colors.border)
        )
    }
}

private val NukeSettingGroupCornerRadius = 20.dp

/**
 * Shared section title used by [NukeSettingGroup] and by virtualized feature lists whose rows are
 * emitted as individual [androidx.compose.foundation.lazy.LazyColumn] items.
 */
@Composable
fun NukeSettingGroupTitle(
    title: String?,
    modifier: Modifier = Modifier,
) {
    if (title == null) return
    val colors = NukeTheme.colors
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val titleAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(180),
        label = "SettingGroupTitleAlphaAnimation",
    )
    val titleOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 1_500f),
        label = "SettingGroupTitleOffsetAnimation",
    )
    val markWidth by animateDpAsState(
        targetValue = if (entered) 22.dp else 7.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "SettingGroupTitleMarkWidthAnimation",
    )

    Row(
        modifier
            .offset(y = titleOffset)
            .graphicsLayer { alpha = titleAlpha }
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = markWidth, height = 5.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
        NukeText(
            text = title.uppercase(Locale.ROOT),
            modifier = Modifier.padding(start = 8.dp),
            color = colors.accent,
            fontSize = 12,
            lineHeight = 15,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun NukeSettingGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = NukeTheme.colors
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val bodyAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(160),
        label = "SettingGroupAlphaAnimation",
    )
    val bodyOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "SettingGroupOffsetAnimation",
    )

    Column(modifier.fillMaxWidth()) {
        NukeSettingGroupTitle(title = title)
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = bodyOffset)
                .graphicsLayer {
                    alpha = bodyAlpha
                    // A long feature list makes this group thousands of px tall. An offscreen
                    // layer for the enter animation would be clamped to the device texture limit
                    // (~8192px), clipping both drawing and input below that boundary.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                // RoundedCornerShape produces an Outline.Rounded, whose hit test is plain
                // rounded-rect math at any size. NukeSquircleShape's generic Path outline breaks
                // point-in-path tests once the group is taller than ~8192px, leaving deep rows
                // visible but unclickable (around the 29th row on typical 640dpi devices).
                .clip(RoundedCornerShape(NukeSettingGroupCornerRadius))
                .background(colors.surface),
            content = content,
        )
    }
}

/**
 * Per-row card surface for virtualized grouped lists: the first row rounds the top corners, the
 * last row rounds the bottom corners, and middle rows stay square so adjacent rows read as one
 * continuous card. Mirrors the Miuix engine's `groupedCardItem` layout contract.
 */
fun nukeGroupedCardShape(index: Int, count: Int): RoundedCornerShape =
    RoundedCornerShape(
        topStart = if (index == 0) NukeSettingGroupCornerRadius else 0.dp,
        topEnd = if (index == 0) NukeSettingGroupCornerRadius else 0.dp,
        bottomEnd = if (index == count - 1) NukeSettingGroupCornerRadius else 0.dp,
        bottomStart = if (index == count - 1) NukeSettingGroupCornerRadius else 0.dp,
    )

@Composable
fun Modifier.nukeGroupedCardItem(
    index: Int,
    count: Int,
    animate: Boolean = true,
): Modifier {
    // Entrance motion for the initial screenful only: rows composed before the page's entrance
    // flag flips fade in while springing up 6dp; rows composed later while scrolling (the
    // second-time appearance) stay static. The decision is frozen at first composition.
    val animateOnEnter = remember { animate }
    var entered by remember { mutableStateOf(!animateOnEnter) }
    LaunchedEffect(animateOnEnter) {
        if (animateOnEnter) entered = true
    }
    val itemAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(160),
        label = "NukeGroupedCardItemAlphaAnimation",
    )
    val itemOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "NukeGroupedCardItemOffsetAnimation",
    )
    return fillMaxWidth()
        .offset(y = itemOffset)
        .graphicsLayer {
            alpha = itemAlpha
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .clip(nukeGroupedCardShape(index, count))
        .background(NukeTheme.colors.surface)
}

@Composable
fun NukePreferenceRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: ((Offset) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = NukeTheme.colors
    val clickModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.nukeJellyClickable(
            enabled = enabled,
            onClick = onClick,
        )
    }

    Row(
        modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            NukeText(
                text = title,
                color = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.58f),
                fontSize = 15,
                lineHeight = 20,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Spacer(Modifier.height(3.dp))
                NukeText(
                    text = description,
                    color = colors.textSecondary,
                    fontSize = 12,
                    lineHeight = 17,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun NukeCategoryIcon(
    glyph: NukeGlyphKind,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val colors = NukeTheme.colors
    val color = if (error) Color(0xFFDC2626) else colors.accent
    Box(
        modifier
            .size(34.dp)
            .clip(NukeSquircleShape(11.dp))
            .background(color.copy(alpha = if (error) 0.13f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        NukeGlyph(
            kind = glyph,
            color = color,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Foundation-hosted wrapper for the project's existing Miuix setting icon vectors. */
@Composable
fun NukeVectorCategoryIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val colors = NukeTheme.colors
    val color = if (error) Color(0xFFDC2626) else colors.accent
    Box(
        modifier
            .size(34.dp)
            .clip(NukeSquircleShape(11.dp))
            .background(color.copy(alpha = if (error) 0.13f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(color),
        )
    }
}

@Composable
fun NukeDivider(
    modifier: Modifier = Modifier,
    startPadding: Dp = 64.dp,
    endPadding: Dp = 16.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = endPadding)
            .height(0.5.dp)
            .background(NukeTheme.colors.border)
    )
}

@Composable
fun NukeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NukeTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = NukeTheme.hapticFeedback
    val geometry by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (checked) {
            spring(dampingRatio = 0.5f, stiffness = 1_500f)
        } else {
            spring(dampingRatio = 1f, stiffness = 10_000f)
        },
        label = "NukeCheckboxGeometryAnim",
    )
    val pressScale by animateFloatAsState(
        targetValue = when {
            pressed && enabled -> 0.9f
            checked -> 1.04f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "NukeCheckboxPressScale",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textPrimary.copy(alpha = 0.05f)
            checked -> colors.accent.copy(alpha = 0.14f)
            else -> colors.textPrimary.copy(alpha = 0.10f)
        },
        animationSpec = tween(150),
        label = "NukeCheckboxContainerColor",
    )

    Box(
        modifier
            .size(20.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(NukeSquircleShape(6.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(!checked)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer {
                    scaleX = geometry
                    scaleY = geometry
                }
                .clip(NukeSquircleShape(3.dp))
                .background(if (enabled) colors.accent else colors.textPrimary)
        )
    }
}

@Composable
fun NukeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NukeTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = NukeTheme.hapticFeedback
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> colors.accent.copy(alpha = 0.3f)
            !enabled -> colors.textSecondary.copy(alpha = 0.1f)
            checked -> colors.accent
            colors.isLight -> colors.textSecondary.copy(alpha = 0.18f)
            else -> colors.textSecondary.copy(alpha = 0.26f)
        },
        animationSpec = tween(180),
        label = "TrackColorAnimation",
    )
    val thumbWidth by animateDpAsState(
        targetValue = when {
            pressed && enabled -> 27.dp
            checked -> 24.dp
            else -> 22.dp
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "ThumbWidthAnimation",
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (pressed && enabled) 20.dp else 22.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "ThumbHeightAnimation",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 42.dp - thumbWidth else 0.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "ThumbOffsetAnimation",
    )
    val thumbElevation by animateDpAsState(
        targetValue = when {
            !enabled -> 0.dp
            pressed -> 1.dp
            checked -> 5.dp
            else -> 3.dp
        },
        animationSpec = tween(180),
        label = "ThumbElevationAnimation",
    )
    val trackScaleX by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "TrackScaleXAnimation",
    )
    val trackScaleY by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "TrackScaleYAnimation",
    )
    val highlightOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 200f),
        label = "HighlightOffsetAnimation",
    )
    val highlightAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            checked -> 0.28f
            else -> 0.12f
        },
        animationSpec = tween(180),
        label = "HighlightAlphaAnimation",
    )

    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .graphicsLayer {
                scaleX = trackScaleX
                scaleY = trackScaleY
            }
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onCheckedChange(!checked)
                },
            )
    ) {
        Box(
            Modifier
                .offset(x = 3.dp + highlightOffset, y = 7.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = highlightAlpha))
        )
        Box(
            Modifier
                .offset(
                    x = 3.dp + thumbOffset,
                    y = (28.dp - thumbHeight) / 2,
                )
                .size(width = thumbWidth, height = thumbHeight)
                .shadow(
                    elevation = thumbElevation,
                    shape = CircleShape,
                    clip = false,
                )
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun NukeCountAndChevron(
    text: String?,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val colors = NukeTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text != null) {
            NukeText(
                text = text,
                color = if (error) Color(0xFFDC2626) else colors.textSecondary,
                fontSize = 14,
                lineHeight = 18,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        NukeGlyph(
            kind = NukeGlyphKind.Chevron,
            color = colors.textSecondary.copy(alpha = if (error) 0.62f else 0.36f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun NukeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = NukeTheme.colors
    val container = when {
        primary -> colors.accent.copy(alpha = if (enabled) 0.30f else 0.08f)
        colors.isLight -> Color.Black.copy(alpha = if (enabled) 0.06f else 0.02f)
        else -> Color.White.copy(alpha = if (enabled) 0.06f else 0.02f)
    }
    Box(
        modifier
            .nukeJellyClickable(
                enabled = enabled,
                role = Role.Button,
                hapticFeedbackType = HapticFeedbackType.VirtualKey,
                onClick = { onClick() },
            )
            .clip(NukeSquircleShape(11.dp))
            .background(container)
            .padding(horizontal = 17.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        NukeText(
            text = text,
            color = when {
                primary -> colors.accent.copy(alpha = if (enabled) 1f else 0.45f)
                else -> colors.textPrimary.copy(alpha = if (enabled) 1f else 0.45f)
            },
            fontSize = 14,
            lineHeight = null,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun NukeStatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        NukeText(
            text = text,
            color = color,
            fontSize = 10,
            lineHeight = 12,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun NukeGlyph(
    kind: NukeGlyphKind,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawNukeGlyph(kind, color)
    }
}

private fun DrawScope.drawNukeGlyph(
    kind: NukeGlyphKind,
    color: Color,
) {
    val width = size.width
    val height = size.height
    val short = min(width, height)
    nukeMaterialGlyphPaths[kind]?.let { path ->
        val vectorScale = short / 24f
        val left = (width - short) / 2f
        val top = (height - short) / 2f
        val autoMirror = kind == NukeGlyphKind.Send && layoutDirection == LayoutDirection.Rtl
        withTransform({
            if (autoMirror) {
                translate(left + short, top)
                scale(-vectorScale, vectorScale, Offset.Zero)
            } else {
                translate(left, top)
                scale(vectorScale, vectorScale, Offset.Zero)
            }
        }) {
            drawPath(path, color)
        }
        return
    }

    val stroke = short * 0.105f
    when (kind) {
        NukeGlyphKind.Check -> {
            val checkStroke = with(this) { 2.dp.toPx() }
            val path = Path().apply {
                moveTo(width * 0.22f, height * 0.52f)
                lineTo(width * 0.42f, height * 0.72f)
                lineTo(width * 0.78f, height * 0.28f)
            }
            drawPath(
                path,
                color,
                style = Stroke(width = checkStroke, cap = StrokeCap.Round),
            )
        }

        NukeGlyphKind.Chevron -> {
            val chevronStroke = with(this) { 1.9.dp.toPx() }
            drawLine(
                color,
                Offset(width * 0.38f, height * 0.28f),
                Offset(width * 0.62f, height * 0.50f),
                chevronStroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(width * 0.62f, height * 0.50f),
                Offset(width * 0.38f, height * 0.72f),
                chevronStroke,
                StrokeCap.Round,
            )
        }

        NukeGlyphKind.Back -> {
            val backStroke = with(this) { 2.dp.toPx() }
            drawLine(
                color,
                Offset(width * 0.66f, height * 0.24f),
                Offset(width * 0.34f, height * 0.5f),
                backStroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(width * 0.34f, height * 0.5f),
                Offset(width * 0.66f, height * 0.76f),
                backStroke,
                StrokeCap.Round,
            )
        }

        NukeGlyphKind.Close -> {
            drawLine(color, Offset(width * 0.26f, height * 0.26f), Offset(width * 0.74f, height * 0.74f), stroke, StrokeCap.Round)
            drawLine(color, Offset(width * 0.74f, height * 0.26f), Offset(width * 0.26f, height * 0.74f), stroke, StrokeCap.Round)
        }

        else -> error("Material glyph path missing for $kind")
    }
}

private fun materialGlyphPath(pathData: String): Path =
    PathParser().parsePathString(pathData).toPath()

private val nukeMaterialGlyphPaths = mapOf(
    NukeGlyphKind.Send to materialGlyphPath(
        "M2.01,21L23,12L2.01,3L2,10l15,2l-15,2z"
    ),
    NukeGlyphKind.Person to materialGlyphPath(
        "M12,12c2.21,0,4,-1.79,4,-4s-1.79,-4,-4,-4s-4,1.79,-4,4s1.79,4,4,4z" +
            "M12,14c-2.67,0,-8,1.34,-8,4v2h16v-2c0,-2.66,-5.33,-4,-8,-4z"
    ),
    NukeGlyphKind.Search to materialGlyphPath(
        "M15.5,14h-.79l-.28-.27C15.41,12.59,16,11.11,16,9.5C16,5.91,13.09,3,9.5,3" +
            "S3,5.91,3,9.5S5.91,16,9.5,16c1.61,0,3.09-.59,4.23-1.57l.27,.28v.79l5,4.99" +
            "L20.49,19l-4.99-5zM9.5,14C7.01,14,5,11.99,5,9.5S7.01,5,9.5,5S14,7.01,14,9.5" +
            "S11.99,14,9.5,14z"
    ),
    NukeGlyphKind.Star to materialGlyphPath(
        "M12,17.27L18.18,21l-1.64-7.03L22,9.24l-7.19-.61L12,2L9.19,8.63L2,9.24" +
            "l5.46,4.73L5.82,21z"
    ),
    NukeGlyphKind.CheckCircle to materialGlyphPath(
        "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10-4.48,10-10S17.52,2,12,2z" +
            "M10,17l-5-5l1.41-1.41L10,14.17l7.59-7.59L19,8z"
    ),
    NukeGlyphKind.Heart to materialGlyphPath(
        "M12,21.35l-1.45-1.32C5.4,15.36,2,12.28,2,8.5C2,5.42,4.42,3,7.5,3" +
            "c1.74,0,3.41,.81,4.5,2.09C13.09,3.81,14.76,3,16.5,3C19.58,3,22,5.42,22,8.5" +
            "c0,3.78-3.4,6.86-8.55,11.54L12,21.35z"
    ),
    NukeGlyphKind.Info to materialGlyphPath(
        "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10-4.48,10-10S17.52,2,12,2z" +
            "M13,17h-2v-6h2v6zM13,9h-2V7h2v2z"
    ),
    NukeGlyphKind.Settings to materialGlyphPath(
        "M19.14,12.94c.04-.3,.06-.61,.06-.94s-.02-.64-.07-.94l2.03-1.58" +
            "c.18-.14,.23-.41,.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39,.96" +
            "c-.5-.38-1.03-.7-1.62-.94L14.4,2.81c-.04-.24-.24-.41-.48-.41h-3.84" +
            "c-.24,0-.43,.17-.47,.41L9.25,5.35c-.59,.24-1.13,.57-1.62,.94l-2.39-.96" +
            "c-.22-.08-.47,0-.59,.22L2.74,8.87c-.12,.21-.08,.47,.12,.61l2.03,1.58" +
            "c-.05,.3-.09,.63-.09,.94s.02,.64,.07,.94l-2.03,1.58" +
            "c-.18,.14-.23,.41-.12,.61l1.92,3.32c.12,.22,.37,.29,.59,.22l2.39-.96" +
            "c.5,.38,1.03,.7,1.62,.94l.36,2.54c.05,.24,.24,.41,.48,.41h3.84" +
            "c.24,0,.44-.17,.47-.41l.36-2.54c.59-.24,1.13-.56,1.62-.94l2.39,.96" +
            "c.22,.08,.47,0,.59-.22l1.92-3.32c.12-.22,.07-.47-.12-.61l-2.03-1.58z" +
            "M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6" +
            "S13.98,15.6,12,15.6z"
    ),
    NukeGlyphKind.Update to materialGlyphPath(
        "M17.65,6.35C16.2,4.9,14.21,4,12,4C7.58,4,4.01,7.58,4.01,12" +
            "S7.58,20,12,20c3.73,0,6.84-2.55,7.73-6h-2.08c-.82,2.33-3.04,4-5.65,4" +
            "c-3.31,0-6-2.69-6-6s2.69-6,6-6c1.66,0,3.14,.69,4.22,1.78L13,11h7V4z"
    ),
    NukeGlyphKind.Code to materialGlyphPath(
        "M9.4,16.6L4.8,12l4.6-4.6L8,6l-6,6l6,6zM14.6,16.6l4.6-4.6l-4.6-4.6" +
            "L16,6l6,6l-6,6z"
    ),
    NukeGlyphKind.Home to materialGlyphPath(
        "M10,20v-6h4v6h5v-8h3L12,3L2,12h3v8z"
    ),
    NukeGlyphKind.Gift to materialGlyphPath(
        "M12,21.35l-1.45-1.32C5.4,15.36,2,12.28,2,8.5C2,5.42,4.42,3,7.5,3" +
            "c1.74,0,3.41,.81,4.5,2.09C13.09,3.81,14.76,3,16.5,3C19.58,3,22,5.42,22,8.5" +
            "c0,3.78-3.4,6.86-8.55,11.54L12,21.35z"
    ),
    NukeGlyphKind.Restart to materialGlyphPath(
        "M17.65,6.35C16.2,4.9,14.21,4,12,4C7.58,4,4.01,7.58,4.01,12" +
            "S7.58,20,12,20c3.73,0,6.84-2.55,7.73-6h-2.08c-.82,2.33-3.04,4-5.65,4" +
            "c-3.31,0-6-2.69-6-6s2.69-6,6-6c1.66,0,3.14,.69,4.22,1.78L13,11h7V4z"
    ),
    NukeGlyphKind.Error to materialGlyphPath(
        "M11,15h2v2h-2zM11,7h2v6h-2zM11.99,2C6.47,2,2,6.48,2,12" +
            "s4.47,10,9.99,10C17.52,22,22,17.52,22,12S17.52,2,11.99,2z" +
            "M12,20c-4.42,0-8-3.58-8-8s3.58-8,8-8s8,3.58,8,8s-3.58,8-8,8z"
    ),
)

@Composable
fun NukeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    fontSize: Int,
    lineHeight: Int?,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize.sp,
            lineHeight = lineHeight?.sp ?: TextUnit.Unspecified,
            fontWeight = fontWeight,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
