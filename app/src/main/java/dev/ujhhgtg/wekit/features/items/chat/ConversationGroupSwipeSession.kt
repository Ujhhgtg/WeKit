package dev.ujhhgtg.wekit.features.items.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlin.math.abs

data class ConversationGroupPagingProgress(
    val fromGroupId: String,
    val toGroupId: String,
    val progress: Float,
)

/** Coordinates one native list and a temporary outgoing capture without changing host ownership. */
class ConversationGroupSwipeSession(
    private val sourceView: View,
    private val pager: ViewGroup,
    private val tabHost: ConversationGroupTabsHost,
    private val lifecycleOwner: LifecycleOwner,
    private val groupIds: () -> List<String>,
    private val selectedGroupId: () -> String,
    private val enabled: () -> Boolean,
    private val swipeEnabled: () -> Boolean,
    private val rememberScrollState: () -> Boolean,
    private val canStart: () -> Boolean,
    private val prepareGroup: (String, (Boolean) -> Unit) -> Unit,
    private val cancelPreparation: () -> Unit,
    private val commitGroup: (String) -> Unit,
    private val onVisualState: (View?, ConversationGroupPagingProgress?) -> Unit,
    private val onFailure: () -> Unit,
) {
    private enum class Phase { IDLE, PREPARING, DRAGGING, SETTLING, RESTORING }
    private enum class DeferredIntent { WAIT, SWIPE, DISCARD }

    private val touchSlopPx = ViewConfiguration.get(sourceView.context).scaledTouchSlop.toFloat()
    private val gesture = ConversationGroupSwipeState(touchSlopPx)
    private val sourceBounds = Rect()
    private val pagerBounds = Rect()
    private val tabBounds = Rect()
    private val windowOffset = IntArray(2)
    private val easing = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private var phase = Phase.IDLE
    private var transition: ConversationGroupTransitionView? = null
    private var animator: ValueAnimator? = null
    private var originalPosition: ConversationGroupScrollPosition? = null
    private val scrollPositions = mutableMapOf<String, ConversationGroupScrollPosition>()
    private var originGroupId: String? = null
    private var targetGroupId: String? = null
    private var downGroupIds: List<String> = emptyList()
    private var direction = ConversationGroupSwipeState.Direction.NEXT
    private var progress = 0f
    private var pendingSettlement: Boolean? = null
    private var animateTransition = true
    private var preparationInFlight = false
    private var preparationSequence = 0
    private var restoreObserver: ViewTreeObserver? = null
    private var restoreListener: ViewTreeObserver.OnPreDrawListener? = null
    private var restoreScrollTask: Runnable? = null
    private var cancellingHost = false
    private var immediateAbort = false
    private var disposed = false
    private var failureReported = false
    private val deferredEvents = ArrayDeque<MotionEvent>()
    private var replayingEvents = false
    private var replayPosted = false
    private var discardTouchStream = false
    private val replayEvents = Runnable {
        replayPosted = false
        replayDeferredEvents()
    }

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
        oldLeft, oldTop, oldRight, oldBottom ->
        if (phase != Phase.IDLE &&
            (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)
        ) {
            abortImmediately()
        }
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = abortImmediately()
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> abortImmediately()
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }

    init {
        sourceView.addOnLayoutChangeListener(layoutListener)
        pager.addOnLayoutChangeListener(layoutListener)
        sourceView.addOnAttachStateChangeListener(attachListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    }

    val defersHomeSidePanel: Boolean
        get() = !cancellingHost && (discardTouchStream ||
            (!replayingEvents && deferredEvents.isNotEmpty()) ||
            !immediateAbort && (phase != Phase.IDLE ||
            gesture.owner == ConversationGroupSwipeState.Owner.PENDING ||
            gesture.owner == ConversationGroupSwipeState.Owner.NATIVE_VERTICAL ||
            gesture.owner == ConversationGroupSwipeState.Owner.GROUP))

    /** Called before the native pager dispatches an event. True means this session consumed it. */
    fun beforeDispatch(event: MotionEvent): Boolean {
        if (cancellingHost) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            discardTouchStream = false
            if (gesture.owner == ConversationGroupSwipeState.Owner.GROUP) cancel()
            // A new finger starts from the settled group, not from the previous gesture's state.
            // The native target is already ready here; only the decorative animation remains.
            if (phase == Phase.SETTLING && !immediateAbort) {
                stopAnimator()
                finishSettlement(pendingSettlement == true)
            }
        }
        if (discardTouchStream) return true
        if (!replayingEvents && !immediateAbort && !disposed && enabled() &&
            (deferredEvents.isNotEmpty() ||
                event.actionMasked == MotionEvent.ACTION_DOWN && phase != Phase.IDLE)
        ) {
            // Preparation/rollback may still need the native list. Preserve DOWN and its entire
            // stream so finishing that work cannot hand an orphan MOVE to the outer pager/panel.
            deferredEvents.addLast(MotionEvent.obtain(event))
            scheduleDeferredEvents()
            return true
        }
        if (phase != Phase.IDLE) {
            if (immediateAbort) return false
            if (!enabled()) cancel()
            handleOwnedEvent(event)
            return true
        }
        if (disposed || !enabled() || !swipeEnabled()) {
            gesture.onCancel()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            failureReported = false
            val ids = groupIds()
            val selected = selectedGroupId()
            val index = ids.indexOf(selected)
            val eligible = index >= 0 && ids.size > 1 &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                sourceView.isAttachedToWindow && sourceView.isShown && canStart() &&
                isFullyVisibleHome() && isInsideListOutsideTabs(event)
            downGroupIds = if (eligible) ids.toList() else emptyList()
            originGroupId = if (eligible) selected else null
            gesture.onDown(
                event.rawX, event.rawY, sourceView.width.coerceAtLeast(1).toFloat(), event.eventTime,
                canGoPrevious = index > 0,
                canGoNext = index >= 0 && index < ids.lastIndex,
                eligible = eligible,
            )
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            gesture.onChildClaimed()
        }
        return false
    }

    /** Let DOWN initialize the native pager; defer MOVE only while an inner group is possible. */
    fun shouldDeferInterception(event: MotionEvent): Boolean {
        if (cancellingHost) return false
        if (discardTouchStream || !replayingEvents && deferredEvents.isNotEmpty()) return true
        if (phase != Phase.IDLE) return !immediateAbort
        if (disposed || !enabled() || !swipeEnabled()) {
            gesture.onCancel()
            return false
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (event.pointerCount != 1) {
            gesture.onChildClaimed()
            return false
        }
        gesture.onMove(event.rawX, event.rawY, event.eventTime)
        return gesture.owner == ConversationGroupSwipeState.Owner.PENDING ||
            gesture.owner == ConversationGroupSwipeState.Owner.GROUP
    }

    /** Called after native children had the chance to claim the deciding horizontal MOVE. */
    fun afterDispatch(event: MotionEvent, cancelHost: () -> Unit): Boolean {
        if (cancellingHost) return false
        if (discardTouchStream || !replayingEvents && deferredEvents.isNotEmpty()) return true
        if (phase != Phase.IDLE) return !immediateAbort
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) return false
                val candidate = gesture.onMove(event.rawX, event.rawY, event.eventTime)
                    ?: return false
                if (gesture.owner == ConversationGroupSwipeState.Owner.PENDING) {
                    return claim(candidate, cancelHost)
                }
            }
            MotionEvent.ACTION_UP -> gesture.onUp(event.eventTime)
            MotionEvent.ACTION_CANCEL -> gesture.onCancel()
        }
        return false
    }

    fun onChildClaimed() {
        if (!cancellingHost) gesture.onChildClaimed()
    }

    fun onPagerViewportChanged() {
        if (phase != Phase.IDLE && !isFullyVisibleHome()) abortImmediately()
    }

    /** Clicking a tab uses the same native preparation and cancellation path as a finger swipe. */
    fun selectGroup(groupId: String, animate: Boolean): Boolean {
        if (phase != Phase.IDLE || deferredEvents.isNotEmpty()) return true
        if (disposed || !enabled() || !canStart() || !isFullyVisibleHome() ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return false
        val ids = groupIds()
        val origin = selectedGroupId()
        val from = ids.indexOf(origin)
        val to = ids.indexOf(groupId)
        if (from < 0 || to < 0) return false
        if (from == to) return true
        gesture.onCancel()
        downGroupIds = ids.toList()
        originGroupId = origin
        return beginTransition(
            groupId,
            if (to > from) ConversationGroupSwipeState.Direction.NEXT
            else ConversationGroupSwipeState.Direction.PREVIOUS,
            animate,
            cancelHost = null,
        )
    }

    fun forgetScrollPositions() = scrollPositions.clear()

    private fun isFullyVisibleHome(): Boolean {
        if (!sourceView.getGlobalVisibleRect(sourceBounds) || !pager.getGlobalVisibleRect(pagerBounds)) {
            return false
        }
        // Global visible rectangles are relative to the window root; MotionEvent.rawX/Y are
        // screen coordinates, including a freeform or split-screen window's offset.
        sourceView.rootView.getLocationOnScreen(windowOffset)
        sourceBounds.offset(windowOffset[0], windowOffset[1])
        pagerBounds.offset(windowOffset[0], windowOffset[1])
        return sourceBounds.width() >= sourceView.width - 1 &&
            abs(sourceBounds.left - pagerBounds.left) <= 1 &&
            abs(sourceBounds.right - pagerBounds.right) <= 1
    }

    private fun isInsideListOutsideTabs(event: MotionEvent): Boolean {
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        val tabsVisible = tabHost.isShown && tabHost.getGlobalVisibleRect(tabBounds)
        if (tabsVisible) tabBounds.offset(windowOffset[0], windowOffset[1])
        return sourceBounds.contains(x, y) && !(tabsVisible && tabBounds.contains(x, y))
    }

    private fun claim(candidate: ConversationGroupSwipeState.Direction, cancelHost: () -> Unit): Boolean {
        val origin = originGroupId ?: return false
        if (disposed || !enabled() || !canStart() || !isFullyVisibleHome() ||
            selectedGroupId() != origin || groupIds() != downGroupIds
        ) {
            gesture.onCancel()
            return false
        }
        val originIndex = downGroupIds.indexOf(origin)
        val targetIndex = originIndex + if (candidate == ConversationGroupSwipeState.Direction.NEXT) 1 else -1
        val target = downGroupIds.getOrNull(targetIndex) ?: return false
        return beginTransition(target, candidate, animate = true, cancelHost)
    }

    private fun beginTransition(
        target: String,
        candidate: ConversationGroupSwipeState.Direction,
        animate: Boolean,
        cancelHost: (() -> Unit)?,
    ): Boolean {
        val parent = sourceView.parent as? ViewGroup
        if (parent == null || tabHost.parent !== parent) {
            gesture.onCancel()
            return false
        }
        val overlay = ConversationGroupTransitionView(sourceView)
        val params = when (val original = sourceView.layoutParams) {
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(original)
            is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(original)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(original)
            else -> ViewGroup.LayoutParams(original)
        }
        val position: ConversationGroupScrollPosition?
        try {
            // A floating tab is a sibling of the list: its DOWN did not stop the list's fling.
            ConversationGroupScrollPosition.stopScrolling(sourceView)
            position = ConversationGroupScrollPosition.capture(sourceView)
            sourceView.isPressed = false
            sourceView.cancelLongPress()
            sourceView.jumpDrawablesToCurrentState()
            parent.addView(overlay, parent.indexOfChild(sourceView) + 1, params)
        } catch (error: Exception) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            gesture.onCancel()
            reportFailure(error)
            return false
        }
        when (val result = overlay.begin()) {
            ConversationGroupTransitionView.BeginResult.Started -> Unit
            else -> {
                parent.removeView(overlay)
                gesture.onCancel()
                val error = when (result) {
                    is ConversationGroupTransitionView.BeginResult.AllocationFailed -> result.cause
                    is ConversationGroupTransitionView.BeginResult.CaptureFailed -> result.cause
                    else -> IllegalStateException("conversation transition capture unavailable: $result")
                }
                reportFailure(error)
                return false
            }
        }
        if (cancelHost != null && !gesture.claimGroup()) {
            overlay.dispose()
            parent.removeView(overlay)
            return false
        }

        transition = overlay
        originalPosition = position
        if (rememberScrollState()) {
            scrollPositions.keys.retainAll(downGroupIds.toSet())
            if (position != null) scrollPositions[originGroupId!!] = position
        } else scrollPositions.clear()
        targetGroupId = target
        direction = candidate
        progress = if (cancelHost != null) gesture.progress else 0f
        pendingSettlement = if (cancelHost != null) null else true
        animateTransition = animate
        immediateAbort = false
        failureReported = false
        phase = Phase.PREPARING
        overlay.onDrawingFailed = { error ->
            sourceView.post {
                reportFailure(error)
                abortImmediately()
            }
        }
        tabHost.setPagingTransitionActive(true)
        publishVisual(0f)
        cancellingHost = true
        try {
            cancelHost?.invoke()
        } catch (error: RuntimeException) {
            abortImmediately()
            reportFailure(error)
            return true
        } finally {
            cancellingHost = false
        }
        pager.requestDisallowInterceptTouchEvent(true)
        if (phase != Phase.PREPARING || immediateAbort) return true
        requestPreparation(target) { ready ->
            if (phase != Phase.PREPARING) return@requestPreparation
            if (!enabled() || groupIds() != downGroupIds) pendingSettlement = false
            if (!ready) {
                pendingSettlement = false
                reportFailure(IllegalStateException("target conversation group did not become ready"))
                if (phase != Phase.PREPARING) return@requestPreparation
            }
            if (immediateAbort || pendingSettlement == false || !ready) {
                restoreOrigin()
            } else {
                restoreScrollPosition(if (rememberScrollState()) scrollPositions[target] else null) {
                    if (!enabled() || groupIds() != downGroupIds || pendingSettlement == false) {
                        restoreOrigin()
                    } else {
                        phase = Phase.DRAGGING
                        transition!!.setIncomingReady(true)
                        publishVisual(progress)
                        pendingSettlement?.let(::settle)
                    }
                }
            }
        }
        return true
    }

    private fun handleOwnedEvent(event: MotionEvent) {
        if (groupIds() != downGroupIds) {
            cancel()
            return
        }
        if (gesture.owner != ConversationGroupSwipeState.Owner.GROUP) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) {
                    cancel()
                    return
                }
                gesture.onMove(event.rawX, event.rawY, event.eventTime)
                progress = gesture.progress
                if (phase == Phase.DRAGGING) publishVisual(progress)
            }
            MotionEvent.ACTION_UP -> {
                progress = gesture.progress
                val settlement = gesture.onUp(event.eventTime) ?: return
                pendingSettlement = settlement.commit
                if (phase == Phase.DRAGGING) settle(settlement.commit)
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> cancel()
        }
    }

    private fun publishVisual(value: Float) {
        val overlay = transition ?: return
        val visualProgress = if (overlay.incomingReady) value else 0f
        overlay.updateProgress(
            if (direction == ConversationGroupSwipeState.Direction.NEXT) 1 else -1,
            visualProgress,
        )
        onVisualState(
            overlay,
            ConversationGroupPagingProgress(originGroupId!!, targetGroupId!!, visualProgress),
        )
    }

    private fun settle(commit: Boolean) {
        stopAnimator()
        phase = Phase.SETTLING
        val target = if (commit) 1f else 0f
        val from = progress
        if (!animateTransition || from == target ||
            deferredEvents.firstOrNull()?.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            finishSettlement(commit)
            return
        }
        animator = ValueAnimator.ofFloat(from, target).apply {
            duration = (180L + (80f * abs(target - from)).toLong()).coerceIn(180L, 260L)
            interpolator = easing
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                publishVisual(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    finishSettlement(commit)
                }
            })
            start()
        }
    }

    private fun finishSettlement(commit: Boolean) {
        if (commit && !immediateAbort && enabled() && groupIds() == downGroupIds) {
            try {
                commitGroup(targetGroupId!!)
            } finally {
                finishSession()
            }
        } else {
            restoreOrigin()
        }
    }

    /** A pending query is allowed to finish before the restore query starts. */
    fun cancel() {
        gesture.onCancel()
        if (phase == Phase.IDLE) return
        pendingSettlement = false
        when (phase) {
            Phase.DRAGGING, Phase.SETTLING -> settle(false)
            Phase.PREPARING, Phase.RESTORING, Phase.IDLE -> Unit
        }
    }

    fun cancelImmediately() = abortImmediately()

    private fun restoreOrigin() {
        if (preparationInFlight || phase == Phase.RESTORING) return
        if (disposed) {
            finishSession()
            return
        }
        stopAnimator()
        phase = Phase.RESTORING
        progress = 0f
        transition?.setIncomingReady(false)
        publishVisual(0f)
        val ids = groupIds()
        val origin = originGroupId!!
        val restoreGroup = origin.takeIf { it in ids }
            ?: selectedGroupId().takeIf { it in ids } ?: ids.firstOrNull()
        if (restoreGroup == null) {
            finishSession()
            return
        }
        requestPreparation(restoreGroup) { ready ->
            if (!ready) {
                reportFailure(IllegalStateException("original conversation group did not become ready"))
                finishSession()
                return@requestPreparation
            }
            restoreScrollPosition(if (restoreGroup == origin) originalPosition else null) {
                if (restoreGroup != origin) commitGroup(restoreGroup)
                finishSession()
            }
        }
    }

    private fun requestPreparation(groupId: String, onReady: (Boolean) -> Unit) {
        check(!preparationInFlight) { "conversation group preparations must be serial" }
        preparationInFlight = true
        val sequence = ++preparationSequence
        var delivered = false
        val completion: (Boolean) -> Unit = { ready ->
            if (!delivered && sequence == preparationSequence) {
                delivered = true
                preparationInFlight = false
                try {
                    onReady(ready)
                } catch (error: Exception) {
                    abortImmediately()
                    reportFailure(error)
                }
            }
        }
        try {
            prepareGroup(groupId, completion)
        } catch (error: Exception) {
            WeLogger.e(TAG, "conversation group preparation failed", error)
            completion(false)
        }
    }

    private fun restoreScrollPosition(position: ConversationGroupScrollPosition?, completed: () -> Unit) {
        removeRestoreListener()
        val expectedPhase = phase
        val applyPosition = Runnable {
            restoreScrollTask = null
            if (phase != expectedPhase || disposed) return@Runnable
            try {
                if (position == null) ConversationGroupScrollPosition.reset(sourceView)
                else position.restore(sourceView)
                if (immediateAbort || !sourceView.isAttachedToWindow ||
                    !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    completed()
                } else {
                    val observer = sourceView.viewTreeObserver
                    val listener = ViewTreeObserver.OnPreDrawListener {
                        removeRestoreListener()
                        if (phase == expectedPhase) completed()
                        true
                    }
                    restoreObserver = observer
                    restoreListener = listener
                    observer.addOnPreDrawListener(listener)
                    sourceView.requestLayout()
                    sourceView.invalidate()
                }
            } catch (error: Exception) {
                reportFailure(error)
                if (phase == Phase.RESTORING) finishSession() else abortImmediately()
            }
        }
        if (immediateAbort || !sourceView.isAttachedToWindow) applyPosition.run()
        else {
            // Native Recycler footer handling can schedule its own reset during the same pre-draw
            // that reports the target ready. Restore after it, then wait for our viewport layout.
            restoreScrollTask = applyPosition
            sourceView.post(applyPosition)
        }
    }

    private fun abortImmediately() {
        discardDeferredEvents()
        gesture.onCancel()
        if (phase == Phase.IDLE) return
        immediateAbort = true
        pendingSettlement = false
        stopAnimator()
        removeRestoreListener()
        clearVisual()
        // Releasing the visual also cancels its pending native preparation. A detached page can
        // complete the resulting restore synchronously and finish this session inside that callback.
        if (phase == Phase.IDLE) return
        if (phase == Phase.RESTORING) {
            if (!preparationInFlight) finishSession()
        } else if (!preparationInFlight) {
            restoreOrigin()
        }
    }

    private fun stopAnimator() {
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
    }

    private fun removeRestoreListener() {
        restoreScrollTask?.let(sourceView::removeCallbacks)
        restoreScrollTask = null
        val listener = restoreListener ?: return
        val observer = restoreObserver
        (if (observer != null && observer.isAlive) observer else sourceView.viewTreeObserver)
            .removeOnPreDrawListener(listener)
        restoreObserver = null
        restoreListener = null
    }

    private fun clearVisual() {
        val overlay = transition
        transition = null
        if (overlay != null) {
            overlay.onDrawingFailed = null
            overlay.dispose()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        onVisualState(null, null)
        tabHost.setPagingTransitionActive(false)
        pager.requestDisallowInterceptTouchEvent(false)
    }

    private fun finishSession() {
        stopAnimator()
        removeRestoreListener()
        gesture.onCancel()
        originalPosition = null
        originGroupId = null
        targetGroupId = null
        downGroupIds = emptyList()
        pendingSettlement = null
        progress = 0f
        immediateAbort = false
        phase = Phase.IDLE
        clearVisual()
        scheduleDeferredEvents()
    }

    private fun scheduleDeferredEvents() {
        if (phase != Phase.IDLE || replayPosted || replayingEvents || deferredEvents.isEmpty()) return
        replayPosted = true
        pager.post(replayEvents)
    }

    private fun replayDeferredEvents() {
        if (disposed || !enabled() || !sourceView.isAttachedToWindow ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            discardDeferredEvents()
            return
        }
        replayingEvents = true
        try {
            while (deferredEvents.isNotEmpty()) {
                if (deferredEvents.first().actionMasked == MotionEvent.ACTION_DOWN) {
                    if (phase != Phase.IDLE) {
                        if (phase != Phase.SETTLING || immediateAbort) break
                        stopAnimator()
                        finishSettlement(pendingSettlement == true)
                        if (phase != Phase.IDLE) break
                    }
                    when (deferredHorizontalIntent()) {
                        DeferredIntent.WAIT -> break
                        DeferredIntent.DISCARD -> {
                            do {
                                deferredEvents.removeFirst().recycle()
                            } while (deferredEvents.isNotEmpty() &&
                                deferredEvents.first().actionMasked != MotionEvent.ACTION_DOWN)
                            if (deferredEvents.isEmpty()) discardTouchStream = true
                            continue
                        }
                        DeferredIntent.SWIPE -> Unit
                    }
                }
                val event = deferredEvents.removeFirst()
                try {
                    // Use normal dispatch, including hooks and children: a deferred stream must
                    // retain row-swipe priority and normal outward handoff at the first/last group.
                    pager.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        } finally {
            replayingEvents = false
        }
    }

    private fun deferredHorizontalIntent(): DeferredIntent {
        val events = deferredEvents.iterator()
        val down = events.next()
        val intent = ConversationGroupSwipeState(touchSlopPx)
        intent.onDown(
            down.rawX, down.rawY, sourceView.width.coerceAtLeast(1).toFloat(), down.eventTime,
            canGoPrevious = true, canGoNext = true,
        )
        // The finger went down on a frozen page. Only replay a confirmed swipe; replaying a tap
        // (or DOWN alone before its later UP) could open a different row on the newly loaded page.
        while (events.hasNext()) {
            val event = events.next()
            if (event.actionMasked != MotionEvent.ACTION_MOVE || event.pointerCount != 1) {
                return DeferredIntent.DISCARD
            }
            if (intent.onMove(event.rawX, event.rawY, event.eventTime) != null) return DeferredIntent.SWIPE
            if (intent.owner == ConversationGroupSwipeState.Owner.NATIVE_VERTICAL) return DeferredIntent.DISCARD
        }
        return DeferredIntent.WAIT
    }

    private fun discardDeferredEvents() {
        pager.removeCallbacks(replayEvents)
        replayPosted = false
        if (deferredEvents.isNotEmpty()) discardTouchStream = true
        while (deferredEvents.isNotEmpty()) deferredEvents.removeFirst().recycle()
    }

    private fun reportFailure(error: Throwable) {
        WeLogger.e(TAG, "conversation group swipe failed", error)
        if (!failureReported && !immediateAbort && !disposed && enabled()) {
            failureReported = true
            onFailure()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        scrollPositions.clear()
        sourceView.removeOnLayoutChangeListener(layoutListener)
        pager.removeOnLayoutChangeListener(layoutListener)
        sourceView.removeOnAttachStateChangeListener(attachListener)
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        cancelPreparation()
        abortImmediately()
    }

    private companion object {
        const val TAG = "ConversationGroupSwipeSession"
    }
}
