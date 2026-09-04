package dev.ujhhgtg.wekit.ui.content.nuke

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val IMMEDIATE_PRESS_MINIMUM_DURATION_MILLIS = 64L

@Composable
fun Modifier.nukeJellyClickable(
    enabled: Boolean = true,
    role: Role? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.ContextClick,
    onClick: (Offset) -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = NukeTheme.hapticFeedback
    val immediatePressFeedback = NukeTheme.immediatePressFeedback
    val pressFeedbackScope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }
    var hasPointerPosition by remember { mutableStateOf(false) }
    var immediatePressed by remember { mutableStateOf(false) }
    var immediatePressGeneration by remember { mutableIntStateOf(0) }
    val isJellyPressed = enabled && (pressed || (immediatePressFeedback && immediatePressed))

    val jellyProgress by animateFloatAsState(
        targetValue = if (isJellyPressed) 1f else 0f,
        animationSpec = if (isJellyPressed) {
            spring(dampingRatio = 1f, stiffness = 10_000f)
        } else {
            spring(dampingRatio = 0.4f, stiffness = 120f)
        },
        label = "JellyProgress",
    )

    val normalizedX = if (size.width == 0) {
        0f
    } else {
        ((pointerPosition.x - size.width / 2f) / (size.width / 2f)).coerceIn(-1f, 1f)
    }
    val normalizedY = if (size.height == 0) {
        0f
    } else {
        ((pointerPosition.y - size.height / 2f) / (size.height / 2f)).coerceIn(-1f, 1f)
    }

    return onSizeChanged { size = it }
        .onGloballyPositioned { coordinates = it }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.lastOrNull()
                    if (change != null) {
                        pointerPosition = change.position
                        hasPointerPosition = true
                    }
                }
            }
        }
        .pointerInput(enabled, immediatePressFeedback) {
            if (!enabled || !immediatePressFeedback) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                val generation = immediatePressGeneration + 1
                immediatePressGeneration = generation
                immediatePressed = true

                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                            ?: break
                        if (!change.pressed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            immediatePressed = false
                            break
                        }
                    }
                } finally {
                    val remaining = (
                        IMMEDIATE_PRESS_MINIMUM_DURATION_MILLIS -
                            (SystemClock.uptimeMillis() - down.uptimeMillis)
                        ).coerceAtLeast(0L)
                    if (remaining == 0L) {
                        if (immediatePressGeneration == generation) immediatePressed = false
                    } else {
                        // Keep a quick tap visible for at least one rendered frame.
                        pressFeedbackScope.launch {
                            delay(remaining)
                            if (immediatePressGeneration == generation) {
                                immediatePressed = false
                            }
                        }
                    }
                }
            }
        }
        .graphicsLayer {
            val scale = 1f - jellyProgress * 0.03f
            scaleX = scale
            scaleY = scale
            rotationX = jellyProgress * -normalizedY * 6f
            rotationY = jellyProgress * normalizedX * 6f
            cameraDistance = density * 16f
            transformOrigin = if (size.width > 0 && size.height > 0) {
                TransformOrigin(
                    pivotFractionX = pointerPosition.x / size.width,
                    pivotFractionY = pointerPosition.y / size.height,
                )
            } else {
                TransformOrigin.Center
            }
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = {
                hapticFeedback.performHapticFeedback(hapticFeedbackType)
                val localOrigin = if (hasPointerPosition) {
                    pointerPosition
                } else {
                    Offset(size.width / 2f, size.height / 2f)
                }
                hasPointerPosition = false
                val rootOrigin = coordinates
                    ?.takeIf { it.isAttached }
                    ?.localToRoot(localOrigin)
                    ?: localOrigin
                onClick(rootOrigin)
            },
        )
}
