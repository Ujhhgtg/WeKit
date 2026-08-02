// SPDX-License-Identifier: GPL-3.0-only
package dev.ujhhgtg.wekit.ui.content.animation

import android.os.SystemClock
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Synchronous pill gesture handler.
 *
 * The suspending `Modifier.pointerInput {}` coroutine path does not deliver events inside the
 * injected host ComposeView (the handler coroutine starts but never resumes), so this node
 * processes pointers the same way foundation's clickable does: directly in
 * [PointerInputModifierNode.onPointerEvent] on the UI thread, with the long-press timer run on the
 * node coroutine scope.
 */
internal class PillDragPointerNode(
    private var canDrag: (Offset) -> Boolean,
    private var touchSlop: Float,
    private var longPressTimeoutMillis: Long,
    private var hostView: View,
    private var onGestureStart: (Offset) -> Unit,
    private var onGestureMove: (Offset) -> Unit,
    private var onGestureEnd: () -> Unit,
    private var onDragStart: (Offset) -> Unit,
    private var onDrag: (IntSize, Offset, Offset) -> Unit,
    private var onDragEnd: () -> Unit,
    private var onDragCancel: () -> Unit,
    private var onTap: () -> Unit,
    private var onLongPress: () -> Unit,
) : Modifier.Node(), PointerInputModifierNode {

    private var downPosition: Offset? = null
    private var lastPosition: Offset? = null
    private var gestureActive = false
    private var accumulatedDrag = 0f
    private var longPressFired = false
    private var downUptime = 0L
    private var longPressRunnable: Runnable? = null
    private var latestBounds = IntSize.Zero

    fun update(
        canDrag: (Offset) -> Boolean,
        touchSlop: Float,
        longPressTimeoutMillis: Long,
        hostView: View,
        onGestureStart: (Offset) -> Unit,
        onGestureMove: (Offset) -> Unit,
        onGestureEnd: () -> Unit,
        onDragStart: (Offset) -> Unit,
        onDrag: (IntSize, Offset, Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        this.canDrag = canDrag
        this.touchSlop = touchSlop
        this.longPressTimeoutMillis = longPressTimeoutMillis
        this.hostView = hostView
        this.onGestureStart = onGestureStart
        this.onGestureMove = onGestureMove
        this.onGestureEnd = onGestureEnd
        this.onDragStart = onDragStart
        this.onDrag = onDrag
        this.onDragEnd = onDragEnd
        this.onDragCancel = onDragCancel
        this.onTap = onTap
        this.onLongPress = onLongPress
    }

    override fun onPointerEvent(event: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        latestBounds = bounds
        val change = event.changes.firstOrNull() ?: return

        if (change.pressed) {
            if (downPosition == null) {
                handleDown(change.position)
            } else {
                handleMove(change.position)
            }
        } else if (downPosition != null) {
            handleUp()
        }
    }

    override fun onCancelPointerInput() {
        val wasActive = gestureActive
        downPosition = null
        lastPosition = null
        longPressRunnable?.let(hostView::removeCallbacks)
        longPressRunnable = null
        if (wasActive) {
            gestureActive = false
            onDragCancel()
        }
        onGestureEnd()
    }

    private fun handleDown(position: Offset) {
        downPosition = position
        lastPosition = position
        // touchSlop is resolved at composition time and passed in.
        gestureActive = canDrag(position)
        accumulatedDrag = 0f
        longPressFired = false
        downUptime = SystemClock.uptimeMillis()
        onGestureStart(position)
        if (gestureActive) {
            onDragStart(position)
            val runnable = Runnable {
                longPressFired = true
                onLongPress()
            }
            longPressRunnable = runnable
            // The injected host's Compose coroutine scope does not run delayed coroutines, so the
            // long-press timer rides the Android view handler instead.
            hostView.postDelayed(runnable, longPressTimeoutMillis)
        }
    }

    private fun handleMove(position: Offset) {
        val prev = lastPosition ?: position
        val amount = position - prev
        lastPosition = position
        if (gestureActive) {
            accumulatedDrag += abs(amount.x) + abs(amount.y)
            onDrag(latestBounds, position, amount)
            if (accumulatedDrag > touchSlop) {
                longPressRunnable?.let(hostView::removeCallbacks)
                longPressRunnable = null
            }
        }
        onGestureMove(position)
    }

    private fun handleUp() {
        val wasActive = gestureActive
        val heldLongEnough =
            SystemClock.uptimeMillis() - downUptime >= longPressTimeoutMillis
        downPosition = null
        lastPosition = null
        longPressRunnable?.let(hostView::removeCallbacks)
        longPressRunnable = null
        if (wasActive) {
            gestureActive = false
            onDragEnd()
            val dragged = accumulatedDrag > touchSlop
            if ((longPressFired || heldLongEnough) && !dragged) {
                onLongPress()
            } else if (!dragged) {
                onTap()
            }
        }
        onGestureEnd()
    }
}

internal fun Modifier.pillDragPointer(
    canDrag: (Offset) -> Boolean,
    touchSlop: Float,
    longPressTimeoutMillis: Long,
    hostView: View,
    onGestureStart: (Offset) -> Unit = {},
    onGestureMove: (Offset) -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onDragStart: (Offset) -> Unit,
    onDrag: (IntSize, Offset, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = this.then(
    PillDragPointerElement(
        canDrag = canDrag,
        touchSlop = touchSlop,
        longPressTimeoutMillis = longPressTimeoutMillis,
        hostView = hostView,
        onGestureStart = onGestureStart,
        onGestureMove = onGestureMove,
        onGestureEnd = onGestureEnd,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        onDragCancel = onDragCancel,
        onTap = onTap,
        onLongPress = onLongPress,
    )
)

private class PillDragPointerElement(
    private val canDrag: (Offset) -> Boolean,
    private val touchSlop: Float,
    private val longPressTimeoutMillis: Long,
    private val hostView: View,
    private val onGestureStart: (Offset) -> Unit,
    private val onGestureMove: (Offset) -> Unit,
    private val onGestureEnd: () -> Unit,
    private val onDragStart: (Offset) -> Unit,
    private val onDrag: (IntSize, Offset, Offset) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onDragCancel: () -> Unit,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) : ModifierNodeElement<PillDragPointerNode>() {
    override fun create(): PillDragPointerNode =
        PillDragPointerNode(
            canDrag,
            touchSlop,
            longPressTimeoutMillis,
            hostView,
            onGestureStart,
            onGestureMove,
            onGestureEnd,
            onDragStart,
            onDrag,
            onDragEnd,
            onDragCancel,
            onTap,
            onLongPress,
        )

    override fun update(node: PillDragPointerNode) {
        node.update(
            canDrag,
            touchSlop,
            longPressTimeoutMillis,
            hostView,
            onGestureStart,
            onGestureMove,
            onGestureEnd,
            onDragStart,
            onDrag,
            onDragEnd,
            onDragCancel,
            onTap,
            onLongPress,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PillDragPointerElement) return false
        return canDrag === other.canDrag &&
            touchSlop == other.touchSlop &&
            longPressTimeoutMillis == other.longPressTimeoutMillis &&
            hostView === other.hostView &&
            onGestureStart === other.onGestureStart &&
            onGestureMove === other.onGestureMove &&
            onGestureEnd === other.onGestureEnd &&
            onDragStart === other.onDragStart &&
            onDrag === other.onDrag &&
            onDragEnd === other.onDragEnd &&
            onDragCancel === other.onDragCancel &&
            onTap === other.onTap &&
            onLongPress === other.onLongPress
    }

    override fun hashCode(): Int {
        var result = canDrag.hashCode()
        result = 31 * result + touchSlop.hashCode()
        result = 31 * result + longPressTimeoutMillis.hashCode()
        result = 31 * result + hostView.hashCode()
        result = 31 * result + onGestureStart.hashCode()
        result = 31 * result + onGestureMove.hashCode()
        result = 31 * result + onGestureEnd.hashCode()
        result = 31 * result + onDragStart.hashCode()
        result = 31 * result + onDrag.hashCode()
        result = 31 * result + onDragEnd.hashCode()
        result = 31 * result + onDragCancel.hashCode()
        result = 31 * result + onTap.hashCode()
        result = 31 * result + onLongPress.hashCode()
        return result
    }
}
