package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.isNotEmpty
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.base.CustomViewPager
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.wekit.activity.settings.SettingsActivity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.items.beautify.AddMainScreenFab
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
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
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor
import java.lang.reflect.Modifier as ReflectModifier

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

internal fun homeSidePanelOwnsTabsAdapter(expected: Any, actual: Any?): Boolean = expected === actual

internal fun homeSidePanelShouldStartLocationDetection(actionInProgress: Boolean): Boolean = !actionInProgress

internal fun homeSidePanelShouldPublishWeatherSearch(currentQuery: String, resultQuery: String): Boolean =
    currentQuery == resultQuery

internal fun homeSidePanelOneShotCloseDuration(from: Float, target: Float): Long =
    (120L + 120L * kotlin.math.abs(from - target)).roundToInt().toLong()

internal fun homeSidePanelNormalCloseDuration(from: Float, target: Float): Long =
    (180L + 180L * kotlin.math.abs(from - target)).roundToInt().toLong()

internal fun homeSidePanelWeatherProfileStateBelongsToAccount(
    storedAccountId: String?,
    currentAccountId: String,
): Boolean = currentAccountId.isNotBlank() && storedAccountId == currentAccountId

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
object HomeSidePanel : SwitchFeature(), IResolveDex {

    private const val TAG = "HomeSidePanel"
    private val classTextStatusService by dexClass()
    private val classTextStatusRecord by dexClass()
    private val methodTextStatusStorageAccessor by dexMethod()
    private val methodLatestStatusByUsername by dexMethod()
    private val sessions = WeakHashMap<WxViewPager, WeakReference<HomeSidePanelSession>>()
    private val pendingEdgeToEdgeAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val dispatchTouchEventMethod by lazy {
        CustomViewPager::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
    }
    private val pendingHostCancel = ThreadLocal<PendingHostCancel?>()

    override fun resolveDex(dexKit: DexKitBridge) {
        classTextStatusRecord.find(dexKit) {
            matcher {
                addFieldForName("field_UserName")
                addFieldForName("field_StatusID")
                addFieldForName("field_IconID")
                addFieldForName("field_Description")
                addFieldForName("field_ExpireTime")
                addFieldForName("field_EmojiInfo")
            }
        }

        methodLatestStatusByUsername.find(dexKit) {
            matcher {
                paramTypes(String::class.java)
                usingEqStrings(
                    "MicroMsg.TextStatus.StatusInfoAffStorage",
                    "getLatestStatusByUserName: failed",
                )
            }
        }

        val latestStatusMethod = dexKit.getMethodData(
            methodLatestStatusByUsername.getDescriptorString()!!,
        )!!
        val storageInterface = latestStatusMethod.declaredClass!!.interfaces.single { candidate ->
            candidate.methods.any { method ->
                method.methodName == latestStatusMethod.methodName &&
                    method.paramTypeNames == latestStatusMethod.paramTypeNames &&
                    method.returnTypeName == latestStatusMethod.returnTypeName
            }
        }
        val storageAccessor = dexKit.findMethod {
            matcher {
                paramTypes()
                returnType(storageInterface.name)
            }
        }.filter { candidate ->
            candidate.declaredClass!!.fields.any { field ->
                field.typeName == candidate.declaredClassName &&
                    ReflectModifier.isStatic(field.modifiers)
            }
        }.single()

        classTextStatusService.setDescriptor(storageAccessor.declaredClass!!)
        methodTextStatusStorageAccessor.setDescriptor(storageAccessor)
    }

    internal fun createTextStatusReader(): HomeSidePanelTextStatusReader =
        HomeSidePanelTextStatusApi(
            serviceClass = classTextStatusService.clazz,
            storageAccessor = methodTextStatusStorageAccessor.method,
            latestStatusMethod = methodLatestStatusByUsername.method,
            recordClass = classTextStatusRecord.clazz,
        )

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
                result = true
            }
        }
        LauncherUI::class.reflekt().firstMethod {
            name = "onResume"
            parameters()
        }.hookAfter {
            val activity = thisObject as Activity
            sessions.values.mapNotNull { it.get() }.firstOrNull { it.ownsActivity(activity) }
                ?.resumePendingLocationDetection()
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
        private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val cityIndex = AssetHomeSidePanelCityIndex(activity)
        private val controller = HomeSidePanelController(
            profileRepository = HomeSidePanelProfileRepository(
                statusReader = createTextStatusReader(),
                cityIndex = cityIndex,
            ),
            weatherRepository = DefaultHomeSidePanelWeatherRepository(
                preferences = PersistedHomeSidePanelWeatherPreferences,
                cityIndex = cityIndex,
                client = homeSidePanelHttpClient,
            ),
            hitokotoRepository = DefaultHomeSidePanelHitokotoRepository(
                preferences = PersistedHomeSidePanelHitokotoPreferences,
                client = homeSidePanelHttpClient,
            ),
            locationResolver = AndroidHomeSidePanelLocationResolver(cityIndex),
            navigator = HomeSidePanelHostNavigator(
                activity = activity,
                closePanel = { afterClosed ->
                    close(animated = true, oneShot = true, afterClosed = afterClosed)
                },
                ioScope = controllerScope,
            ),
            scope = controllerScope,
        )
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
        private var wasPanelVisible = false
        private val tabsAdapterHookHandles = mutableListOf<HookHandle>()

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
                if (renderedProgress > CLOSED_EPSILON) close(animated = true, oneShot = true)
            }

            panelView.setBackgroundColor(AndroidColor.TRANSPARENT)
            panelView.isClickable = true
            panelView.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity))
            panelView.setContent {
                InjectedUiTheme {
                    LaunchedEffect(controller) {
                        val messagesJob = launch(start = CoroutineStart.UNDISPATCHED) {
                            controller.weatherMessages.collect { message ->
                                showToast(activity, message)
                            }
                        }
                        controller.startPreload()
                        messagesJob.join()
                    }
                    val state by controller.uiState.collectAsStateWithLifecycle()
                    HomeSidePanelContent(state, controller)
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
            installTabsAdapterHooks()
            parent.post {
                updateDrawerWidth()
                applyProgress(0f)
            }
        }

        fun detach() {
            if (!attached) return
            attached = false
            animator?.cancel()
            animator = null
            tabsAdapterHookHandles.forEach { it.unhook() }
            tabsAdapterHookHandles.clear()
            controller.close()
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

        private fun installTabsAdapterHooks() {
            val reflectedTabsAdapter = tabsAdapter.reflekt()
            tabsAdapterHookHandles += reflectedTabsAdapter.firstMethod {
                name = "onPageSelected"
                parameters(int)
            }.hookBeforeDirectly {
                if (!homeSidePanelOwnsTabsAdapter(tabsAdapter, thisObject)) return@hookBeforeDirectly
                setSelectedTab(args[0] as Int)
            }
            tabsAdapterHookHandles += reflectedTabsAdapter.firstMethod {
                name = "onPageSelected"
                parameters(int)
            }.hookAfterDirectly {
                if (!homeSidePanelOwnsTabsAdapter(tabsAdapter, thisObject)) return@hookAfterDirectly
                val position = args[0] as Int
                if (homeSidePanelShouldReapplyEdgeToEdgeAfterTabSelection(position)) {
                    ensureLauncherEdgeToEdge(activity)
                }
            }
            tabsAdapterHookHandles += reflectedTabsAdapter.firstMethod {
                name = "onPageScrolled"
                parameters(int, float, int)
            }.hookBeforeDirectly {
                if (!homeSidePanelOwnsTabsAdapter(tabsAdapter, thisObject)) return@hookBeforeDirectly
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

        fun resumePendingLocationDetection() {
            controller.resumePendingLocationDetection(activity)
        }

        fun consumeBack(): Boolean {
            if (controller.consumeSettingsBack()) return true
            if (!homeSidePanelShouldConsumeMoveTaskToBack(renderedProgress, dragging, gesture.isTracking)) {
                return false
            }
            close(animated = true)
            return true
        }

        fun close(
            animated: Boolean,
            oneShot: Boolean = false,
            afterClosed: (() -> Unit)? = null,
        ) {
            val from = renderedProgress
            animator?.cancel()
            animator = null
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            gesture.close()
            if (animated) {
                animateTo(0f, from, oneShot, afterClosed)
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
                        animateTo(target, from, oneShot = target == 0f)
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
                        animateTo(target, from, oneShot = target == 0f)
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
                    animateTo(target, from, oneShot = target == 0f)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    val from = renderedProgress
                    val target = gesture.onCancel()
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    animateTo(target, from, oneShot = target == 0f)
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

        private fun animateTo(
            target: Float,
            from: Float = renderedProgress,
            oneShot: Boolean = false,
            afterClosed: (() -> Unit)? = null,
        ) {
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
                duration = if (oneShot && target == 0f) {
                    homeSidePanelOneShotCloseDuration(from, target)
                } else {
                    homeSidePanelNormalCloseDuration(from, target)
                }
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
            }
            val fabCandidate = AddMainScreenFab.hostViewFor(activity)?.takeIf { it.parent != null }
            if (fabHostView?.parent == null && fabCandidate == null) {
                fabHostView = null
            }
            if (fabCandidate != null && fabHostView !== fabCandidate) {
                restoreFabHostToOriginalParent()
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

        private fun applyProgress(progress: Float) {
            val p = progress.coerceIn(0f, 1f)
            val becameVisible = !wasPanelVisible && p > CLOSED_EPSILON
            renderedProgress = p
            if (becameVisible) controller.onPanelOpened()
            wasPanelVisible = p > CLOSED_EPSILON
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
    private const val DRAWER_WIDTH_FRACTION = 0.84f
    private const val DIM_MAX_ALPHA = 0.52f
    private const val CLOSED_EPSILON = 0.001f
    private const val PAGE_SETTLED_EPSILON = 0.001f
}

private class HomeSidePanelHostNavigator(
    private val activity: Activity,
    private val closePanel: ((() -> Unit)?) -> Unit,
    private val ioScope: CoroutineScope,
) : HomeSidePanelNavigator {

    override fun closePanel(afterClosed: (() -> Unit)?) = closePanel.invoke(afterClosed)

    override fun openShortcut(shortcut: HomeSidePanelShortcut) {
        when (shortcut) {
            HomeSidePanelShortcut.SCAN -> startExplicit("${activity.packageName}.plugin.scanner.ui.BaseScanUI")
            HomeSidePanelShortcut.PAYMENTS -> {
                if (!startExplicit("${activity.packageName}.plugin.offline.ui.WalletOfflineCoinPurseUI")) {
                    startExplicit("${activity.packageName}.plugin.mall.ui.MallIndexUIv2")
                }
            }

            HomeSidePanelShortcut.FAVORITES -> startExplicit("${activity.packageName}.plugin.fav.ui.FavoriteIndexUI")
            HomeSidePanelShortcut.MOMENTS -> WeApi.openMoments(activity, WeApi.selfWxId)
            HomeSidePanelShortcut.VIDEO_CHANNELS -> startExplicit("${activity.packageName}.plugin.finder.ui.FinderHomeAffinityUI")
            HomeSidePanelShortcut.MARK_ALL_READ -> ioScope.launch(Dispatchers.IO) { WeConversationApi.markAllAsRead() }
            HomeSidePanelShortcut.WEKIT_SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
    }

    private fun startExplicit(className: String): Boolean {
        val intent = Intent().setClassName(activity.packageName, className)
        if (intent.resolveActivity(activity.packageManager) == null) return false
        activity.startActivity(intent)
        return true
    }
}

private val homeSidePanelHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}
