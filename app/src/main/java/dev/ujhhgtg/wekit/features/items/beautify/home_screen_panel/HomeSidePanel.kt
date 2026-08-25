package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.base.CustomViewPager
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.beautify.AddMainScreenFab
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.int
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private data class HomeSidePanelVisualTransform(
    val easedProgress: Float,
    val scale: Float,
    val translationXPx: Float,
    val translationYPx: Float,
)

private fun homeSidePanelVisualTransform(
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

private fun homeSidePanelShouldReparentExternalChrome(
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
object HomeSidePanel : SwitchFeature(), IResolveDex {

    override val technicalId = "主页侧滑面板"
    override val nameRes = R.string.feature_home_side_panel_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_home_side_panel_description

    private val classWalletCache by dexClass {
        matcher {
            className = "com.tencent.mm.wallet_core.model.m1"
        }
    }

    private val classWalletPayPlugin by dexClass {
        matcher {
            className = "com.tencent.mm.plugin.wxpay.g"
            usingEqStrings("MicroMsg.PluginWxPay")
            addMethod {
                returnType(classWalletCache.data.name)
            }
        }
    }

    private val methodWalletCacheRead by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.wallet_core.model.m1"
            name = "i"
            paramCount = 2
            returnType = "java.lang.Object"
        }
    }

    private val methodWalletCacheWrite by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.wallet_core.model.m1"
            name = "j"
            paramCount = 2
            returnType = "void"
        }
    }

    private const val TAG = "HomeSidePanel"
    private const val LAUNCHER_BOTTOM_TAB_VIEW_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"
    private val sessions = WeakHashMap<WxViewPager, WeakReference<HomeSidePanelSession>>()
    private val pendingEdgeToEdgeAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val dispatchTouchEventMethod by lazy {
        CustomViewPager::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
    }
    private val pendingHostCancel = ThreadLocal<PendingHostCancel?>()

    override fun onEnable() {
        HomeSidePanelWalletBalanceSource.install {
            readHomeSidePanelWalletBalance(
                walletCacheReadMethod = methodWalletCacheRead.method,
                walletPayPluginClass = classWalletPayPlugin.clazz,
            )
        }
        methodWalletCacheWrite.hookAfter {
            HomeSidePanelWalletBalanceSource.onCacheWrite(args[0], args[1])
        }
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
            name = "moveTaskToBack"
            parameters(Boolean::class)
        }.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            val session = sessions.values.mapNotNull { it.get() }.firstOrNull { it.ownsActivity(activity) }
                ?: return@hookBefore
            if (session.consumeMoveTaskToBack()) {
                result = true
            }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onResume"
            parameters()
        }.hookAfter {
            val activity = thisObject as Activity
            sessions.values.mapNotNull { it.get() }.firstOrNull { it.ownsActivity(activity) }
                ?.onLauncherResumed()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "startChatting"
            parameters(String::class, Bundle::class, Boolean::class)
        }.hookAfter {
            val activity = thisObject as Activity
            sessions.values.mapNotNull { it.get() }
                .firstOrNull { it.ownsActivity(activity) }
                ?.onChatTransition()
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "closeChatting"
            parameters(Boolean::class)
        }.hookAfter {
            val activity = thisObject as Activity
            sessions.values.mapNotNull { it.get() }
                .firstOrNull { it.ownsActivity(activity) }
                ?.onChatTransition()
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

            val session = HomeSidePanelSession(activity, parent, viewPager, tabsAdapter).also { it.attach() }
            session.setSelectedTab(viewPager.currentItem)
            sessions[viewPager] = WeakReference(session)
        }
    }

    override fun onDisable() {
        sessions.values.mapNotNull { it.get() }.forEach { it.detach() }
        sessions.clear()
        HomeSidePanelWalletBalanceSource.clear()
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
        if (decor.isAttachedToWindow) {
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

    private data class ToolbarProfileBinding(
        val host: RelativeLayout,
        val nativeTitle: TextView,
        val composeView: ComposeView,
    )

    private data class ObservedViewListeners(
        val attach: View.OnAttachStateChangeListener,
        val layout: View.OnLayoutChangeListener,
    )

    private enum class PagerTouchResult {
        PASS,
        CANCEL_HOST,
        CONSUME,
    }

    private class HomeSidePanelSession(
        private val activity: Activity,
        private val parent: FrameLayout,
        private val viewPager: WxViewPager,
        private val tabsAdapter: Any,
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
        private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val layoutStore = HomeSidePanelLayoutStore
        private val cityIndex = HomeSidePanelCityIndex(activity)
        private val weather = HomeSidePanelWeather(
            cityIndex = cityIndex,
            client = homeSidePanelHttpClient,
        )
        private val hitokoto = HomeSidePanelHitokoto(homeSidePanelHttpClient)
        private val runtimeStore = HomeSidePanelRuntimeStore(
            weather = weather,
            hitokoto = hitokoto,
            walletBalance = HomeSidePanelWalletBalanceSource,
            parentScope = stateScope,
            cacheStore = layoutStore,
        )
        private val panelState = HomeSidePanelState(
            activity = activity,
            profile = HomeSidePanelProfileLoader(
                cityIndex = cityIndex,
            ),
            weather = weather,
            hitokoto = hitokoto,
            runtimeStore = runtimeStore,
            location = HomeSidePanelLocation(cityIndex),
            scope = stateScope,
            layoutStore = layoutStore,
            closePanel = { afterClosed ->
                close(animated = true, afterClosed = afterClosed)
            },
        )
        private val outlineProvider = ProgressOutlineProvider()

        private var animator: ValueAnimator? = null
        private var drawerWidthPx = 1
        private var renderedProgress = 0f
        private var dragging = false
        private var attached = false
        private var pendingSyncFlags = 0
        private var syncPosted = false
        private val parentLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            requestSync(SYNC_ALL)
        }
        private val decorLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            requestSync(SYNC_HIERARCHY or SYNC_GEOMETRY or SYNC_INSETS)
        }
        private val syncRunnable = Runnable {
            syncPosted = false
            if (!attached) {
                pendingSyncFlags = 0
                return@Runnable
            }
            runPendingSync()
        }
        private var parentClipChildren = true
        private var parentClipToPadding = true
        private val actionBarContainers = linkedSetOf<View>()
        private val actionBarTransformSnapshots = linkedMapOf<View, ActionBarTransformSnapshot>()
        private var fabHostView: View? = null
        private var fabOriginalParent: ViewGroup? = null
        private var fabOriginalLayoutParams: ViewGroup.LayoutParams? = null
        private var fabOriginalIndex = -1
        private var wasPanelVisible = false
        private var suppressCloseUntilNextFrame = false
        private var selectedTabIndex = HOME_TAB_INDEX
        private var chattingVisible = false
        private var cachedNativeBottomTabView: View? = null
        private var observedNativeBottomTabView: View? = null
        private var observedNativeBottomLinearLayout: LinearLayout? = null
        private var nativeBottomTabMissingLogged = false
        private var lastNativeContentInsetLogState: String? = null
        private val toolbarProfileBindings = linkedMapOf<RelativeLayout, ToolbarProfileBinding>()
        private val observedToolbarProfileHosts = linkedSetOf<RelativeLayout>()
        private val homeToolbarHosts = linkedSetOf<RelativeLayout>()
        private val chattingToolbarHosts = linkedSetOf<RelativeLayout>()
        private val nativeTitleVisibilities = linkedMapOf<TextView, Int>()
        private val tabsAdapterHookHandles = mutableListOf<HookHandle>()
        private val observedViews = WeakHashMap<View, ObservedViewListeners>()
        private var pendingTransitionLayoutListener: View.OnLayoutChangeListener? = null

        fun attach() {
            if (attached) return
            attached = true

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
                    LaunchedEffect(panelState) {
                        val messagesJob = launch(start = CoroutineStart.UNDISPATCHED) {
                            panelState.messages.collect { message ->
                                showToast(activity, message)
                            }
                        }
                        panelState.start()
                        messagesJob.join()
                    }
                    val state by panelState.uiState.collectAsStateWithLifecycle()
                    HomeSidePanelContent(state, panelState)
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
            parent.addOnLayoutChangeListener(parentLayoutListener)
            decorRoot.addOnLayoutChangeListener(decorLayoutListener)
            ViewCompat.setOnApplyWindowInsetsListener(contentWrapper) { _, insets ->
                requestSync(SYNC_INSETS)
                insets
            }
            stateScope.launch {
                panelState.uiState
                    .map { it.showToolbarProfile to it.hideWeChatTitle }
                    .distinctUntilChanged()
                    .collect { syncToolbarProfileVisibility() }
            }
            installTabsAdapterHooks()
            requestSync(SYNC_ALL)
        }

        private fun requestSync(flags: Int) {
            if (!attached) return
            pendingSyncFlags = pendingSyncFlags or flags
            if (syncPosted) return
            syncPosted = true
            parent.post(syncRunnable)
        }

        private fun runPendingSync() {
            val flags = pendingSyncFlags
            pendingSyncFlags = 0
            if (flags and SYNC_HIERARCHY != 0) {
                absorbStrayChildren()
                resolveExternalChrome()
                syncToolbarProfileBindings()
            }
            if (flags and SYNC_GEOMETRY != 0) {
                if (updateDrawerWidth()) {
                    applyProgress(renderedProgress)
                }
            }
            if (flags and SYNC_INSETS != 0) {
                syncNativeContentBottomInset()
            }
            if (flags and (SYNC_HIERARCHY or SYNC_GEOMETRY) != 0) {
                applyActionBarProgress(renderedProgress)
            }
        }

        private fun syncNativeContentBottomInset() {
            val bottomBar = cachedNativeBottomTabView
                ?.takeIf { it.isAttachedToWindow }
                ?: contentWrapper.findViewWhich {
                    it.javaClass.name == LAUNCHER_BOTTOM_TAB_VIEW_CLASS
                }?.also { cachedNativeBottomTabView = it }
            val bottomLinearLayout = (bottomBar as? ViewGroup)?.directLinearLayout()
            if (observedNativeBottomTabView !== bottomBar) {
                observedNativeBottomTabView?.let(::unobserveView)
                observedNativeBottomTabView = bottomBar
            }
            if (observedNativeBottomLinearLayout !== bottomLinearLayout) {
                observedNativeBottomLinearLayout?.let(::unobserveView)
                observedNativeBottomLinearLayout = bottomLinearLayout
            }
            if (bottomBar == null) {
                if (!nativeBottomTabMissingLogged) {
                    WeLogger.w(TAG, "native bottom tab not found; launcher content inset pending")
                    nativeBottomTabMissingLogged = true
                }
                return
            }
            observeView(bottomBar, SYNC_INSETS)
            bottomLinearLayout?.let { observeView(it, SYNC_INSETS) }
            nativeBottomTabMissingLogged = false

            val replacementOwnsInsets =
                bottomBar.visibility != View.VISIBLE || bottomBar.findViewWhich { it is ComposeView } != null
            val insets = ViewCompat.getRootWindowInsets(contentWrapper)
            val navigationBottom = insets
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())
                ?.bottom
                ?: 0
            val tappableBottom = insets
                ?.getInsets(WindowInsetsCompat.Type.tappableElement())
                ?.bottom
                ?: 0
            val ancestorBottomInset = contentBottomGapToDecor()
            val nativeBottomInset = bottomBar.paddingBottom +
                (bottomLinearLayout?.paddingBottom ?: 0)
            val alreadyAvoidedBottom = ancestorBottomInset + nativeBottomInset
            val targetBottom = if (chattingVisible || replacementOwnsInsets) {
                0
            } else {
                (navigationBottom - alreadyAvoidedBottom).coerceAtLeast(0)
            }

            if (contentWrapper.paddingBottom != targetBottom) {
                contentWrapper.setPadding(
                    contentWrapper.paddingLeft,
                    contentWrapper.paddingTop,
                    contentWrapper.paddingRight,
                    targetBottom,
                )
            }
            val logState = "$targetBottom/$navigationBottom/$tappableBottom/$ancestorBottomInset/" +
                "$nativeBottomInset/$chattingVisible/$replacementOwnsInsets/${bottomBar.visibility}"
            if (lastNativeContentInsetLogState != logState) {
                WeLogger.d(
                    TAG,
                    "native content inset target=$targetBottom " +
                        "(navigation=$navigationBottom tappable=$tappableBottom " +
                        "ancestor=$ancestorBottomInset native=$nativeBottomInset " +
                        "chatting=$chattingVisible replacement=$replacementOwnsInsets " +
                        "wrapper=${contentWrapper.height}/${contentWrapper.paddingBottom} " +
                        "bottomBar=${bottomBar.height}/${bottomBar.top}/${bottomBar.bottom}/" +
                        "${bottomBar.paddingBottom}/${bottomBar.visibility} sysUi=0x" +
                        activity.window.decorView.systemUiVisibility.toString(16) + ")"
                )
                lastNativeContentInsetLogState = logState
            }
        }

        /**
         * contentWrapper 自己的 padding 不改变它的边界，因此这里得到的是微信祖先布局已经
         * 留出的底部空间。使用布局坐标而不是屏幕坐标，避免侧栏动画的 scale/translation
         * 被误算成系统栏补偿。
         */
        private fun contentBottomGapToDecor(): Int {
            var bottom = contentWrapper.bottom
            var ancestor = contentWrapper.parent as View
            while (ancestor !== decorRoot) {
                bottom += ancestor.top
                ancestor = ancestor.parent as View
            }
            return (decorRoot.height - bottom).coerceAtLeast(0)
        }

        /**
         * 8.0.65-8.0.69 把导航栏高度加在 LauncherUIBottomTabView 的直接 LinearLayout
         * 子项上；8.0.74+ 的新 Edge2EdgeHelper 则可能直接加在底栏本身。两者都要计入
         * 已有补偿，wrapper 只补剩余差值。
         */
        private fun ViewGroup.directLinearLayout(): LinearLayout? {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child is LinearLayout) return child
            }
            return null
        }

        fun detach() {
            if (!attached) return
            attached = false
            parent.removeCallbacks(syncRunnable)
            parent.removeOnLayoutChangeListener(parentLayoutListener)
            decorRoot.removeOnLayoutChangeListener(decorLayoutListener)
            ViewCompat.setOnApplyWindowInsetsListener(contentWrapper, null)
            pendingTransitionLayoutListener?.let(parent::removeOnLayoutChangeListener)
            pendingTransitionLayoutListener = null
            observedViews.forEach { (view, listeners) ->
                view.removeOnAttachStateChangeListener(listeners.attach)
                view.removeOnLayoutChangeListener(listeners.layout)
            }
            observedViews.clear()
            observedToolbarProfileHosts.clear()
            animator?.cancel()
            animator = null
            tabsAdapterHookHandles.forEach { it.unhook() }
            tabsAdapterHookHandles.clear()
            panelState.close()
            pendingSyncFlags = 0
            syncPosted = false
            clearToolbarProfileBindings()
            restoreActionBarTransform()
            restoreFabHostToOriginalParent()
            contentWrapper.setPadding(
                contentWrapper.paddingLeft,
                contentWrapper.paddingTop,
                contentWrapper.paddingRight,
                0,
            )
            cachedNativeBottomTabView = null
            observedNativeBottomTabView = null
            observedNativeBottomLinearLayout = null
            nativeBottomTabMissingLogged = false
            lastNativeContentInsetLogState = null
            restoreContent()
            panelView.disposeComposition()
            decorRoot.removeView(overlayRoot)
            contentWrapper.clipToOutline = false
            parent.removeView(contentWrapper)
            parent.clipChildren = parentClipChildren
            parent.clipToPadding = parentClipToPadding
        }

        fun setSelectedTab(index: Int) {
            val previousTabIndex = selectedTabIndex
            selectedTabIndex = index
            gesture.setSelectedTab(index)
            syncToolbarProfileVisibility()
            if (index != previousTabIndex) {
                requestSync(SYNC_HIERARCHY or SYNC_INSETS)
            }
            if (index == HOME_TAB_INDEX && previousTabIndex != HOME_TAB_INDEX) {
                panelState.onPanelOpened()
            }
            if (index != HOME_TAB_INDEX && renderedProgress > CLOSED_EPSILON) {
                close(animated = true)
            }
        }

        private fun installTabsAdapterHooks() {
            val reflectedTabsAdapter = tabsAdapter.reflekt()
            tabsAdapterHookHandles += reflectedTabsAdapter.firstMethod {
                name = "onPageSelected"
                parameters(int)
            }.hookBeforeDirectly {
                if (tabsAdapter !== thisObject) return@hookBeforeDirectly
                setSelectedTab(args[0] as Int)
            }
            tabsAdapterHookHandles += reflectedTabsAdapter.firstMethod {
                name = "onPageSelected"
                parameters(int)
            }.hookAfterDirectly {
                if (tabsAdapter !== thisObject) return@hookAfterDirectly
                val position = args[0] as Int
                if (position == HOME_TAB_INDEX) {
                    ensureLauncherEdgeToEdge(activity)
                }
            }
            tabsAdapterHookHandles += reflectedTabsAdapter.firstMethod {
                name = "onPageScrolled"
                parameters(int, float, int)
            }.hookBeforeDirectly {
                if (tabsAdapter !== thisObject) return@hookBeforeDirectly
                val position = args[0] as Int
                val offset = args[1] as Float
                if (position != HOME_TAB_INDEX || offset > PAGE_SETTLED_EPSILON) {
                    setSelectedTab(-1)
                } else {
                    setSelectedTab(viewPager.currentItem)
                }
            }
        }

        fun ownsActivity(candidate: Activity): Boolean = activity === candidate

        fun onLauncherResumed() {
            panelState.onLauncherResumed()
            requestSync(SYNC_ALL)
        }

        fun onChatTransition() {
            if (!attached) return
            requestSync(SYNC_HIERARCHY or SYNC_INSETS)
            pendingTransitionLayoutListener?.let(parent::removeOnLayoutChangeListener)
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    parent.removeOnLayoutChangeListener(this)
                    if (pendingTransitionLayoutListener === this) {
                        pendingTransitionLayoutListener = null
                    }
                    requestSync(SYNC_ALL)
                }
            }
            pendingTransitionLayoutListener = listener
            parent.addOnLayoutChangeListener(listener)
        }

        fun open() {
            if (selectedTabIndex != HOME_TAB_INDEX || isChattingVisible()) return
            val from = renderedProgress
            animator?.cancel()
            animator = null
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            gesture.close()
            gesture.snapTo(from)
            animateTo(1f, from)
        }

        fun consumeMoveTaskToBack(): Boolean {
            if (suppressCloseUntilNextFrame) return true
            if (panelState.consumeSettingsBack()) {
                suppressCloseUntilNextFrame = true
                decorRoot.postOnAnimation {
                    suppressCloseUntilNextFrame = false
                }
                return true
            }
            if (renderedProgress <= 0f && !dragging && !gesture.isTracking) {
                return false
            }
            close(animated = true)
            return true
        }

        fun close(
            animated: Boolean,
            afterClosed: (() -> Unit)? = null,
        ) {
            if (suppressCloseUntilNextFrame) return
            val from = renderedProgress
            animator?.cancel()
            animator = null
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            gesture.close()
            if (animated) {
                animateTo(0f, from, afterClosed)
            } else {
                applyProgress(0f)
                afterClosed?.invoke()
            }
        }

        fun onPagerTouch(event: MotionEvent): PagerTouchResult {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
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
            requestSync(SYNC_HIERARCHY or SYNC_GEOMETRY)
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

        private fun animateTo(
            target: Float,
            from: Float = renderedProgress,
            afterClosed: (() -> Unit)? = null,
        ) {
            requestSync(SYNC_HIERARCHY or SYNC_GEOMETRY)
            animator?.cancel()
            animator = null
            if (kotlin.math.abs(from - target) < 0.001f) {
                gesture.snapTo(target)
                applyProgress(target)
                afterClosed?.invoke()
                return
            }
            overlayRoot.visibility = View.VISIBLE
            var canceled = false
            animator = ValueAnimator.ofFloat(from, target).apply {
                duration = (120L + 120L * kotlin.math.abs(from - target)).roundToInt().toLong()
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
                        afterClosed?.invoke()
                    }
                })
                start()
            }
        }

        private fun resolveExternalChrome() {
            val actionBarCandidates = linkedSetOf<View>()
            collectViews(decorRoot, actionBarCandidates) {
                it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
            }
            val staleActionBars = actionBarContainers.filter { it !in actionBarCandidates }
            staleActionBars.forEach { actionBar ->
                unobserveView(actionBar)
                restoreActionBarTransform(actionBar)
                actionBarContainers.remove(actionBar)
            }
            actionBarCandidates.forEach { observeView(it, SYNC_GEOMETRY) }
            actionBarContainers += actionBarCandidates
            val fabCandidate = AddMainScreenFab.hostViewFor(activity)?.takeIf { it.parent != null }
            if (fabHostView !== fabCandidate) {
                fabHostView?.let(::unobserveView)
                if (fabHostView?.parent == null && fabCandidate == null) {
                    fabHostView = null
                    fabOriginalParent = null
                    fabOriginalLayoutParams = null
                    fabOriginalIndex = -1
                } else {
                    restoreFabHostToOriginalParent()
                }
            }
            if (fabCandidate != null) {
                observeView(fabCandidate, SYNC_HIERARCHY or SYNC_GEOMETRY)
            }
            if (fabCandidate != null && fabHostView !== fabCandidate) {
                moveFabHostIntoContentWrapper(fabCandidate)
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

        private fun syncToolbarProfileBindings() {
            chattingVisible = isChattingVisible()
            val hosts = linkedMapOf<RelativeLayout, TextView>()
            collectToolbarProfileHosts(decorRoot, hosts)
            val staleObservedHosts = observedToolbarProfileHosts.filter { it !in hosts }
            staleObservedHosts.forEach { host ->
                unobserveView(host)
                observedToolbarProfileHosts.remove(host)
            }
            hosts.keys.forEach { host ->
                observeView(host, SYNC_HIERARCHY)
                observedToolbarProfileHosts += host
            }
            chattingToolbarHosts.removeAll { it.parent == null }
            if (chattingVisible) {
                chattingToolbarHosts += hosts.keys.filterNot { it in toolbarProfileBindings }
            }

            val iterator = toolbarProfileBindings.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in hosts || entry.value.composeView.parent !== entry.key) {
                    if (entry.key !in hosts) unobserveView(entry.key)
                    disposeToolbarProfileBinding(entry.value)
                    iterator.remove()
                }
            }

            hosts.forEach { (host, nativeTitle) ->
                if (
                    !chattingVisible &&
                    (host in homeToolbarHosts || host !in chattingToolbarHosts) &&
                    host !in toolbarProfileBindings
                ) {
                    homeToolbarHosts += host
                    toolbarProfileBindings[host] = createToolbarProfileBinding(host, nativeTitle)
                }
            }
            syncToolbarProfileVisibility()
            if (chattingVisible && renderedProgress > CLOSED_EPSILON) {
                close(animated = true)
            }
        }

        private fun isChattingVisible(): Boolean =
            (activity as LauncherUI).currentFragmet != null

        private fun collectToolbarProfileHosts(
            view: View,
            destination: MutableMap<RelativeLayout, TextView>,
        ) {
            val titles = mutableListOf<View>()
            collectViews(view, titles) { it is TextView && it.id == android.R.id.text1 }
            titles.forEach { titleView ->
                val title = titleView as TextView
                var customRoot: View = title
                var ancestor = title.parent
                while (ancestor is ViewGroup) {
                    if (ancestor.isToolbar()) {
                        destination[customRoot as RelativeLayout] = title
                        break
                    }
                    customRoot = ancestor
                    ancestor = ancestor.parent
                }
            }
        }

        private fun View.isToolbar(): Boolean {
            var type: Class<*>? = javaClass
            while (type != null) {
                if (type.name == "androidx.appcompat.widget.Toolbar") return true
                type = type.superclass
            }
            return false
        }

        private fun collectViews(
            view: View,
            destination: MutableCollection<View>,
            predicate: (View) -> Boolean,
        ) {
            if (predicate(view)) destination += view
            if (view !is ViewGroup) return
            for (index in 0 until view.childCount) {
                collectViews(view.getChildAt(index), destination, predicate)
            }
        }

        private fun observeView(view: View, flags: Int) {
            if (observedViews.containsKey(view)) return
            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    requestSync(flags)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    unobserveView(view)
                    requestSync(SYNC_HIERARCHY or SYNC_INSETS)
                }
            }
            val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                requestSync(flags)
            }
            view.addOnAttachStateChangeListener(attachListener)
            view.addOnLayoutChangeListener(layoutListener)
            observedViews[view] = ObservedViewListeners(attachListener, layoutListener)
        }

        private fun unobserveView(view: View) {
            val listeners = observedViews.remove(view) ?: return
            view.removeOnAttachStateChangeListener(listeners.attach)
            view.removeOnLayoutChangeListener(listeners.layout)
        }

        private fun createToolbarProfileBinding(
            host: RelativeLayout,
            nativeTitle: TextView,
        ): ToolbarProfileBinding {
            val composeView = ComposeView(activity).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity))
                setContent {
                    InjectedUiTheme {
                        val state by panelState.uiState.collectAsStateWithLifecycle()
                        HomeSidePanelToolbarContent(
                            profile = state.profile,
                            onAvatarClick = ::open,
                            onStatusClick = panelState::openStatusEditorFromToolbar,
                        )
                    }
                }
            }
            host.addView(
                composeView,
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                },
            )
            return ToolbarProfileBinding(host, nativeTitle, composeView)
        }

        private fun syncToolbarProfileVisibility() {
            val state = panelState.uiState.value
            val showProfile = state.showToolbarProfile && selectedTabIndex == HOME_TAB_INDEX
            toolbarProfileBindings.values.forEach { binding ->
                binding.composeView.visibility = if (showProfile) View.VISIBLE else View.GONE
            }
            syncNativeTitleVisibility(
                bindings = toolbarProfileBindings.values,
                hide = showProfile && state.hideWeChatTitle,
            )
        }

        private fun syncNativeTitleVisibility(
            bindings: Collection<ToolbarProfileBinding>,
            hide: Boolean,
        ) {
            val currentTitles = bindings.mapTo(linkedSetOf()) { it.nativeTitle }
            val iterator = nativeTitleVisibilities.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!hide || entry.key !in currentTitles) {
                    entry.key.visibility = entry.value
                    iterator.remove()
                }
            }
            if (!hide) return
            currentTitles.forEach { title ->
                nativeTitleVisibilities.putIfAbsent(title, title.visibility)
                title.visibility = View.GONE
            }
        }

        private fun clearToolbarProfileBindings() {
            nativeTitleVisibilities.forEach { (title, visibility) ->
                title.visibility = visibility
            }
            nativeTitleVisibilities.clear()
            toolbarProfileBindings.values.forEach(::disposeToolbarProfileBinding)
            toolbarProfileBindings.clear()
        }

        private fun disposeToolbarProfileBinding(binding: ToolbarProfileBinding) {
            if (binding.composeView.parent === binding.host) {
                binding.host.removeView(binding.composeView)
            }
            binding.composeView.disposeComposition()
        }

        private fun applyProgress(progress: Float) {
            val p = progress.coerceIn(0f, 1f)
            val becameVisible = !wasPanelVisible && p > CLOSED_EPSILON
            val becameHidden = wasPanelVisible && p <= CLOSED_EPSILON
            renderedProgress = p
            if (becameVisible) panelState.onPanelOpened()
            if (becameHidden) panelState.onPanelClosed()
            wasPanelVisible = p > CLOSED_EPSILON

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

        private fun updateDrawerWidth(): Boolean {
            val width = parent.width
            if (width <= 0) return false
            val nextWidth = (width * DRAWER_WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
            if (nextWidth == drawerWidthPx) return false
            drawerWidthPx = nextWidth
            val params = panelView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(drawerWidthPx, FrameLayout.LayoutParams.MATCH_PARENT)
            params.width = drawerWidthPx
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            panelView.layoutParams = params
            return true
        }

        private fun applyActionBarProgress(
            progress: Float,
            transform: HomeSidePanelVisualTransform = homeSidePanelVisualTransform(
                progress = progress,
                density = activity.resources.displayMetrics.density,
            ),
        ) {
            if (progress <= CLOSED_EPSILON) {
                restoreActionBarTransform()
                return
            }
            actionBarContainers.forEach { actionBar ->
                val snapshot = actionBarTransformSnapshots.getOrPut(actionBar) {
                    captureActionBarTransform(actionBar)
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
            actionBarTransformSnapshots.forEach { (actionBar, snapshot) ->
                restoreActionBarTransform(actionBar, snapshot)
            }
            actionBarTransformSnapshots.clear()
        }

        private fun restoreActionBarTransform(actionBar: View) {
            val snapshot = actionBarTransformSnapshots.remove(actionBar) ?: return
            restoreActionBarTransform(actionBar, snapshot)
        }

        private fun restoreActionBarTransform(
            actionBar: View,
            snapshot: ActionBarTransformSnapshot,
        ) {
            actionBar.pivotX = snapshot.originalPivotX
            actionBar.pivotY = snapshot.originalPivotY
            actionBar.scaleX = snapshot.scaleX
            actionBar.scaleY = snapshot.scaleY
            actionBar.translationX = snapshot.translationX
            actionBar.translationY = snapshot.translationY
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
    private const val SYNC_HIERARCHY = 1
    private const val SYNC_GEOMETRY = 1 shl 1
    private const val SYNC_INSETS = 1 shl 2
    private const val SYNC_ALL = SYNC_HIERARCHY or SYNC_GEOMETRY or SYNC_INSETS
    private const val DRAWER_WIDTH_FRACTION = 0.84f
    private const val DIM_MAX_ALPHA = 0.52f
    private const val CLOSED_EPSILON = 0.001f
    private const val PAGE_SETTLED_EPSILON = 0.001f
}

private val homeSidePanelHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}
