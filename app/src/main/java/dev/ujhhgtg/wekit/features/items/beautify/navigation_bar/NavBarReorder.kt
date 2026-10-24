package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class ReorderSource {
    LIST,
    BAR,
}

/**
 * One drag shared by the page-management list and the bar itself, so both surfaces show the same
 * lift, preview and drop regardless of where the finger is.
 */
@Stable
class NavBarReorderState {
    var draggedTab by mutableStateOf<Int?>(null)
        private set

    var source by mutableStateOf(ReorderSource.LIST)
        private set

    /** Drop position within the full four-tab order. */
    var targetIndex by mutableIntStateOf(-1)

    /** Finger travel along the source surface's axis, in pixels. */
    var fingerOffset by mutableFloatStateOf(0f)

    fun start(tab: Int, source: ReorderSource, index: Int) {
        draggedTab = tab
        this.source = source
        targetIndex = index
        fingerOffset = 0f
    }

    fun reset() {
        draggedTab = null
        targetIndex = -1
        fingerOffset = 0f
    }

    fun previewOrder(order: List<Int>): List<Int> {
        val dragged = draggedTab ?: return order
        return order.toMutableList().apply { add(targetIndex, removeAt(indexOf(dragged))) }
    }

    /** Commits the drag into [order] and returns the result, or null when nothing moved. */
    fun finish(order: List<Int>): List<Int>? {
        val moved = previewOrder(order).takeIf { it != order }
        reset()
        return moved
    }

    fun follows(tab: Int, surface: ReorderSource): Boolean = draggedTab == tab && source == surface
}

/** InstallerX's slot rounding: a dragged item swaps once it crosses half of its neighbour. */
fun reorderTarget(from: Int, offset: Float, step: Float, lastIndex: Int): Int {
    if (step <= 0f) return from
    val shift = (offset / step + if (offset > 0) 0.5f else -0.5f).toInt()
    return (from + shift).coerceIn(0, lastIndex)
}

/**
 * Animated translation of the item currently laid out at [slot].
 *
 * When the layout reorders underneath (a drop commits, or the other surface moved an item), the
 * value is rebased by the slot delta first, so items glide from where they were drawn instead of
 * jumping to their new layout slot.
 */
@Composable
fun rememberReorderOffset(
    slot: Int,
    step: Float,
    target: Float,
    followFinger: Boolean,
): State<Float> {
    val offset = remember { Animatable(0f) }
    var lastSlot by remember { mutableIntStateOf(slot) }
    LaunchedEffect(slot, step, target, followFinger) {
        if (slot != lastSlot) {
            offset.snapTo(offset.value - (slot - lastSlot) * step)
            lastSlot = slot
        }
        if (followFinger) {
            offset.snapTo(target)
        } else {
            offset.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    return offset.asState()
}

@Composable
fun rememberReorderLift(lifted: Boolean): State<Float> {
    val lift = remember { Animatable(0f) }
    LaunchedEffect(lifted) {
        lift.animateTo(
            if (lifted) 1f else 0f,
            spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        )
    }
    return lift.asState()
}

/** Target translation of [tab] on a surface showing [shown] tabs in their current order. */
fun reorderShift(
    reorder: NavBarReorderState,
    order: List<Int>,
    shown: (Int) -> Boolean,
    tab: Int,
    step: Float,
    surface: ReorderSource,
): Float {
    if (reorder.draggedTab == null) return 0f
    if (reorder.follows(tab, surface)) return reorder.fingerOffset
    val current = order.filter(shown)
    val preview = reorder.previewOrder(order).filter(shown)
    return (preview.indexOf(tab) - current.indexOf(tab)) * step
}
