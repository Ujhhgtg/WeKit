package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlin.math.roundToInt

/**
 * A temporary sibling of the native conversation list. The outgoing page is captured once; the
 * incoming page is drawn from the same native list after its new group has finished binding.
 * The caller owns gesture arbitration, data readiness, positioning after layout, and removal.
 */
class ConversationGroupTransitionView(val sourceView: View) : View(sourceView.context) {
    sealed interface BeginResult {
        data object Started : BeginResult
        data object AlreadyActive : BeginResult
        data object SourceNotReady : BeginResult
        data object InvalidParent : BeginResult
        data class AllocationFailed(val cause: Throwable) : BeginResult
        data class CaptureFailed(val cause: Throwable) : BeginResult
    }

    private val sourceLocation = IntArray(2)
    private val parentLocation = IntArray(2)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var outgoingBitmap: Bitmap? = null
    private var originalAlpha = 1f
    private var direction = 1
    private var progress = 0f
    private var incomingIsReady = false

    val isActive: Boolean
        get() = outgoingBitmap != null

    val incomingReady: Boolean
        get() = incomingIsReady

    /** Called after restoring the source and releasing the capture when live drawing fails. */
    var onDrawingFailed: ((Throwable) -> Unit)? = null

    init {
        visibility = INVISIBLE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Must be called on the UI thread, after this view has been added beside [sourceView]. */
    fun begin(): BeginResult {
        if (isActive) return BeginResult.AlreadyActive
        if (parent == null || parent !== sourceView.parent) return BeginResult.InvalidParent
        if (!sourceView.isAttachedToWindow || !sourceView.isShown || sourceView.alpha <= 0f ||
            sourceView.width <= 0 || sourceView.height <= 0
        ) {
            return BeginResult.SourceNotReady
        }
        if (!alignToSource()) return BeginResult.SourceNotReady

        val bitmap = try {
            Bitmap.createBitmap(sourceView.width, sourceView.height, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            return BeginResult.AllocationFailed(error)
        } catch (error: IllegalArgumentException) {
            return BeginResult.AllocationFailed(error)
        }
        try {
            drawSource(Canvas(bitmap))
        } catch (error: RuntimeException) {
            bitmap.recycle()
            return BeginResult.CaptureFailed(error)
        } catch (error: OutOfMemoryError) {
            bitmap.recycle()
            return BeginResult.CaptureFailed(error)
        }

        originalAlpha = sourceView.alpha
        bitmapPaint.alpha = (originalAlpha * 255f).roundToInt().coerceIn(0, 255)
        outgoingBitmap = bitmap
        incomingIsReady = false
        direction = 1
        progress = 0f
        sourceView.alpha = 0f
        visibility = VISIBLE
        invalidate()
        return BeginResult.Started
    }

    /** Preparing a group keeps the original frozen page stationary until the real list is ready. */
    fun setIncomingReady(ready: Boolean) {
        if (!isActive || incomingReady == ready) return
        incomingIsReady = ready
        invalidate()
    }

    /** [direction] is +1 for the next group (pages move left), or -1 for the previous group. */
    fun updateProgress(direction: Int, progress: Float) {
        require(direction == -1 || direction == 1) { "direction must be -1 or 1" }
        require(progress.isFinite()) { "progress must be finite" }
        if (!isActive) return
        val boundedProgress = progress.coerceIn(0f, 1f)
        if (this.direction == direction && this.progress == boundedProgress) return
        this.direction = direction
        this.progress = boundedProgress
        invalidate()
    }

    /** Aligns only this overlay. No listener, source translation, scrolling, or reparenting is used. */
    fun alignToSource(): Boolean {
        val parentView = parent as? ViewGroup ?: return false
        if (parentView !== sourceView.parent || sourceView.width <= 0 || sourceView.height <= 0) {
            return false
        }
        val bitmap = outgoingBitmap
        if (bitmap != null && (bitmap.width != sourceView.width || bitmap.height != sourceView.height)) {
            // A frozen capture cannot represent a resized source; the controller must cancel it.
            return false
        }
        sourceView.getLocationInWindow(sourceLocation)
        parentView.getLocationInWindow(parentLocation)
        val targetLeft = sourceLocation[0] - parentLocation[0] + parentView.scrollX
        val targetTop = sourceLocation[1] - parentLocation[1] + parentView.scrollY
        val sourceWidth = sourceView.width
        val sourceHeight = sourceView.height
        if (layoutParams.width != sourceWidth || layoutParams.height != sourceHeight) {
            layoutParams = layoutParams.apply {
                width = sourceWidth
                height = sourceHeight
            }
        }
        measure(
            MeasureSpec.makeMeasureSpec(sourceWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(sourceHeight, MeasureSpec.EXACTLY),
        )
        layout(targetLeft, targetTop, targetLeft + sourceWidth, targetTop + sourceHeight)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = outgoingBitmap ?: return
        if (!incomingReady) {
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            return
        }

        val pageWidth = bitmap.width.toFloat()
        val pageHeight = bitmap.height.toFloat()
        val outgoingX = -direction * progress * pageWidth
        val incomingX = direction * (1f - progress) * pageWidth
        val outgoingSave = canvas.save()
        try {
            canvas.clipRect(0f, 0f, pageWidth, pageHeight)
            canvas.translate(outgoingX, 0f)
            canvas.clipRect(0f, 0f, pageWidth, pageHeight)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        } finally {
            canvas.restoreToCount(outgoingSave)
        }

        if (progress == 0f) return
        val incomingSave = canvas.save()
        var failure: Throwable? = null
        try {
            canvas.clipRect(0f, 0f, pageWidth, pageHeight)
            canvas.translate(incomingX, 0f)
            canvas.clipRect(0f, 0f, pageWidth, pageHeight)
            if (bitmapPaint.alpha < 255) {
                canvas.saveLayerAlpha(0f, 0f, pageWidth, pageHeight, bitmapPaint.alpha)
            }
            drawSource(canvas)
        } catch (error: RuntimeException) {
            failure = error
        } catch (error: OutOfMemoryError) {
            failure = error
        } finally {
            canvas.restoreToCount(incomingSave)
        }
        failure?.let { error ->
            finish()
            WeLogger.e(TAG, "conversation group transition drawing failed", error)
            onDrawingFailed?.invoke(error)
        }
    }

    private fun drawSource(canvas: Canvas) {
        val save = canvas.save()
        try {
            canvas.clipRect(0, 0, sourceView.width, sourceView.height)
            sourceView.clipBounds?.let(canvas::clipRect)
            // Public View.draw does not apply the scroll transform or root alpha normally added
            // by its parent. Keep native coordinates intact and apply those here, like ViewBackdrop.
            canvas.translate(-sourceView.scrollX.toFloat(), -sourceView.scrollY.toFloat())
            sourceView.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** Restores visibility for both completion and cancellation; the caller restores group data. */
    fun finish() {
        val bitmap = outgoingBitmap ?: return
        outgoingBitmap = null
        incomingIsReady = false
        progress = 0f
        visibility = INVISIBLE
        sourceView.alpha = originalAlpha
        bitmap.recycle()
        invalidate()
    }

    fun dispose() {
        finish()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private companion object {
        const val TAG = "ConversationGroupTransitionView"
    }
}
