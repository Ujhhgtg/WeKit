package dev.ujhhgtg.wekit.ui.content.nuke

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun <T> NukeSelectPreference(
    title: String,
    description: String?,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var panelAbove by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var predictiveExitProgress by remember { mutableFloatStateOf(0f) }
    var predictiveExitCompleted by remember { mutableStateOf(false) }
    var predictiveAnimationJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val popupMotion = NukeTheme.popupMotion
    val animationMode = popupMotion.animationMode
    val predictiveBackEnabled = popupMotion.useDialogHost &&
        popupMotion.predictiveExit &&
        animationMode.supportsPredictiveExit
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 1_500f),
        label = "ArrowRotation",
    )
    val arrowScale by animateFloatAsState(
        targetValue = if (expanded) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "ArrowScale",
    )
    val visibilityState = remember { MutableTransitionState(false) }
    visibilityState.targetState = expanded
    val transformOrigin = TransformOrigin(
        pivotFractionX = 0.82f,
        pivotFractionY = if (panelAbove) 1f else 0f,
    )
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        NukeSelectPopupPositionProvider(
            verticalOffsetPx = with(density) { 18.dp.roundToPx() },
            screenPaddingPx = with(density) { 12.dp.roundToPx() },
            onPanelAboveChanged = { panelAbove = it },
        )
    }

    LaunchedEffect(predictiveBackEnabled) {
        if (!predictiveBackEnabled) {
            predictiveAnimationJob?.cancel()
            predictiveExitProgress = 0f
            predictiveExitCompleted = false
        }
    }

    fun openPopup() {
        predictiveAnimationJob?.cancel()
        predictiveExitProgress = 0f
        predictiveExitCompleted = false
        expanded = true
    }

    fun resetPredictiveExit() {
        predictiveAnimationJob?.cancel()
        predictiveAnimationJob = coroutineScope.launch {
            animate(
                initialValue = predictiveExitProgress,
                targetValue = 0f,
                animationSpec = NukePopupMotionSpecs.predictiveReset,
            ) { value, _ ->
                predictiveExitProgress = value
            }
        }
    }

    fun completePredictiveExit() {
        predictiveAnimationJob?.cancel()
        predictiveAnimationJob = coroutineScope.launch {
            val initial = predictiveExitProgress
            animate(
                initialValue = initial,
                targetValue = 1f,
                animationSpec = NukePopupMotionSpecs.predictiveExit(initial),
            ) { value, _ ->
                predictiveExitProgress = value
            }
            predictiveExitCompleted = true
            expanded = false
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                anchorBounds = IntRect(
                    left = bounds.left.toInt(),
                    top = bounds.top.toInt(),
                    right = bounds.right.toInt(),
                    bottom = bounds.bottom.toInt(),
                )
            }
    ) {
        NukePreferenceRow(
            title = title,
            description = description,
            enabled = enabled,
            onClick = { openPopup() },
            trailing = {
                NukeText(
                    text = optionLabel(selected),
                    color = NukeTheme.colors.accent,
                    fontSize = 14,
                    lineHeight = null,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                NukeSelectArrow(
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            rotationZ = arrowRotation
                            scaleX = arrowScale
                            scaleY = arrowScale
                        },
                    color = NukeTheme.colors.textSecondary,
                )
            },
        )

        NukePopupHost(
            visible = expanded,
            contentMounted = visibilityState.currentState || visibilityState.targetState,
            useDialogHost = popupMotion.useDialogHost,
            anchorBounds = anchorBounds,
            popupPositionProvider = positionProvider,
            onDismissRequest = { expanded = false },
            backEnabled = expanded,
            onBackProgressed = if (predictiveBackEnabled) {
                { gestureProgress ->
                    predictiveAnimationJob?.cancel()
                    predictiveExitProgress = gestureProgress
                }
            } else {
                null
            },
            onBackCancelled = {
                if (predictiveBackEnabled) {
                    resetPredictiveExit()
                }
            },
            onBackCompleted = {
                if (predictiveBackEnabled) {
                    completePredictiveExit()
                } else {
                    expanded = false
                }
            },
        ) {
            Box(
                Modifier.graphicsLayer {
                    val progress = predictiveExitProgress.coerceIn(0f, 1f)
                    alpha = 1f - progress
                    val scale = 1f - 0.18f * progress
                    scaleX = scale
                    scaleY = scale
                    this.transformOrigin = transformOrigin
                }
            ) {
                AnimatedVisibility(
                    visibleState = visibilityState,
                    enter = NukePopupMotionSpecs.enter(animationMode, transformOrigin),
                    exit = if (predictiveExitCompleted) {
                        ExitTransition.None
                    } else {
                        NukePopupMotionSpecs.exit(animationMode, transformOrigin)
                    },
                ) {
                    val panelMotion = nukePopupPanelMotionValues(animationMode)
                    NukeSelectPanel(
                        options = options,
                        selected = selected,
                        optionLabel = optionLabel,
                        panelAbove = panelAbove,
                        enabled = enabled,
                        motion = panelMotion,
                        onSelected = { option ->
                            onSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> NukeSelectPanel(
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    panelAbove: Boolean,
    enabled: Boolean,
    motion: NukePopupPanelMotionValues,
    onSelected: (T) -> Unit,
) {
    val colors = NukeTheme.colors
    val edgeColor = lerp(
        start = colors.accent.copy(alpha = 0.14f),
        stop = colors.textPrimary.copy(alpha = 0.035f),
        fraction = motion.edgeProgress,
    )
    val edgeThickness = (2f - motion.edgeThicknessProgress).dp
    val panelShape = NukeSquircleShape(14.dp)

    Column(
        Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .graphicsLayer {
                alpha = motion.alpha
                scaleX = motion.scaleX
                scaleY = motion.scaleY
                transformOrigin = TransformOrigin(
                    pivotFractionX = 0.82f,
                    pivotFractionY = if (panelAbove) 1f else 0f,
                )
            }
            .clip(panelShape)
            .background(edgeColor)
            .padding(edgeThickness)
            .clip(panelShape)
            .background(colors.surface)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val optionShape = NukeSquircleShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clip(optionShape)
                    .background(
                        if (isSelected) {
                            colors.accent.copy(alpha = 0.09f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .nukeJellyClickable(
                        enabled = enabled,
                        onClick = { onSelected(option) },
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NukeText(
                    text = optionLabel(option),
                    modifier = Modifier.weight(1f),
                    color = colors.textPrimary.copy(alpha = if (isSelected) 1f else 0.8f),
                    fontSize = 14,
                    lineHeight = null,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
                if (isSelected) {
                    Spacer(Modifier.width(12.dp))
                    NukeSelectCheckmark(
                        color = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NukeSelectCheckmark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 1_500f),
        label = "SelectCheckmarkScale",
    )
    Canvas(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.72f)
            lineTo(size.width * 0.78f, size.height * 0.28f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun NukeSelectArrow(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.25f, size.height * 0.38f),
            end = Offset(size.width * 0.5f, size.height * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.62f),
            end = Offset(size.width * 0.75f, size.height * 0.38f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private class NukeSelectPopupPositionProvider(
    private val verticalOffsetPx: Int,
    private val screenPaddingPx: Int,
    private val onPanelAboveChanged: (Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val maxX = (windowSize.width - popupContentSize.width - screenPaddingPx)
            .coerceAtLeast(screenPaddingPx)
        val x = anchorX.coerceIn(screenPaddingPx, maxX)

        val anchorCenterY = (anchorBounds.top + anchorBounds.bottom) / 2
        val belowY = anchorCenterY + verticalOffsetPx
        val aboveY = anchorCenterY - verticalOffsetPx - popupContentSize.height
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - screenPaddingPx
        val fitsAbove = aboveY >= screenPaddingPx
        val y = when {
            fitsBelow -> belowY
            fitsAbove -> aboveY
            anchorBounds.top > windowSize.height - anchorBounds.bottom ->
                aboveY.coerceAtLeast(screenPaddingPx)
            else -> belowY.coerceAtMost(
                (windowSize.height - popupContentSize.height - screenPaddingPx)
                    .coerceAtLeast(screenPaddingPx)
            )
        }

        onPanelAboveChanged(y < anchorBounds.top)
        return IntOffset(x, y)
    }
}
