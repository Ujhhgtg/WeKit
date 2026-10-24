package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ListView
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import kotlin.math.abs

/** One interactive tab bar, with a same-height header preserving the host's list geometry. */
class ConversationGroupTabsHost(
    private val conversationView: ViewGroup,
    content: View,
    private var pinned: Boolean,
    private val onContentOverlapChanged: (Boolean) -> Unit = {},
) : FrameLayout(conversationView.context), ViewTreeObserver.OnPreDrawListener {
    private val spacer = View(context)
    private val header = FrameLayout(context).apply {
        // WeChat's getFirstHeaderVisible() requires a visible first child.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(spacer, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }
    private var headerPosition = 0
    private var observer: ViewTreeObserver? = null
    private val hostLocation = IntArray(2)
    private val parentLocation = IntArray(2)
    private val headerLocation = IntArray(2)
    private val actionBarLocation = IntArray(2)
    private val touchLocation = IntArray(2)
    private val visibleBounds = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val capsuleVerticalInset = ConversationGrouping.CAPSULE_VERTICAL_INSET_DP.dpToPx(context)
    private var contentOverlap = false
    private var pagingTransitionActive = false
    private var downEvent: MotionEvent? = null
    private var horizontalGesture = false
    private var childOwnsGesture = false
    private var forwardingScroll = false

    init {
        visibility = INVISIBLE
        isClickable = true
        accessibilityTraversalBefore = conversationView.id
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun install(mainUi: Any) {
        WeConversationListViewApi.addHeaderView(mainUi, header)
        headerPosition = WeConversationListViewApi.headerCount(conversationView) - 1
        (conversationView.parent as ViewGroup).addView(
            this,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    fun setPinned(enabled: Boolean) {
        pinned = enabled
        if (!enabled) updateContentOverlap(false)
        // This overlay may be INVISIBLE while unpinned; invalidate the visible list instead.
        conversationView.invalidate()
    }

    fun setPagingTransitionActive(active: Boolean) {
        pagingTransitionActive = active
        if (!active) conversationView.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        downEvent?.recycle()
        downEvent = null
        forwardingScroll = false
        updateContentOverlap(false)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (spacer.layoutParams.height != h) {
            spacer.layoutParams = spacer.layoutParams.apply { height = h }
        }
    }

    override fun onPreDraw(): Boolean {
        // The live list is preparing the next group behind a frozen page. Retain the current
        // tab-bar anchor until that page is committed or the original scroll state is restored.
        if (pagingTransitionActive) return true
        val actionBar = WeConversationListViewApi.actionBarView(conversationView)
        if (!conversationView.isShown || actionBar == null || !actionBar.isShown || actionBar.alpha == 0f || height == 0) {
            hideTabs()
            return true
        }
        val headerAttached = header.isAttachedToWindow && header.isShown
        if (headerAttached && conversationView is ListView) {
            val position = conversationView.getPositionForView(header)
            if (position >= 0) headerPosition = position
        }
        val scrolledPastHeader = WeConversationListViewApi.firstVisiblePosition(conversationView) > headerPosition
        if (!headerAttached && (!pinned || !scrolledPastHeader)) {
            // A missing header can also be BELOW the viewport while the recent page is open.
            hideTabs()
            return true
        }

        val parentView = parent as ViewGroup
        conversationView.getLocationInWindow(hostLocation)
        parentView.getLocationInWindow(parentLocation)
        actionBar.getLocationInWindow(actionBarLocation)
        val actionBarBottom = actionBarLocation[1] + actionBar.height
        val headerTop = if (headerAttached) {
            header.getLocationInWindow(headerLocation)
            headerLocation[1]
        } else {
            actionBarBottom
        }
        val top = if (pinned) maxOf(headerTop, actionBarBottom) else headerTop
        val contentWidth = conversationView.width - conversationView.paddingLeft - conversationView.paddingRight
        val clipTop = (maxOf(actionBarBottom, hostLocation[1] + conversationView.paddingTop) - top)
            .coerceAtLeast(0)
        val clipBottom = minOf(height, hostLocation[1] + conversationView.height - conversationView.paddingBottom - top)
        if (contentWidth <= 0 || clipBottom <= clipTop) {
            hideTabs()
            return true
        }
        if (layoutParams.width != contentWidth) {
            layoutParams = layoutParams.apply { width = contentWidth }
        }
        x = (hostLocation[0] + conversationView.paddingLeft - parentLocation[0] + parentView.scrollX).toFloat()
        y = (top - parentLocation[1] + parentView.scrollY).toFloat()
        visibleBounds.set(0, clipTop, contentWidth, clipBottom)
        clipBounds = visibleBounds
        alpha = actionBar.alpha
        visibility = VISIBLE
        // The header reserves this overlay's full height. Its transparent bottom inset must
        // scroll away before the visible capsule actually overlaps the conversations below.
        val capsuleBottom = minOf(height - capsuleVerticalInset, clipBottom)
        val overlapsConversations = if (headerAttached) {
            top + capsuleBottom > headerTop + height
        } else {
            scrolledPastHeader
        }
        updateContentOverlap(
            pinned && capsuleBottom > maxOf(capsuleVerticalInset, clipTop) && overlapsConversations,
        )
        return true
    }

    private fun updateContentOverlap(overlaps: Boolean) {
        if (contentOverlap == overlaps) return
        contentOverlap = overlaps
        onContentOverlapChanged(overlaps)
    }

    private fun hideTabs() {
        updateContentOverlap(false)
        if (forwardingScroll) {
            // Keep the active touch target until UP/CANCEL, even when pulling the recent page
            // moves the tabs out of view. Changing visibility would cancel that native drag.
            visibleBounds.setEmpty()
            clipBounds = visibleBounds
            alpha = 0f
        } else {
            visibility = INVISIBLE
        }
    }

    // The overlay is a sibling of the list, so route vertical drags back to the native list.
    // Horizontal swipes, taps and long presses stay with the single Compose tab bar.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        childOwnsGesture = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!visibleBounds.contains(event.x.toInt(), event.y.toInt())) return false
            if (ConversationGrouping.usesFloatingTabs) {
                val left = ConversationGrouping.CAPSULE_HORIZONTAL_INSET_DP.dpToPx(context).toFloat()
                val top = ConversationGrouping.CAPSULE_VERTICAL_INSET_DP.dpToPx(context).toFloat()
                val right = width - left
                val bottom = height - top
                if (event.x < left || event.x > right || event.y < top || event.y > bottom) return false
                val radius = minOf(right - left, bottom - top) / 2f
                val dx = event.x - event.x.coerceIn(left + radius, right - radius)
                val dy = event.y - event.y.coerceIn(top + radius, bottom - radius)
                // The corners and outer margins are transparent: let the parent hit the list below.
                if (dx * dx + dy * dy > radius * radius) return false
            }
            downEvent?.recycle()
            downEvent = MotionEvent.obtain(event)
            horizontalGesture = false
            childOwnsGesture = false
            forwardingScroll = false
        }
        val down = downEvent
        if (!forwardingScroll && !horizontalGesture && !childOwnsGesture && down != null &&
            event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1
        ) {
            val dx = abs(event.rawX - down.rawX)
            val dy = abs(event.rawY - down.rawY)
            if (maxOf(dx, dy) > touchSlop) {
                if (dx >= dy) {
                    horizontalGesture = true
                } else {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    forwardingScroll = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    forwardToList(down)
                }
            }
        }
        return try {
            if (forwardingScroll) {
                forwardToList(event)
                true
            } else {
                super.dispatchTouchEvent(event)
            }
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (forwardingScroll) parent.requestDisallowInterceptTouchEvent(false)
                downEvent?.recycle()
                downEvent = null
                forwardingScroll = false
                conversationView.invalidate()
            }
        }
    }

    private fun forwardToList(event: MotionEvent) {
        getLocationInWindow(touchLocation)
        conversationView.getLocationInWindow(hostLocation)
        val forwarded = MotionEvent.obtain(event)
        try {
            forwarded.offsetLocation(
                (touchLocation[0] - hostLocation[0]).toFloat(),
                (touchLocation[1] - hostLocation[1]).toFloat(),
            )
            conversationView.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }
}
