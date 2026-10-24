package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a plain [androidx.compose.foundation.layout.Column] of items: each item uses
 * [reorderableItem], its drag handle [reorderHandle]. Items shift live while dragging; [onMove]
 * runs once on release with the original and target index.
 */
class AutomationReorderState(
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onStart: () -> Unit,
) {
    var dragged by mutableStateOf<Int?>(null)
        private set

    private var offset by mutableFloatStateOf(0f)
    private val tops = HashMap<Int, Float>()
    private val heights = HashMap<Int, Int>()

    fun place(index: Int, top: Float, height: Int) {
        tops[index] = top
        heights[index] = height
    }

    private val gap: Float
        get() {
            val first = tops[0] ?: return 0f
            val second = tops[1] ?: return 0f
            return second - first - (heights[0] ?: 0)
        }

    private fun target(): Int {
        val from = dragged ?: return -1
        val center = (tops[from] ?: 0f) + (heights[from] ?: 0) / 2f + offset
        var to = from
        tops.keys.sorted().forEach { i ->
            val mid = (tops[i] ?: 0f) + (heights[i] ?: 0) / 2f
            if (i > from && center > mid) to = i
            if (i < from && center < mid && to == from) to = i
        }
        return to
    }

    fun translation(index: Int): Float {
        val from = dragged ?: return 0f
        if (index == from) return offset
        val to = target()
        val slot = (heights[from] ?: 0) + gap
        return when {
            index in (from + 1)..to -> -slot
            index in to until from -> slot
            else -> 0f
        }
    }

    fun start(index: Int) {
        dragged = index
        offset = 0f
        onStart()
    }

    fun drag(delta: Float) {
        offset += delta
    }

    fun end() {
        val from = dragged ?: return
        val to = target()
        dragged = null
        offset = 0f
        if (to != from) onMove(from, to)
    }

    fun cancel() {
        dragged = null
        offset = 0f
    }
}

@Composable
fun rememberAutomationReorderState(onMove: (from: Int, to: Int) -> Unit): AutomationReorderState {
    val haptic = LocalHapticFeedback.current
    val currentOnMove by rememberUpdatedState(onMove)
    return remember {
        AutomationReorderState(
            onMove = { from, to -> currentOnMove(from, to) },
            onStart = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
        )
    }
}

fun Modifier.reorderableItem(state: AutomationReorderState, index: Int): Modifier =
    this.onGloballyPositioned { state.place(index, it.positionInParent().y, it.size.height) }
        .zIndex(if (state.dragged == index) 1f else 0f)
        .graphicsLayer {
            translationY = state.translation(index)
            if (state.dragged == index) shadowElevation = 12f
        }

fun Modifier.reorderHandle(state: AutomationReorderState, index: Int): Modifier =
    pointerInput(state, index) {
        detectDragGestures(
            onDragStart = { state.start(index) },
            onDragEnd = { state.end() },
            onDragCancel = { state.cancel() },
        ) { change, amount ->
            change.consume()
            state.drag(amount.y)
        }
    }
