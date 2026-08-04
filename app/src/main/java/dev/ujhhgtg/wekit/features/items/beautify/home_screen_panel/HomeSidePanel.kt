package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.isNotEmpty
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.base.CustomViewPager
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.items.beautify.AddMainScreenFab
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.int
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.pow
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

internal fun homeSidePanelShouldConsumeBack(
    progress: Float,
    dragging: Boolean,
    tracking: Boolean,
): Boolean = progress > 0f || dragging || tracking

internal fun homeSidePanelShouldConsumeMoveTaskToBack(
    progress: Float,
    dragging: Boolean,
    tracking: Boolean,
): Boolean = homeSidePanelShouldConsumeBack(progress, dragging, tracking)

internal fun homeSidePanelShouldDeferEdgeToEdgeUntilDecorAttached(isAttached: Boolean): Boolean =
    !isAttached

internal fun homeSidePanelShouldReapplyEdgeToEdgeAfterTabSelection(position: Int): Boolean = position == 0

internal fun homeSidePanelIsActionBarContainerClass(className: String): Boolean =
    className == "androidx.appcompat.widget.ActionBarContainer"

internal fun homeSidePanelIsToolbarClass(className: String): Boolean =
    className == "androidx.appcompat.widget.Toolbar"

internal data class HomeSidePanelVisualTransform(
    val easedProgress: Float,
    val scale: Float,
    val translationXPx: Float,
    val translationYPx: Float,
)

internal fun homeSidePanelVisualTransform(
    progress: Float,
    density: Float,
): HomeSidePanelVisualTransform {
    val p = progress.coerceIn(0f, 1f)
    val eased = (1f - (1f - p).toDouble().pow(1.35).toFloat()).coerceIn(0f, 1f)
    return HomeSidePanelVisualTransform(
        easedProgress = eased,
        scale = 1f - 0.05f * eased,
        translationXPx = (7f * density).roundToInt().toFloat() * eased,
        translationYPx = (8f * density).roundToInt().toFloat() * eased,
    )
}

internal fun homeSidePanelShouldReparentExternalChrome(
    progress: Float,
    isCurrentHost: Boolean,
    isInContentWrapper: Boolean,
    parentClassName: String,
): Boolean =
    progress > 0f &&
        isCurrentHost &&
        !isInContentWrapper &&
        parentClassName != "androidx.appcompat.widget.ActionBarOverlayLayout"

@Suppress("DEPRECATION")
@Feature(name = "主页侧滑面板", categories = ["界面美化"], description = "在微信主页添加一个左划侧栏面板 (负一屏)")
object HomeSidePanel : SwitchFeature() {

    private const val TAG = "HomeSidePanel"
    private val sessions = WeakHashMap<WxViewPager, WeakReference<HomeSidePanelSession>>()
    private val pendingEdgeToEdgeAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val dispatchTouchEventMethod =
        CustomViewPager::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
    private val pendingHostCancel = ThreadLocal<PendingHostCancel?>()

    override fun onEnable() {
        LauncherUI::class.reflekt().firstMethodOrNull {
            name = "enableEdge2Edge"
            parameters()
        }?.hookBefore {
            result = true
        }
        LauncherUI::class.hookAfterOnCreate {
            ensureLauncherEdgeToEdge(thisObject as Activity)
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onBackPressed"
            parameters()
        }.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            val session = sessions.values.mapNotNull { it.get() }.firstOrNull { it.ownsActivity(activity) }
                ?: return@hookBefore
            if (session.consumeBack()) {
                result = null
            }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "moveTaskToBack"
            parameters(Boolean::class)
        }.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            val session = sessions.values.mapNotNull { it.get() }.firstOrNull { it.ownsActivity(activity) }
                ?: return@hookBefore
            if (session.consumeBack()) {
                WeLogger.i(TAG, "consuming LauncherUI.moveTaskToBack while drawer is open")
                result = true
            }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onDestroy"
            parameters()
        }.hookAfter {
            val activity = thisObject as Activity
            removePendingEdgeToEdgeAttachListener(activity)
            removeSessionsForActivity(activity)
        }
        dispatchTouchEventMethod.hookBefore {
            pendingHostCancel.remove()
            val pager = thisObject as? WxViewPager ?: return@hookBefore
            val session = sessions[pager]?.get() ?: return@hookBefore
            val event = args[0] as? MotionEvent ?: return@hookBefore
            when (session.onPagerTouch(event)) {
                PagerTouchResult.PASS -> Unit
                PagerTouchResult.CANCEL_HOST -> {
                    pendingHostCancel.set(PendingHostCancel(event, event.action))
                    event.action = MotionEvent.ACTION_CANCEL
                }

                PagerTouchResult.CONSUME -> result = true
            }
        }
        dispatchTouchEventMethod.hookAfter {
            val event = args[0] as? MotionEvent ?: return@hookAfter
            val pending = pendingHostCancel.get() ?: return@hookAfter
            if (pending.event !== event) return@hookAfter
            event.action = pending.originalAction
            result = true
            pendingHostCancel.remove()
        }
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            ensureLauncherEdgeToEdge(activity)
            val viewPager = thisObject!!.reflekt()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as WxViewPager
            val tabsAdapter = thisObject!!.reflekt()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!
            val parent = viewPager.parent as? FrameLayout
            if (parent == null) {
                WeLogger.e(TAG, "MainTabUI mViewPager parent is not a FrameLayout")
                return@hookAfter
            }
            if (sessions[viewPager]?.get() != null) return@hookAfter

            val session = HomeSidePanelSession(activity, parent).also { it.attach() }
            session.setSelectedTab(viewPager.currentItem)
            sessions[viewPager] = WeakReference(session)

            val reflectedTabsAdapter = tabsAdapter.reflekt()
            val onPageSelected = reflectedTabsAdapter.firstMethod {
                    name = "onPageSelected"
                    parameters(int)
                }
            onPageSelected.hookBefore {
                session.setSelectedTab(args[0] as Int)
            }
            onPageSelected.hookAfter {
                val position = args[0] as Int
                if (homeSidePanelShouldReapplyEdgeToEdgeAfterTabSelection(position)) {
                    ensureLauncherEdgeToEdge(activity)
                }
            }
            reflectedTabsAdapter.firstMethod {
                name = "onPageScrolled"
                parameters(int, float, int)
            }.hookBefore {
                val position = args[0] as Int
                val offset = args[1] as Float
                if (position != HOME_TAB_INDEX || offset > PAGE_SETTLED_EPSILON) {
                    session.setSelectedTab(-1)
                } else {
                    session.setSelectedTab(viewPager.currentItem)
                }
            }
        }
    }

    override fun onDisable() {
        sessions.values.mapNotNull { it.get() }.forEach { it.detach() }
        sessions.clear()
    }

    private fun removeSessionsForActivity(activity: Activity) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value.get()
            if (session == null || session.ownsActivity(activity)) {
                session?.detach()
                iterator.remove()
            }
        }
    }

    private fun ensureLauncherEdgeToEdge(activity: Activity) {
        val window = activity.window
        val decor = window.decorView
        if (!homeSidePanelShouldDeferEdgeToEdgeUntilDecorAttached(decor.isAttachedToWindow)) {
            applyLauncherEdgeToEdge(window)
            return
        }
        if (pendingEdgeToEdgeAttachListeners[decor] != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                pendingEdgeToEdgeAttachListeners.remove(view)
                view.removeOnAttachStateChangeListener(this)
                view.post {
                    applyLauncherEdgeToEdge(activity.window)
                }
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        pendingEdgeToEdgeAttachListeners[decor] = listener
        decor.addOnAttachStateChangeListener(listener)
        WeLogger.i(TAG, "waiting for LauncherUI decor attachment before applying edge-to-edge")
    }

    private fun removePendingEdgeToEdgeAttachListener(activity: Activity) {
        val decor = activity.window.decorView
        val listener = pendingEdgeToEdgeAttachListeners.remove(decor) ?: return
        decor.removeOnAttachStateChangeListener(listener)
    }

    private fun applyLauncherEdgeToEdge(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.decorView.requestApplyInsets()
        WeLogger.i(
            TAG,
            "LauncherUI edge-to-edge applied: " +
                "attached=${window.decorView.isAttachedToWindow}, " +
                "systemUi=${window.decorView.systemUiVisibility}",
        )
    }

    private data class PendingHostCancel(
        val event: MotionEvent,
        val originalAction: Int,
    )

    private data class ActionBarTransformSnapshot(
        val originalPivotX: Float,
        val originalPivotY: Float,
        val transformPivotX: Float,
        val transformPivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
    )

    private enum class PagerTouchResult {
        PASS,
        CANCEL_HOST,
        CONSUME,
    }

    private class HomeSidePanelSession(
        private val activity: Activity,
        private val parent: FrameLayout,
    ) {
        private val gestureConfig = homeSidePanelGestureConfig(
            density = activity.resources.displayMetrics.density,
        )
        private val gesture = HomeSidePanelGestureState(gestureConfig)
        private val decorRoot = activity.window.decorView as FrameLayout
        private val contentWrapper = FrameLayout(activity)
        private val overlayRoot = HomeSidePanelOverlayLayout(activity).also { it.session = this }
        private val dimView = View(activity)
        private val panelView = ComposeView(activity)
        private val outlineProvider = ProgressOutlineProvider()
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            absorbStrayChildren()
            updateDrawerWidth()
            resolveExternalChrome()
            applyActionBarProgress(renderedProgress)
            true
        }

        private var animator: ValueAnimator? = null
        private var drawerWidthPx = 1
        private var renderedProgress = 0f
        private var dragging = false
        private var attached = false
        private var parentClipChildren = true
        private var parentClipToPadding = true
        private var actionBarContainer: View? = null
        private var actionBarTransformSnapshot: ActionBarTransformSnapshot? = null
        private var fabHostView: View? = null
        private var fabOriginalParent: ViewGroup? = null
        private var fabOriginalLayoutParams: ViewGroup.LayoutParams? = null
        private var fabOriginalIndex = -1

        fun attach() {
            if (attached) return
            attached = true
            WeLogger.i(TAG, "attaching home side panel to ${parent.javaClass.name}")

            parentClipChildren = parent.clipChildren
            parentClipToPadding = parent.clipToPadding
            parent.clipChildren = false
            parent.clipToPadding = false

            moveExistingChildrenIntoWrapper()
            contentWrapper.clipChildren = false
            contentWrapper.clipToPadding = false
            contentWrapper.outlineProvider = outlineProvider
            contentWrapper.clipToOutline = true

            dimView.setBackgroundColor(AndroidColor.BLACK)
            dimView.alpha = 0f
            dimView.isClickable = true
            dimView.setOnClickListener {
                if (renderedProgress > CLOSED_EPSILON) close(animated = true)
            }

            panelView.setBackgroundColor(AndroidColor.TRANSPARENT)
            panelView.isClickable = true
            panelView.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity))
            panelView.setContent {
                InjectedUiTheme {
                    HomeSidePanelContent()
                }
            }
            overlayRoot.clipChildren = false
            overlayRoot.clipToPadding = false
            overlayRoot.visibility = View.GONE
            overlayRoot.addView(
                dimView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            overlayRoot.addView(
                panelView,
                FrameLayout.LayoutParams(
                    drawerWidthPx,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )

            parent.addView(
                contentWrapper,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            decorRoot.addView(
                overlayRoot,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            parent.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            parent.post {
                updateDrawerWidth()
                applyProgress(0f)
                WeLogger.i(
                    TAG,
                    "home side panel ready: parent=${parent.width}x${parent.height}, " +
                        "drawerWidth=$drawerWidthPx, touchSlop=${gestureConfig.touchSlopPx}px",
                )
            }
        }

        fun detach() {
            if (!attached) return
            attached = false
            animator?.cancel()
            animator = null
            if (parent.viewTreeObserver.isAlive) {
                parent.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            restoreActionBarTransform()
            restoreFabHostToOriginalParent()
            restoreContent()
            panelView.disposeComposition()
            decorRoot.removeView(overlayRoot)
            contentWrapper.clipToOutline = false
            parent.removeView(contentWrapper)
            parent.clipChildren = parentClipChildren
            parent.clipToPadding = parentClipToPadding
        }

        fun setSelectedTab(index: Int) {
            gesture.setSelectedTab(index)
            if (index != HOME_TAB_INDEX && renderedProgress > CLOSED_EPSILON) {
                close(animated = true)
            }
        }

        fun ownsActivity(candidate: Activity): Boolean = activity === candidate

        fun consumeBack(): Boolean {
            if (!homeSidePanelShouldConsumeMoveTaskToBack(renderedProgress, dragging, gesture.isTracking)) {
                return false
            }
            close(animated = true)
            return true
        }

        fun close(animated: Boolean) {
            val from = renderedProgress
            animator?.cancel()
            animator = null
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            gesture.close()
            if (animated) {
                animateTo(0f, from)
            } else {
                applyProgress(0f)
            }
        }

        fun onPagerTouch(event: MotionEvent): PagerTouchResult {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
                    if (gesture.selectedTabIndex == HOME_TAB_INDEX) {
                        WeLogger.i(
                            TAG,
                            "pager touch down: x=${event.x}, y=${event.y}, " +
                                "progress=$renderedProgress",
                        )
                    }
                    PagerTouchResult.PASS
                }

                MotionEvent.ACTION_MOVE -> {
                    when (gesture.onMove(event.x, event.y, event.eventTime)) {
                        HomeSidePanelGestureDecision.PASS,
                        HomeSidePanelGestureDecision.TRACKING,
                        -> PagerTouchResult.PASS

                        HomeSidePanelGestureDecision.CONSUME -> {
                            applyProgress(gesture.progress)
                            if (!dragging) {
                                dragging = true
                                parent.requestDisallowInterceptTouchEvent(true)
                                WeLogger.i(
                                    TAG,
                                    "pager drag captured: x=${event.x}, y=${event.y}, " +
                                        "progress=${gesture.progress}",
                                )
                                PagerTouchResult.CANCEL_HOST
                            } else {
                                PagerTouchResult.CONSUME
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        gesture.onCancel()
                        PagerTouchResult.PASS
                    } else {
                        val from = renderedProgress
                        val target = gesture.onUp(event.eventTime)
                        dragging = false
                        parent.requestDisallowInterceptTouchEvent(false)
                        animateTo(target, from)
                        PagerTouchResult.CONSUME
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        gesture.onCancel()
                        PagerTouchResult.PASS
                    } else {
                        val from = renderedProgress
                        val target = gesture.onCancel()
                        dragging = false
                        parent.requestDisallowInterceptTouchEvent(false)
                        animateTo(target, from)
                        PagerTouchResult.CONSUME
                    }
                }

                else -> if (dragging) PagerTouchResult.CONSUME else PagerTouchResult.PASS
            }
        }

        fun onOverlayInterceptTouch(event: MotionEvent): Boolean =
            handleIntercept(event, allowPanelPassthrough = true)

        fun onOverlayTouch(event: MotionEvent): Boolean =
            handleTouch(event)

        private fun handleIntercept(
            event: MotionEvent,
            allowPanelPassthrough: Boolean,
        ): Boolean {
            if (
                allowPanelPassthrough &&
                isInsidePanel(event.x) &&
                renderedProgress >= 0.98f &&
                homeSidePanelShouldPassFullyOpenTouchToChild(event.actionMasked)
            ) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    beginGesture(event)
                } else if (!dragging) {
                    gesture.onCancel()
                }
                return false
            }

            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val decision = gesture.onMove(event.x, event.y, event.eventTime)
                    if (decision == HomeSidePanelGestureDecision.CONSUME) {
                        if (!dragging) {
                            WeLogger.i(TAG, "drawer drag captured: x=${event.x}, progress=${gesture.progress}")
                        }
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        applyProgress(gesture.progress)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gesture.isTracking && !dragging) {
                        val target = gesture.onCancel()
                        applyProgress(target)
                    }
                    false
                }

                else -> false
            }
        }

        private fun handleTouch(event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val decision = gesture.onMove(event.x, event.y, event.eventTime)
                    if (decision != HomeSidePanelGestureDecision.PASS) {
                        dragging = decision == HomeSidePanelGestureDecision.CONSUME
                        applyProgress(gesture.progress)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val from = renderedProgress
                    val target = gesture.onUp(event.eventTime)
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    animateTo(target, from)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    val from = renderedProgress
                    val target = gesture.onCancel()
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    animateTo(target, from)
                    true
                }

                else -> renderedProgress > CLOSED_EPSILON
            }
        }

        private fun beginGesture(event: MotionEvent) {
            animator?.cancel()
            animator = null
            gesture.snapTo(renderedProgress)
            gesture.onDown(
                x = event.x,
                y = event.y,
                widthPx = parent.width.toFloat(),
                timeMs = event.eventTime,
            )
            dragging = false
            if (renderedProgress > CLOSED_EPSILON) overlayRoot.visibility = View.VISIBLE
        }

        private fun animateTo(target: Float, from: Float = renderedProgress) {
            animator?.cancel()
            animator = null
            if (kotlin.math.abs(from - target) < 0.001f) {
                gesture.snapTo(target)
                applyProgress(target)
                return
            }
            overlayRoot.visibility = View.VISIBLE
            var canceled = false
            animator = ValueAnimator.ofFloat(from, target).apply {
                duration = (180L + 180L * kotlin.math.abs(from - target)).roundToInt().toLong()
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    gesture.snapTo(progress)
                    applyProgress(progress)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (canceled) return
                        gesture.snapTo(target)
                        applyProgress(target)
                        if (animator === animation) animator = null
                    }
                })
                start()
            }
        }

        private fun resolveExternalChrome() {
            val actionBarCandidate = actionBarContainer?.takeIf { it.parent != null }
                ?: decorRoot.findViewWhich {
                    homeSidePanelIsActionBarContainerClass(it.javaClass.name)
                }
            if (actionBarContainer?.parent == null && actionBarCandidate == null) {
                restoreActionBarTransform()
                actionBarContainer = null
            }
            if (actionBarCandidate != null && actionBarContainer !== actionBarCandidate) {
                restoreActionBarTransform()
                actionBarContainer = actionBarCandidate
                val toolbar = actionBarCandidate.findViewWhich {
                    homeSidePanelIsToolbarClass(it.javaClass.name)
                }
                WeLogger.i(
                    TAG,
                    "resolved actionbar chrome in place: container=${actionBarCandidate.javaClass.name}, " +
                        "parent=${actionBarCandidate.parent.javaClass.name}, " +
                        "toolbar=${toolbar?.javaClass?.name ?: "missing"}",
                )
            }
            val fabCandidate = AddMainScreenFab.hostViewFor(activity)?.takeIf { it.parent != null }
            if (fabHostView?.parent == null && fabCandidate == null) {
                fabHostView = null
            }
            if (fabCandidate != null && fabHostView !== fabCandidate) {
                restoreFabHostToOriginalParent()
                moveFabHostIntoContentWrapper(fabCandidate)
                WeLogger.i(TAG, "resolved AddMainScreenFab host: ${fabCandidate.javaClass.name}")
            }
            if (
                fabCandidate != null &&
                homeSidePanelShouldReparentExternalChrome(
                    progress = renderedProgress,
                    isCurrentHost = fabHostView === fabCandidate,
                    isInContentWrapper = fabCandidate.parent === contentWrapper,
                    parentClassName = fabCandidate.parent.javaClass.name,
                )
            ) {
                moveFabHostIntoContentWrapper(fabCandidate)
            }
        }

        private fun applyProgress(progress: Float) {
            val p = progress.coerceIn(0f, 1f)
            renderedProgress = p
            updateDrawerWidth()
            resolveExternalChrome()

            val transform = homeSidePanelVisualTransform(
                progress = p,
                density = activity.resources.displayMetrics.density,
            )

            contentWrapper.pivotX = contentWrapper.width / 2f
            contentWrapper.pivotY = contentWrapper.height / 2f
            contentWrapper.scaleX = transform.scale
            contentWrapper.scaleY = transform.scale
            contentWrapper.translationX = transform.translationXPx
            contentWrapper.translationY = transform.translationYPx
            outlineProvider.radiusPx = 28.dpToPx(activity).toFloat() * transform.easedProgress
            contentWrapper.invalidateOutline()

            if (p > CLOSED_EPSILON) {
                fabHostView?.let { moveFabHostIntoContentWrapper(it) }
            }
            applyActionBarProgress(p, transform)

            dimView.alpha = DIM_MAX_ALPHA * transform.easedProgress
            dimView.isClickable = p > CLOSED_EPSILON

            panelView.translationX = -drawerWidthPx * (1f - p)
            overlayRoot.isClickable = p > CLOSED_EPSILON || dragging
            overlayRoot.visibility = if (p > CLOSED_EPSILON || dragging) View.VISIBLE else View.GONE
            overlayRoot.bringToFront()
        }

        private fun updateDrawerWidth() {
            val width = parent.width
            if (width <= 0) return
            val nextWidth = (width * DRAWER_WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
            if (nextWidth == drawerWidthPx) return
            drawerWidthPx = nextWidth
            val params = panelView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(drawerWidthPx, FrameLayout.LayoutParams.MATCH_PARENT)
            params.width = drawerWidthPx
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            panelView.layoutParams = params
        }

        private fun applyActionBarProgress(
            progress: Float,
            transform: HomeSidePanelVisualTransform = homeSidePanelVisualTransform(
                progress = progress,
                density = activity.resources.displayMetrics.density,
            ),
        ) {
            val actionBar = actionBarContainer ?: return
            if (progress <= CLOSED_EPSILON) {
                restoreActionBarTransform()
                return
            }
            val snapshot = actionBarTransformSnapshot ?: captureActionBarTransform(actionBar).also {
                actionBarTransformSnapshot = it
                WeLogger.i(TAG, "applying in-place side-panel transform to ActionBarContainer")
            }
            if (actionBar.pivotX != snapshot.transformPivotX) {
                actionBar.pivotX = snapshot.transformPivotX
            }
            if (actionBar.pivotY != snapshot.transformPivotY) {
                actionBar.pivotY = snapshot.transformPivotY
            }
            val scaleX = snapshot.scaleX * transform.scale
            val scaleY = snapshot.scaleY * transform.scale
            val translationX = snapshot.translationX + transform.translationXPx
            val translationY = snapshot.translationY + transform.translationYPx
            if (actionBar.scaleX != scaleX) actionBar.scaleX = scaleX
            if (actionBar.scaleY != scaleY) actionBar.scaleY = scaleY
            if (actionBar.translationX != translationX) actionBar.translationX = translationX
            if (actionBar.translationY != translationY) actionBar.translationY = translationY
        }

        private fun captureActionBarTransform(actionBar: View): ActionBarTransformSnapshot {
            val parentLocation = IntArray(2)
            val actionBarLocation = IntArray(2)
            parent.getLocationOnScreen(parentLocation)
            actionBar.getLocationOnScreen(actionBarLocation)
            return ActionBarTransformSnapshot(
                originalPivotX = actionBar.pivotX,
                originalPivotY = actionBar.pivotY,
                transformPivotX = parentLocation[0] + parent.width / 2f - actionBarLocation[0],
                transformPivotY = parentLocation[1] + parent.height / 2f - actionBarLocation[1],
                scaleX = actionBar.scaleX,
                scaleY = actionBar.scaleY,
                translationX = actionBar.translationX,
                translationY = actionBar.translationY,
            )
        }

        private fun restoreActionBarTransform() {
            val snapshot = actionBarTransformSnapshot ?: return
            val actionBar = actionBarContainer ?: return
            actionBar.pivotX = snapshot.originalPivotX
            actionBar.pivotY = snapshot.originalPivotY
            actionBar.scaleX = snapshot.scaleX
            actionBar.scaleY = snapshot.scaleY
            actionBar.translationX = snapshot.translationX
            actionBar.translationY = snapshot.translationY
            actionBarTransformSnapshot = null
            WeLogger.i(TAG, "restored in-place ActionBarContainer transform")
        }

        private fun moveFabHostIntoContentWrapper(host: View) {
            val currentParent = host.parent as? ViewGroup ?: return
            if (currentParent === contentWrapper) {
                fabHostView = host
                return
            }
            fabOriginalParent = currentParent
            fabOriginalLayoutParams = host.layoutParams
            fabOriginalIndex = currentParent.indexOfChild(host)
            currentParent.removeView(host)
            contentWrapper.addView(
                host,
                fabOriginalLayoutParams ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            fabHostView = host
            WeLogger.i(TAG, "moved AddMainScreenFab host into side-panel content wrapper")
        }

        private fun restoreFabHostToOriginalParent() {
            val host = fabHostView ?: return
            val originalParent = fabOriginalParent
            if (host.parent === contentWrapper) {
                contentWrapper.removeView(host)
            }
            if (originalParent != null && host.parent !== originalParent) {
                val index = fabOriginalIndex.coerceIn(0, originalParent.childCount)
                originalParent.addView(
                    host,
                    index,
                    fabOriginalLayoutParams ?: FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            fabHostView = null
            fabOriginalParent = null
            fabOriginalLayoutParams = null
            fabOriginalIndex = -1
        }

        private fun moveExistingChildrenIntoWrapper() {
            val children = buildList {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child !== contentWrapper) {
                        add(child to child.layoutParams)
                    }
                }
            }
            children.forEach { (child, params) ->
                parent.removeView(child)
                contentWrapper.addView(child, params)
            }
        }

        private fun absorbStrayChildren() {
            var index = 0
            while (index < parent.childCount) {
                val child = parent.getChildAt(index)
                if (child === contentWrapper) {
                    index++
                } else {
                    val params = child.layoutParams
                    parent.removeViewAt(index)
                    contentWrapper.addView(child, params)
                }
            }
            if (decorRoot.indexOfChild(overlayRoot) != decorRoot.childCount - 1) {
                overlayRoot.bringToFront()
            }
        }

        private fun restoreContent() {
            while (contentWrapper.isNotEmpty()) {
                val child = contentWrapper.getChildAt(0)
                val params = child.layoutParams
                contentWrapper.removeViewAt(0)
                parent.addView(child, params)
            }
            contentWrapper.scaleX = 1f
            contentWrapper.scaleY = 1f
            contentWrapper.translationX = 0f
            contentWrapper.translationY = 0f
        }

        private fun isInsidePanel(x: Float): Boolean =
            x <= drawerWidthPx
    }

    private class HomeSidePanelOverlayLayout(context: Context) : FrameLayout(context) {
        var session: HomeSidePanelSession? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
            session?.onOverlayInterceptTouch(ev) == true || super.onInterceptTouchEvent(ev)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean =
            session?.onOverlayTouch(event) == true || super.onTouchEvent(event)
    }

    private class ProgressOutlineProvider : ViewOutlineProvider() {
        var radiusPx: Float = 0f

        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    private const val HOME_TAB_INDEX = 0
    private const val DRAWER_WIDTH_FRACTION = 0.84f
    private const val DIM_MAX_ALPHA = 0.52f
    private const val CLOSED_EPSILON = 0.001f
    private const val PAGE_SETTLED_EPSILON = 0.001f
}

@Composable
private fun HomeSidePanelContent() {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        color = Color(0xFFF4F7F4),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, top = 52.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileHeader()
            TimeCard()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(symbol = "◎", label = "扫一扫", modifier = Modifier.weight(1f))
                QuickTile(symbol = "▣", label = "收付款", modifier = Modifier.weight(1f))
                QuickTile(symbol = "◇", label = "收藏", modifier = Modifier.weight(1f))
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.86f),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 1.dp,
            ) {
                Column {
                    MenuRow(symbol = "◉", label = "朋友圈")
                    MenuRow(symbol = "▥", label = "视频号")
                    MenuRow(symbol = "◌", label = "清空未读")
                    MenuRow(symbol = "⚙", label = "侧滑面板设置", showDivider = false)
                }
            }
            Surface(
                color = Color(0xFF15231B),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    text = "当前只是内容壳：验证滑动层级、缩放和 dim，实际入口与数据以后再补。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    color = Color(0xFFDCE5DE),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF102017)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "微",
                color = Color(0xFFA8F4C7),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }
        Column {
            Text(
                text = "微信用户",
                color = Color(0xFF162018),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                text = "WeKit 侧滑面板 · Compose 壳",
                color = Color(0xFF758178),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TimeCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF14241A), Color(0xFF1E3E29)),
                    )
                )
                .padding(18.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "09:41",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 38.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "8月4日 周二",
                        color = Color(0xFFB8C7BC),
                        fontSize = 11.sp,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "上午好，今天也要写漂亮的 Hook。",
                    color = Color.White,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "多云 27°C · 体感 29°C",
                    color = Color(0xFFA9B8AD),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun QuickTile(
    symbol: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(66.dp),
        color = Color.White.copy(alpha = 0.86f),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = symbol, color = Color(0xFF25352A), fontSize = 20.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, color = Color(0xFF68756C), fontSize = 11.sp)
        }
    }
}

@Composable
private fun MenuRow(
    symbol: String,
    label: String,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF5F0)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = symbol, color = Color(0xFF2C3B31), fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = Color(0xFF2F3D34),
                fontSize = 14.sp,
            )
            Text(text = "›", color = Color(0xFFB4BBB6), fontSize = 20.sp)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = 56.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            )
        }
    }
}
