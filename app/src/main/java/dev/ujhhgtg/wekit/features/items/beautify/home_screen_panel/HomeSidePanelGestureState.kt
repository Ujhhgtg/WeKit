package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.view.MotionEvent
import kotlin.math.abs

internal data class HomeSidePanelGestureConfig(
    val drawerWidthFraction: Float = 0.84f,
    val touchSlopPx: Float = 8f,
    val horizontalDominance: Float = 1.15f,
    val openThreshold: Float = 0.38f,
    val velocityProjectionMs: Float = 160f,
)

internal fun homeSidePanelGestureConfig(density: Float): HomeSidePanelGestureConfig =
    HomeSidePanelGestureConfig(
        touchSlopPx = TOUCH_SLOP_DP * density,
    )

internal enum class HomeSidePanelGestureDecision {
    PASS,
    TRACKING,
    CONSUME,
}

internal fun homeSidePanelShouldPassFullyOpenTouchToChild(actionMasked: Int): Boolean =
    actionMasked == MotionEvent.ACTION_DOWN ||
        actionMasked == MotionEvent.ACTION_UP ||
        actionMasked == MotionEvent.ACTION_CANCEL

private const val TOUCH_SLOP_DP = 8f

internal class HomeSidePanelGestureState(
    private val config: HomeSidePanelGestureConfig = HomeSidePanelGestureConfig(),
) {
    var progress: Float = 0f
        private set

    var selectedTabIndex: Int = HOME_TAB_INDEX
        private set

    private var tracking = false
    private var locked = false
    private var rejected = false
    private var startX = 0f
    private var startY = 0f
    private var startProgress = 0f
    private var widthPx = 1f
    private var lastX = 0f
    private var lastTimeMs = 0L
    private var velocityPxPerMs = 0f

    val isTracking: Boolean
        get() = tracking && !rejected

    val isOpenOrOpening: Boolean
        get() = progress > CLOSED_EPSILON || isTracking

    fun setSelectedTab(index: Int) {
        selectedTabIndex = index
        if (index != HOME_TAB_INDEX) close()
    }

    fun onDown(x: Float, y: Float, widthPx: Float, timeMs: Long) {
        this.widthPx = widthPx.coerceAtLeast(1f)
        startX = x
        startY = y
        startProgress = progress
        lastX = x
        lastTimeMs = timeMs
        velocityPxPerMs = 0f
        locked = false
        rejected = selectedTabIndex != HOME_TAB_INDEX
        tracking = !rejected
    }

    fun onMove(x: Float, y: Float, timeMs: Long): HomeSidePanelGestureDecision {
        if (!tracking || rejected) return HomeSidePanelGestureDecision.PASS

        val dx = x - startX
        val dy = y - startY

        if (!locked) {
            if (abs(dx) < config.touchSlopPx && abs(dy) < config.touchSlopPx) {
                return HomeSidePanelGestureDecision.TRACKING
            }
            if (abs(dy) > abs(dx) * config.horizontalDominance) {
                rejected = true
                tracking = false
                return HomeSidePanelGestureDecision.PASS
            }
            if (progress <= CLOSED_EPSILON && dx <= 0f) {
                rejected = true
                tracking = false
                return HomeSidePanelGestureDecision.PASS
            }
            locked = true
        }

        val dt = (timeMs - lastTimeMs).coerceAtLeast(1L)
        velocityPxPerMs = (x - lastX) / dt
        lastX = x
        lastTimeMs = timeMs

        val drawerWidthPx = drawerWidthPx()
        progress = (startProgress + dx / drawerWidthPx).coerceIn(0f, 1f)
        return HomeSidePanelGestureDecision.CONSUME
    }

    fun onUp(timeMs: Long): Float {
        val projected = progress + velocityPxPerMs * config.velocityProjectionMs / drawerWidthPx()
        return settle(projected >= config.openThreshold)
    }

    fun onCancel(): Float = settle(progress >= config.openThreshold)

    fun snapTo(progress: Float): Float {
        this.progress = progress.coerceIn(0f, 1f)
        return this.progress
    }

    fun close(): Float {
        progress = 0f
        resetGesture()
        return progress
    }

    private fun settle(open: Boolean): Float {
        progress = if (open) 1f else 0f
        resetGesture()
        return progress
    }

    private fun resetGesture() {
        tracking = false
        locked = false
        rejected = false
        velocityPxPerMs = 0f
    }

    private fun drawerWidthPx(): Float =
        (widthPx * config.drawerWidthFraction).coerceAtLeast(1f)

    companion object {
        private const val HOME_TAB_INDEX = 0
        private const val CLOSED_EPSILON = 0.001f
    }
}
