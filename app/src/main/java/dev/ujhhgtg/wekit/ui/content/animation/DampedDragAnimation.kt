// InstallerX-Revived
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
//
// Portions of this file are derived from weishu/KernelSU
// (https://github.com/tiann/KernelSU)
// Copyright (C) KernelSU contributors
// Licensed under GPL-3.0
package dev.ujhhgtg.wekit.ui.content.animation

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    // Fired when a gesture on the indicator ends without meaningfully moving (a tap on the
    // pill rather than a drag). Because the pill always sits over the selected tab, this is
    // how a tap on the already-selected tab is surfaced — that tap lands on the pill, not the
    // tab item beneath it, so the item's own onClick never runs.
    val onTap: DampedDragAnimation.() -> Unit = {},
    // Fired when the pointer is held on the pill past the long-press threshold without dragging.
    // Same occlusion problem as onTap: the pill eats the event so the tab item's own long-press
    // modifier never fires for the currently-selected tab.
    val onLongPress: DampedDragAnimation.() -> Unit = {},
    // Optional raw gesture hooks used to drive the interactive highlight from the same
    // synchronous node that drives the pill (the suspending pointerInput path is unreliable
    // inside the injected host).
    var onGestureStart: (Offset) -> Unit = {},
    var onGestureMove: (Offset) -> Unit = {},
    var onGestureEnd: () -> Unit = {},
    // Resolved at composition time (Modifier.Node has no public viewConfiguration accessor).
    private val touchSlopPx: Float,
    private val longPressTimeoutMs: Long,
    private val hostView: View,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    // True while the user is actively dragging the indicator itself. An external
    // continuous driver should stop writing the value during this window so the drag
    // gesture wins.
    var isDragging: Boolean = false
        private set

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pillDragPointer(
        canDrag = canDrag,
        touchSlop = touchSlopPx,
        longPressTimeoutMillis = longPressTimeoutMs,
        hostView = hostView,
        onGestureStart = onGestureStart,
        onGestureMove = onGestureMove,
        onGestureEnd = onGestureEnd,
        onDragStart = { position ->
            WeLogger.d("PillGesture", "dragStart pos=$position isInside=${canDrag(position)}")
            isDragging = true
            onDragStarted(position)
            press()
        },
        onDrag = { bounds, position, dragAmount ->
            WeLogger.d("PillGesture", "dragMove pos=$position amount=$dragAmount")
            onDrag(bounds, dragAmount)
        },
        onDragEnd = {
            WeLogger.d("PillGesture", "dragEnd")
            isDragging = false
            onDragStopped()
            release()
        },
        onDragCancel = {
            WeLogger.d("PillGesture", "dragCancel")
            isDragging = false
            onDragStopped()
            release()
        },
        onTap = {
            WeLogger.d("PillGesture", "tap fired")
            onTap()
        },
        onLongPress = {
            WeLogger.d("PillGesture", "longPress fired")
            onLongPress()
        },
    )

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    // Sets the value immediately with no spring, so an external continuous driver
    // (e.g. a pager's fractional scroll position) can move the indicator 1:1.
    // snapTo cancels any in-flight animateTo on the same Animatable.
    fun snapToValue(value: Float) {
        animationScope.launch {
            valueAnimation.snapTo(value.coerceIn(valueRange))
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
