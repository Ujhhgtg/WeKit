package dev.ujhhgtg.wekit.ui.utils

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.ujhhgtg.wekit.utils.WeLogger

/** Temporary diagnostic wrapper to trace whether MotionEvents reach injected ComposeViews. */
class DebugComposeView(context: Context, private val label: String) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        WeLogger.d(
            "ViewTouch",
            "$label dispatch ${MotionEvent.actionToString(ev.actionMasked)} " +
                "x=${ev.x} y=${ev.y} handled=$handled attached=$isAttachedToWindow " +
                "childCount=$childCount",
        )
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(ev)
        WeLogger.d(
            "ViewTouch",
            "$label intercept ${MotionEvent.actionToString(ev.actionMasked)} " +
                "x=${ev.x} y=${ev.y} intercepted=$intercepted",
        )
        return intercepted
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WeLogger.d("ViewTouch", "$label onAttachedToWindow")
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        WeLogger.d("ViewTouch", "$label onWindowVisibilityChanged visibility=$visibility")
    }
}
