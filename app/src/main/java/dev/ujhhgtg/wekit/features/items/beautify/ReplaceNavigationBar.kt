package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarDefaults
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarItem
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.DebugComposeView
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Feature(name = "美化首页底部导航栏", categories = ["界面美化"], description = "将首页底部导航栏替换为 Material Design 或 Backdrop 风格")
object ReplaceNavigationBar : ClickableFeature(), IResolveDex {

    private data class NavItem(
        val wechatIndex: Int,
        val outlined: ImageVector,
        val filled: ImageVector,
        val label: String
    )

    @Stable
    private val TAB_ITEMS = listOf(
        NavItem(0, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, "主页"),
        NavItem(1, MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, "通讯录"),
        NavItem(2, MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, "发现"),
        NavItem(3, MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, "我")
    )

    private var useFloating by prefOption("nav_bar_use_floating", true)
    private var useBackdrop by prefOption("nav_bar_use_backdrop", true)
    private var animatePageChange by prefOption("nav_bar_animate_page_change", true)
    private var showFinderBadge by prefOption("nav_bar_show_finder_badge", true)
    private var hideLabels by prefOption("nav_bar_hide_labels", false)
    private var blurRadius by prefOption("nav_bar_blur_radius", 8)
    private var barScalePercent by prefOption("nav_bar_scale", 100)
    private var tabOrder by prefOption("nav_bar_tab_order", TAB_ITEMS.joinToString(",") { it.wechatIndex.toString() })
    private var enabledTabs by prefOption("nav_bar_enabled_tabs", TAB_ITEMS.map { it.wechatIndex.toString() }.toSet())

    private const val MIN_BLUR_RADIUS = 0
    private const val MAX_BLUR_RADIUS = 40

    private const val MIN_BAR_SCALE = 50
    private const val MAX_BAR_SCALE = 150
    private const val BAR_SCALE_STEP = 5
    private const val BASE_BAR_HEIGHT_DP = 56

    // Matches the double-tap threshold WeChat's own tab listener (f8/r8) uses.
    private const val DOUBLE_TAP_WINDOW_MS = 300L

    private fun normalizedTabOrder(rawOrder: String = tabOrder): List<NavItem> {
        val orderedIndices = rawOrder.split(",")
            .mapNotNull(String::toIntOrNull)
            .filter { index -> TAB_ITEMS.any { it.wechatIndex == index } }
            .distinct()
            .toMutableList()
        TAB_ITEMS.forEach { item ->
            if (item.wechatIndex !in orderedIndices) orderedIndices += item.wechatIndex
        }
        return orderedIndices.map { index -> TAB_ITEMS.first { it.wechatIndex == index } }
    }

    private fun normalizedEnabledTabIndices(
        orderedItems: List<NavItem>,
        rawEnabled: Set<String> = enabledTabs,
    ): Set<Int> {
        val validIndices = TAB_ITEMS.mapTo(mutableSetOf(), NavItem::wechatIndex)
        val enabled = rawEnabled.mapNotNull(String::toIntOrNull)
            .filterTo(linkedSetOf()) { it in validIndices }
        if (enabled.isEmpty()) enabled += orderedItems.first().wechatIndex
        return enabled
    }

    override fun onEnable() {
        // Freeze the page set for this process. Changing these options is intentionally applied
        // only on the next WeChat launch because FragmentStatePagerAdapter cannot safely change
        // the meaning of already-instantiated positions.
        val orderedTabItems = normalizedTabOrder()
        val enabledTabIndices = normalizedEnabledTabIndices(orderedTabItems)
        val visibleTabItems = orderedTabItems.filter { it.wechatIndex in enabledTabIndices }
        val visibleWechatIndices = visibleTabItems.map(NavItem::wechatIndex)
        val remapProgrammaticTab = ThreadLocal.withInitial { false }
        val allowLogicalTabCount = ThreadLocal.withInitial { false }
        val callbackPagerIndex = ThreadLocal<Int?>()

        val tabsAdapterClass = "com.tencent.mm.ui.MainTabUI\$TabsAdapter".toClass()
        tabsAdapterClass.reflekt().apply {
            firstMethod { name = "getCount" }.hookAfter(priority = 100) {
                result = if (allowLogicalTabCount.get() == true) TAB_ITEMS.size else visibleTabItems.size
            }
            firstMethod {
                name = "getItem"
                parameters(int)
            }.hookBefore(priority = 100) {
                args[0] = visibleTabItems[args[0] as Int].wechatIndex
            }

            listOf("onPageScrolled", "onPageSelected").forEach { callbackName ->
                firstMethod { name = callbackName }.apply {
                    hookBefore(priority = 100) {
                        val pagerIndex = args[0] as Int
                        callbackPagerIndex.set(pagerIndex)
                        args[0] = visibleTabItems[pagerIndex].wechatIndex
                    }
                    hookAfter(priority = 100) {
                        callbackPagerIndex.remove()
                    }
                }
            }

            firstMethod {
                name = "onTabClick"
                parameters(int)
            }.apply {
                hookBefore(priority = 100) {
                    if (args[0] as Int !in visibleWechatIndices) {
                        result = null
                    } else {
                        remapProgrammaticTab.set(true)
                    }
                }
                hookAfter(priority = 100) {
                    remapProgrammaticTab.remove()
                }
            }
        }

        methodChangeTab.apply {
            hookBefore(priority = 100) {
                val requestedIndex = args[0] as Int
                if (requestedIndex !in visibleWechatIndices) {
                    args[0] = visibleWechatIndices.first()
                }
                remapProgrammaticTab.set(true)
                // MainTabUI checks the logical WeChat index against getCount() before it
                // reaches the pager. Let that check see four logical tabs; the pager itself
                // sees the reduced count after setCurrentItem is entered below.
                allowLogicalTabCount.set(true)
            }
            hookAfter(priority = 100) {
                remapProgrammaticTab.remove()
                allowLogicalTabCount.remove()
            }
        }

        val animatePageChange = animatePageChange

        "com.tencent.mm.ui.mogic.WxViewPager".toClass().reflekt().apply {
            listOf("setCurrentItem", "setCurrentItemNotify").forEach { methodName ->
                firstMethod {
                    name = methodName
                    parameters(int, bool)
                }.hookBefore(priority = 100) {
                    if (remapProgrammaticTab.get() != true) return@hookBefore
                    val logicalIndex = args[0] as Int
                    val pagerIndex = visibleWechatIndices.indexOf(logicalIndex)
                    if (pagerIndex >= 0) args[0] = pagerIndex
                    allowLogicalTabCount.set(false)
                    // The second parameter is the pager's `smoothScroll` flag. WeChat always
                    // passes false (MainTabUI.TabsAdapter.onTabClick calls
                    // `setCurrentItem(index, false)`), which is why the content snaps to the new
                    // tab instantly. Flipping it to true makes WxViewPager animate the same
                    // horizontal slide a finger swipe produces. Doing this inside the
                    // `remapProgrammaticTab` guard keeps it scoped to actual tab changes — the
                    // state-restore and first-layout paths never reach here. Non-adjacent jumps
                    // sweep past the pages in between, but MainTabUI sets an offscreen page
                    // limit of 4, so every one of them is alive and renders real content. The
                    // pager caps the scroll duration at 600ms on its own.
                    if (animatePageChange) args[1] = true
                }
            }
        }

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
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
            val methodOnTabClick = tabsAdapter.reflekt()
                .firstMethod {
                    name = "onTabClick"
                }.self

            val navigateToTab = { pagerIndex: Int ->
                WeLogger.d("NavTap", "navigateToTab pagerIndex=$pagerIndex")
                methodOnTabClick.invoke(tabsAdapter, visibleTabItems[pagerIndex].wechatIndex)
            }

            val viewParent = viewPager.parent as ViewGroup
            val bottomTabViewGroup = viewParent.getChildAt(1) as ViewGroup

            // WeChat's original bottom tab (LauncherUIBottomTabView) is kept alive — we only
            // clear its children below — so its own OnClickListener (an `f8`/`r8` instance)
            // survives with its double-tap state machine and the LiveData event it fires.
            // Double-tapping the Chat tab makes that listener fire WeChat's "scroll to next
            // unread conversation" event, which MainUI already observes. We capture the
            // listener and replay two rapid clicks to reproduce that behaviour, so we don't
            // have to resolve the fully-obfuscated event class ourselves.
            val bottomTabClickListener = runCatching {
                bottomTabViewGroup.reflekt()
                    .firstField { type = View.OnClickListener::class }
                    .get() as? View.OnClickListener
            }.getOrNull()
            val doubleTapProbeView = View(activity).apply { tag = 0 }

            var lastHomeTapUptime = 0L
            val onTabClicked = { index: Int ->
                WeLogger.d("NavTap", "onTabClicked index=$index")
                if (index == 0 && bottomTabClickListener != null &&
                    SystemClock.uptimeMillis() - lastHomeTapUptime <= DOUBLE_TAP_WINDOW_MS
                ) {
                    // Second tap on the Chat tab within the double-tap window: drive WeChat's
                    // own listener twice so its internal timing check trips and fires the
                    // scroll-to-next-unread event.
                    bottomTabClickListener.onClick(doubleTapProbeView)
                    bottomTabClickListener.onClick(doubleTapProbeView)
                    lastHomeTapUptime = SystemClock.uptimeMillis()
                } else {
                    navigateToTab(index)
                    lastHomeTapUptime = if (index == 0) SystemClock.uptimeMillis() else 0L
                }
            }

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            bottomTabViewGroup.setLifecycleOwner(lifecycleOwner)

            val initialPagerIndex = viewPager.currentItem
            val selectedPageIndexState = mutableIntStateOf(initialPagerIndex)
            val scrollOffsetState = mutableFloatStateOf(0f)
            // Settled page index: only advances once the pager comes to rest on a page
            // (positionOffset == 0). The floating bar highlights from this so the tab
            // change happens *after* the content stops in both directions. The raw
            // `position` above flips to the target the instant a backward swipe starts,
            // which would move the pill early; the NavigationBar branch still needs that
            // raw value for its scroll-driven color cross-fade.
            val settledPageIndexState = mutableIntStateOf(initialPagerIndex)
            // Target page as soon as it's decided: immediately on a tab tap, and at the
            // half-way crossing during a finger swipe. Drives the discrete spring so a tap
            // still bulges + slides the pill instead of teleporting.
            val targetPageIndexState = mutableIntStateOf(initialPagerIndex)
            // True only while the pager is being moved by a finger (SCROLL_STATE_DRAGGING),
            // through to the follow-on settle. A tab tap smooth-scrolls (SETTLING) without
            // ever passing through DRAGGING, so it stays false and takes the spring path.
            val isSwipingState = mutableStateOf(false)
            var pageDidDrag = false

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = callbackPagerIndex.get()
                        ?: visibleWechatIndices.indexOf(args[0] as Int).coerceAtLeast(0)
                    val positionOffset = args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset
                    if (positionOffset == 0f) {
                        settledPageIndexState.intValue = position
                    }
                }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageSelected" }
                .hookBefore {
                    targetPageIndexState.intValue = callbackPagerIndex.get()
                        ?: visibleWechatIndices.indexOf(args[0] as Int).coerceAtLeast(0)
                }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrollStateChanged" }
                .hookBefore {
                    when (args[0] as Int) {
                        1 -> { // DRAGGING: finger is moving the pager
                            pageDidDrag = true
                            isSwipingState.value = true
                        }

                        2 -> { // SETTLING: keep tracking only if this settle came from a drag
                            isSwipingState.value = pageDidDrag
                        }

                        else -> { // IDLE
                            isSwipingState.value = false
                            pageDidDrag = false
                        }
                    }
                }

            val useFloating = useFloating
            val useBackdrop = useBackdrop
            val showFinderBadge = showFinderBadge
            val hideLabels = hideLabels
            val blurRadius = blurRadius
            val barScale = barScalePercent.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE) / 100f

            val composeContainer = DebugComposeView(activity, "bottomBar")
            val composeView = ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    InjectedUiTheme {
                        val view = LocalView.current

                        // Long-press "发现" tab to jump straight into the improved timeline.
                        val openImproveSnsTimeline = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            activity.startActivity(
                                Intent().setClassName(
                                    "com.tencent.mm",
                                    "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
                                )
                            )
                        }

                        var selectedIndex by selectedPageIndexState
                        val settledIndex by settledPageIndexState
                        val targetIndex by targetPageIndexState
                        val unreadCount by unreadCountState
                        val finderUnreadCount by finderUnreadCountState
                        val showFinderDot by showFinderDotState
                        val contactUnreadCount by contactUnreadCountState

                        val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                        val activeColor = MaterialTheme.colorScheme.primary
                        val inactiveColor = if (isSystemInDarkTheme()) Color(0xFF999999) else Color(0xFF181818)

                        // Scale the bar by overriding the density rather than wrapping it in a
                        // graphicsLayer: every dp/sp inside (height, icons, pill, blur radius,
                        // shadows) is then laid out at the new size instead of being resampled,
                        // so the glass stays crisp and touch targets match what's drawn. Window
                        // insets are unaffected — they round-trip through the same density.
                        val baseDensity = LocalDensity.current
                        val scaledDensity = remember(baseDensity, barScale) {
                            Density(baseDensity.density * barScale, baseDensity.fontScale)
                        }

                        if (!useFloating) {
                            val offset by scrollOffsetState
                            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                                NavigationBar(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(BASE_BAR_HEIGHT_DP.dp),
                                    containerColor = backgroundColor
                                ) {
                                    visibleTabItems.forEachIndexed { index, item ->
                                        val isSelected = index == selectedIndex
                                        val isNext = index == selectedIndex + 1

                                        val tint = when {
                                            isSelected -> lerpColor(
                                                activeColor,
                                                inactiveColor,
                                                offset
                                            )

                                            isNext -> lerpColor(
                                                inactiveColor,
                                                activeColor,
                                                offset
                                            )

                                            else -> inactiveColor
                                        }

                                        val showFilled = if (offset < 0.5f) isSelected else isNext

                                        NavigationBarItem(
                                            selected = isSelected && offset < 0.5f,
                                            onClick = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                onTabClicked(index)
                                            },
                                            modifier = if (item.wechatIndex == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier,
                                            icon = {
                                                BadgedBox(
                                                    badge = {
                                                        if (index == 0 && unreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 1 && contactUnreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (contactUnreadCount <= 99) contactUnreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 2 && showFinderBadge) {
                                                            if (finderUnreadCount > 0) {
                                                                Badge(containerColor = Color(0xFFFF3B30)) {
                                                                    Text(
                                                                        if (finderUnreadCount <= 99) finderUnreadCount.toString() else "99+",
                                                                        color = Color.White, fontSize = 10.sp
                                                                    )
                                                                }
                                                            } else if (showFinderDot) {
                                                                Badge(containerColor = Color(0xFFFF3B30))
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Crossfade(
                                                        targetState = showFilled,
                                                        animationSpec = tween(200),
                                                        label = "navIcon"
                                                    ) { filled ->
                                                        Icon(
                                                            imageVector = if (filled) item.filled else item.outlined,
                                                            contentDescription = item.label,
                                                            tint = tint
                                                        )
                                                    }
                                                }
                                            },
                                            label = null,
                                            alwaysShowLabel = false,
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = activeColor.copy(alpha = 0.15f),
                                                selectedIconColor = activeColor,
                                                unselectedIconColor = inactiveColor,
                                                selectedTextColor = activeColor,
                                                unselectedTextColor = inactiveColor
                                            )
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput("rootRaw") {
                                        awaitEachGesture {
                                            var running = true
                                            while (running) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                event.changes.forEach {
                                                    WeLogger.d(
                                                        "PillHit",
                                                        "rootRaw ${it.type} pos=${it.position} " +
                                                            "pressed=${it.pressed} consumed=${it.isConsumed}",
                                                    )
                                                }
                                                running = event.changes.any { it.pressed }
                                            }
                                        }
                                    }
                            ) {
                                val bottomCenter = Modifier.align(Alignment.BottomCenter)

                                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                                    FloatingBottomBar(
                                        modifier = bottomCenter
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {},
                                            )
                                            .padding(
                                                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                                                    .calculateBottomPadding()
                                            ),
                                        // Spring target: on a tap this is the tapped tab, so the
                                        // pill bulges and slides across. During a swipe the gate
                                        // below hands position control to `progress` instead.
                                        selectedIndex = { targetIndex },
                                        // Drive the indicator from the pager's live fractional
                                        // scroll position so the pill tracks the content 1:1 in
                                        // both directions, like the non-floating bar's crossfade.
                                        progress = { selectedIndex + scrollOffsetState.floatValue },
                                        isTracking = { isSwipingState.value },
                                        onSelected = { navigateToTab(it) },
                                        // In glass mode the pill covers the selected tab and eats
                                        // the tap before the item's onClick can run, so tapping /
                                        // double-tapping the current tab (e.g. Home) would do
                                        // nothing. Route that tap through the same haptic + tab
                                        // handler the items use, restoring double-tap-to-next-unread.
                                        onTabReselected = { index ->
                                            WeLogger.d("NavTap", "onTabReselected index=$index")
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            onTabClicked(index)
                                        },
                        // Long-pressing the "发现" tab while it is already selected:
                                        // the pill sits on top and eats the event, so the item's
                                        // onLongPress modifier never fires — forward it here instead.
                                        onTabReselectedLongPress = { index ->
                                            WeLogger.d("NavTap", "onTabReselectedLongPress index=$index")
                                            if (visibleTabItems[index].wechatIndex == 2) openImproveSnsTimeline()
                                        },
                                        // Sample WeChat's real content (native ViewPager) into the
                                        // glass. rememberLayerBackdrop would only capture Compose
                                        // pixels, of which there are none behind this overlay bar.
                                        backdrop = rememberViewBackdrop(viewPager),
                                        tabsCount = visibleTabItems.size,
                                        isBlurEnabled = useBackdrop,
                                        blurRadius = blurRadius.dp,
                                        colors = FloatingBottomBarDefaults.colors(
                                            containerColor = backgroundColor,
                                            indicatorColor = activeColor,
                                            contentColor = inactiveColor,
                                            activeContentColor = activeColor
                                        )
                                    ) {
                                        visibleTabItems.forEachIndexed { index, item ->
                                            val isSelected = index == settledIndex

                                            FloatingBottomBarItem(
                                                onClick = {
                                                    WeLogger.d("NavTap", "item onClick index=$index")
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                    onTabClicked(index)
                                                },
                                                modifier = Modifier
                                                    .then(if (item.wechatIndex == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier)
                                                    .defaultMinSize(minWidth = 76.dp)
                                            ) {
                                                BadgedBox(
                                                    badge = {
                                                        if (index == 0 && unreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 1 && contactUnreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (contactUnreadCount <= 99) contactUnreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 2 && showFinderBadge) {
                                                            if (finderUnreadCount > 0) {
                                                                Badge(containerColor = Color(0xFFFF3B30)) {
                                                                    Text(
                                                                        if (finderUnreadCount <= 99) finderUnreadCount.toString() else "99+",
                                                                        color = Color.White, fontSize = 10.sp
                                                                    )
                                                                }
                                                            } else if (showFinderDot) {
                                                                Badge(containerColor = Color(0xFFFF3B30))
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Crossfade(
                                                        targetState = isSelected,
                                                        animationSpec = tween(200),
                                                        label = "navIconFloating"
                                                    ) { selected ->
                                                        Icon(
                                                            imageVector = if (selected) item.filled else item.outlined,
                                                            contentDescription = item.label
                                                        )
                                                    }
                                                }
                                                if (!hideLabels) {
                                                    Text(
                                                        text = item.label,
                                                        fontSize = 11.sp,
                                                        lineHeight = 14.sp,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Visible
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            composeContainer.addView(composeView)

            if (useFloating) {
                // In floating mode, hide the original tab bar container so that WeChat's
                // FrostedContentView reads its height as 0 and doesn't draw a frosted grey
                // overlay behind it. Instead, attach the ComposeView directly to the parent
                // FrameLayout as an overlay on top of the content.
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.visibility = View.GONE

                // The pill scales up (press bulge ~1.39x plus velocity overshoot) via a
                // graphicsLayer, so it draws beyond the ComposeView's WRAP_CONTENT bounds.
                // The bottom overdraw lands in the padding/inset gap, but the top overdraw
                // extends above the ComposeView and would be clipped by the Android view
                // hierarchy. Disable child/padding clipping on the parent so it renders.
                viewParent.clipChildren = false
                viewParent.clipToPadding = false
                composeContainer.clipChildren = false
                composeContainer.clipToPadding = false

                viewParent.addView(
                    composeContainer,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                )
            } else {
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.addView(composeContainer)
            }
        }

        methodUpdateTabUnread.hookBefore {
            val count = args[0] as Int
            unreadCountState.intValue = count
            result = null
        }

        methodUpdateFriendTabUnread.hookBefore {
            val count = args[0] as Int
            finderUnreadCountState.intValue = count
            result = null
        }

        methodShowFriendPoint.hookBefore {
            val show = args[0] as Boolean
            showFinderDotState.value = show
            result = null
        }

        methodUpdateContactTabUnread.hookBefore {
            val count = args[0] as Int
            contactUnreadCountState.intValue = count
            result = null
        }

        // Suppress FrostedContentView's bottom blur overlay in floating mode.
        //
        // In WeChat 8.0.69, MainUI.q0() (onResume) calls:
        //   frostedContentView.a(true, tabBar.getHeight())
        // synchronously during doOnCreate — before our hookAfter fires and
        // sets the tab bar to GONE. By that point bottomBlurAreaHeight is
        // already set to the real measured height. Worse, a() has a <= 0
        // fallback: if height is 0 it computes dimen.b2*density + nav_bar_height,
        // producing the short frosted-glass strip you see below our bar.
        // Hooking a() and forcing its first arg (frostedEnabled) to false is the
        // only reliable fix regardless of call timing.
        "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
            parameters { it[0] == bool && it[1] == int }
        }.hookBefore {
            if (useFloating) args[0] = false
        }
    }

    private val unreadCountState = mutableIntStateOf(0)
    private val finderUnreadCountState = mutableIntStateOf(0)
    private val showFinderDotState = mutableStateOf(false)
    private val contactUnreadCountState = mutableIntStateOf(0)

    /**
     * Non-consuming long-press modifier. Fires [block] when the pointer is held down long enough,
     * but does **not** consume the down/up events, so the item's own tap ripple and onClick still work.
     */
    private fun Modifier.onLongPress(block: () -> Unit): Modifier = pointerInput(block) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            block()
        }
    }

    private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (stop.red - start.red) * f,
            green = start.green + (stop.green - start.green) * f,
            blue = start.blue + (stop.blue - start.blue) * f,
            alpha = start.alpha + (stop.alpha - start.alpha) * f
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useFloatingInput by remember { mutableStateOf(useFloating) }
            var useBackdropInput by remember { mutableStateOf(useBackdrop) }
            var animatePageChangeInput by remember { mutableStateOf(animatePageChange) }
            var showFinderBadgeInput by remember { mutableStateOf(showFinderBadge) }
            var hideLabelsInput by remember { mutableStateOf(hideLabels) }
            var blurRadiusInput by remember { mutableFloatStateOf(blurRadius.toFloat()) }
            var barScaleInput by remember {
                mutableFloatStateOf(barScalePercent.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE).toFloat())
            }

            AlertDialogContent(
                title = { Text("美化首页底部导航栏") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTabManagementDialog(context) },
                            leadingContent = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Settings,
                                    contentDescription = null,
                                )
                            },
                            content = { Text("页面管理") },
                            supportingContent = { Text("开关页面及调整顺序, 下次启动微信生效") },
                        )
                        ListItem(
                            trailingContent = {
                                Switch(
                                    animatePageChangeInput,
                                    { animatePageChangeInput = it })
                            },
                            supportingContent = { Text("点击标签时滑动切换页面, 而非直接跳转") },
                            content = { Text("启用页面切换动画") },
                        )
                        ListItem(
                            trailingContent = {
                                Switch(
                                    useFloatingInput,
                                    { useFloatingInput = it })
                            },
                            content = { Text("使用悬浮底栏") },
                        )
                        ListItem(
                            trailingContent = {
                                Switch(
                                    useBackdropInput,
                                    { useBackdropInput = it })
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            content = { Text("启用液态玻璃效果") },
                        )
                        if (useBackdropInput) {
                            ListItem(
                                supportingContent = {
                                    Slider(
                                        value = blurRadiusInput,
                                        onValueChange = { blurRadiusInput = it },
                                        valueRange = MIN_BLUR_RADIUS.toFloat()..MAX_BLUR_RADIUS.toFloat(),
                                        steps = MAX_BLUR_RADIUS - MIN_BLUR_RADIUS - 1
                                    )
                                },
                                content = {
                                    val r = blurRadiusInput.roundToInt()
                                    Text(if (r <= 0) "模糊半径: 关闭 (完全透明)" else "模糊半径: $r")
                                },
                            )
                        }
                        ListItem(
                            trailingContent = {
                                Switch(
                                    hideLabelsInput,
                                    { hideLabelsInput = it })
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            content = { Text("隐藏标签文本") },
                        )
                        ListItem(
                            supportingContent = {
                                Slider(
                                    value = barScaleInput,
                                    onValueChange = { barScaleInput = it },
                                    valueRange = MIN_BAR_SCALE.toFloat()..MAX_BAR_SCALE.toFloat(),
                                    steps = (MAX_BAR_SCALE - MIN_BAR_SCALE) / BAR_SCALE_STEP - 1
                                )
                            },
                            content = { Text("底栏缩放: ${barScaleInput.roundToInt()}%") },
                        )
                        ListItem(
                            modifier = Modifier,
                            leadingContent = null,
                            trailingContent = {
                                Switch(
                                    showFinderBadgeInput,
                                    { showFinderBadgeInput = it })
                            },
                            supportingContent = { Text("包含朋友圈新通知数量等") },
                            content = { Text("显示「发现」标签角标") },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        useFloating = useFloatingInput
                        useBackdrop = useBackdropInput
                        animatePageChange = animatePageChangeInput
                        hideLabels = hideLabelsInput
                        showFinderBadge = showFinderBadgeInput
                        blurRadius = blurRadiusInput.roundToInt()
                        barScalePercent = barScaleInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showTabManagementDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            val currentOrder = remember { normalizedTabOrder().toMutableStateList() }
            val currentEnabled = remember {
                normalizedEnabledTabIndices(currentOrder).toMutableStateList()
            }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text("页面管理") },
                text = {
                    DefaultColumn {
                        Column {
                            Text("显示与顺序", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "长按拖动手柄调整顺序，至少保留一个页面",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NavigationTabReorderableList(
                            items = currentOrder,
                            itemKey = NavItem::wechatIndex,
                            onMove = { from, to ->
                                currentOrder.add(to, currentOrder.removeAt(from))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) { item, dragHandleModifier ->
                            val checked = item.wechatIndex in currentEnabled
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .then(dragHandleModifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Drag_handle,
                                        contentDescription = "拖动${item.label}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    imageVector = item.outlined,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = item.label,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Switch(
                                    checked = checked,
                                    enabled = !checked || currentEnabled.size > 1,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            if (item.wechatIndex !in currentEnabled) {
                                                currentEnabled += item.wechatIndex
                                            }
                                        } else if (currentEnabled.size > 1) {
                                            currentEnabled.remove(item.wechatIndex)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        tabOrder = currentOrder.joinToString(",") { it.wechatIndex.toString() }
                        enabledTabs = currentEnabled.map(Int::toString).toSet()
                        onDismiss()
                    }) { Text("确定") }
                },
            )
        }
    }

    private val methodUpdateTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
        }
    }

    private val methodChangeTab by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MainTabUI"
            usingEqStrings(
                "change tab to %d, cur tab %d, has init tab %B, tab cache size %d"
            )
        }
    }

    private val methodUpdateFriendTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateFriendTabUnread] unread : ")
        }
    }

    private val methodShowFriendPoint by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[showFriendPoint] show : ")
        }
    }

    private val methodUpdateContactTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateContactTabUnread] unread : ")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> NavigationTabReorderableList(
    items: List<T>,
    itemKey: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        userScrollEnabled = draggingKey == null,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> itemKey(item) },
        ) { _, item ->
            val key = itemKey(item)
            val isDragging = draggingKey == key
            val dragHandleModifier = Modifier.pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
                            draggingKey = key
                            dragOffset = 0f
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggingKey != key) return@detectDragGesturesAfterLongPress
                        dragOffset += amount.y

                        val currentInfo = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }
                            ?: return@detectDragGesturesAfterLongPress
                        val currentIndex = currentInfo.index
                        val start = currentInfo.offset + dragOffset
                        val end = start + currentInfo.size
                        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { targetInfo ->
                            if (targetInfo.index == currentIndex) {
                                false
                            } else if (dragOffset > 0f) {
                                targetInfo.index > currentIndex &&
                                    end > targetInfo.offset + targetInfo.size / 2
                            } else {
                                targetInfo.index < currentIndex &&
                                    start < targetInfo.offset + targetInfo.size / 2
                            }
                        }
                        if (target != null) {
                            onMove(currentIndex, target.index)
                            dragOffset -= target.offset - currentInfo.offset
                        }

                        val viewport = listState.layoutInfo
                        val center = currentInfo.offset + dragOffset + currentInfo.size / 2
                        when {
                            center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                coroutineScope.launch { listState.scrollBy(-12f) }

                            center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                coroutineScope.launch { listState.scrollBy(12f) }
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        scaleX = if (isDragging) 1.02f else 1f
                        scaleY = if (isDragging) 1.02f else 1f
                        shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                    }
                    .then(if (isDragging) Modifier else Modifier.animateItem())
            ) {
                itemContent(item, dragHandleModifier)
            }
        }
    }
}
