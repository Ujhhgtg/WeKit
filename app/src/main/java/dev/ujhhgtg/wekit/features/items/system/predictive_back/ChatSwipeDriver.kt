package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * Drives WeChat's own chat swipe-back engine (`SwipeBackLayout` + its ViewDragHelper) with a
 * synthetic touch stream, so the predictive back gesture gets WeChat's slide, shadow, home parallax
 * and native release path instead of a reimplementation.
 *
 * Events go straight to the layout's `dispatchTouchEvent` in its local coordinates. The engine only
 * captures a DOWN inside its 20 dp left edge that is followed by a MOVE past touch slop; once it
 * has captured, children see just that DOWN and an immediate CANCEL.
 */
class ChatSwipeDriver
private constructor(
    private val layout: ViewGroup,
    private val content: View,
) {
    private val downTime = SystemClock.uptimeMillis()
    private val y = layout.height / 2f
    private val stepPx = RELEASE_STEP_DP * layout.resources.displayMetrics.density
    private var captureX = 0f
    private var lastX = 0f
    private var lastTime = downTime

    /** Whether the layout can still receive the stream. */
    val isAttached: Boolean
        get() = layout.isAttachedToWindow

    /** Slides the chat to [progress] of its width, capped below the engine's 0.99 pop-out. */
    fun moveToProgress(progress: Float) {
        val offset = (progress * content.width).coerceIn(0f, content.width * MAX_OFFSET_FRACTION)
        val x = captureX + offset
        if (x == lastX) return
        dispatch(MotionEvent.ACTION_MOVE, x, maxOf(SystemClock.uptimeMillis(), lastTime + 1))
    }

    /** Releases with a rightward fling: the engine settles off-screen and pops the chat. */
    fun commit() = release(stepPx)

    /**
     * Releases back to the start. After the platform springs progress to 0 the chat is already
     * there and a plain UP settles it; a cancel that skipped the spring gets a leftward fling
     * first.
     */
    fun cancel() {
        if (content.left > 0) release(-stepPx) else end(MotionEvent.ACTION_UP)
    }

    private fun start(): Boolean {
        val slop = ViewConfiguration.get(layout.context).scaledTouchSlop.toFloat()
        dispatch(MotionEvent.ACTION_DOWN, 0f, downTime)
        dispatch(MotionEvent.ACTION_MOVE, slop + 1, downTime + 1)
        // The capture MOVE only claims the content; the next pixel proves the engine is dragging.
        dispatch(MotionEvent.ACTION_MOVE, slop + 2, downTime + 2)
        if (content.left != 1) {
            end(MotionEvent.ACTION_CANCEL)
            return false
        }
        captureX = slop + 1
        session = WeakReference(layout)
        return true
    }

    private fun release(step: Float) {
        // A gap longer than VelocityTracker's ASSUME_POINTER_STOPPED_TIME (40 ms) drops the earlier
        // samples, so the release velocity (clamped by the engine to 100..300 dp/s) comes only from
        // these steps.
        var time = lastTime + STOPPED_GAP_MS
        repeat(RELEASE_SAMPLES) {
            dispatch(MotionEvent.ACTION_MOVE, lastX + step, time)
            time += SAMPLE_INTERVAL_MS
        }
        end(MotionEvent.ACTION_UP)
    }

    private fun end(action: Int) {
        dispatch(action, lastX, lastTime + 1)
        if (session?.get() === layout) session = null
    }

    private fun dispatch(action: Int, x: Float, time: Long) {
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        injecting = true
        try {
            layout.dispatchTouchEvent(event)
        } finally {
            injecting = false
            event.recycle()
        }
        lastX = x
        lastTime = time
    }

    companion object {
        private const val MAX_OFFSET_FRACTION = 0.9f
        private const val RELEASE_STEP_DP = 4f
        private const val RELEASE_SAMPLES = 4
        private const val SAMPLE_INTERVAL_MS = 8L
        private const val STOPPED_GAP_MS = 41L

        /** True while one of our synthetic events is being dispatched. */
        var injecting = false
            private set

        private var session: WeakReference<ViewGroup>? = null

        /** Whether [layout] is being driven, so real touches must not reach its engine. */
        fun isDriving(layout: View): Boolean = session?.get() === layout

        /**
         * Captures [content] in [layout]'s engine, or returns null when the engine did not drag.
         */
        fun start(layout: ViewGroup, content: View): ChatSwipeDriver? =
            ChatSwipeDriver(layout, content).takeIf { it.start() }
    }
}
