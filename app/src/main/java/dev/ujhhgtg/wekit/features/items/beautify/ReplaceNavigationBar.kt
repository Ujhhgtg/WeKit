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
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarDefaults
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarMode
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.ReorderableList
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import kotlin.math.roundToInt

object ReplaceNavigationBar : ClickableFeature(), IResolveDex {

    override val technicalId = "美化首页底部导航栏"
    override val nameRes = R.string.feature_replace_navigation_bar_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_replace_navigation_bar_description

    private data class NavItem(
        val wechatIndex: Int,
        val outlined: ImageVector,
        val filled: ImageVector,
        @StringRes val labelRes: Int,
    )

    @Stable
    private val TAB_ITEMS = listOf(
        NavItem(0, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, R.string.nav_tab_home),
        NavItem(1, MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, R.string.nav_tab_contacts),
        NavItem(2, MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, R.string.nav_tab_discover),
        NavItem(3, MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, R.string.nav_tab_me),
    )

    private var useFloating by prefOption("nav_bar_use_floating", true)
    private var useBackdrop by prefOption("nav_bar_use_backdrop", true)
    private var animatePageChange by prefOption("nav_bar_animate_page_change", true)
    private var showFinderBadge by prefOption("nav_bar_show_finder_badge", true)
    private var hideLabels by prefOption("nav_bar_hide_labels", false)
    private var blurRadius by prefOption("nav_bar_blur_radius", 8)
    private var dynamicGravityHighlight by prefOption("nav_bar_dynamic_gravity_highlight", false)
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

    private fun normalizedEnabledTabIndices(rawEnabled: Set<String> = enabledTabs): Set<Int> {
        val validIndices = TAB_ITEMS.mapTo(mutableSetOf(), NavItem::wechatIndex)
        return rawEnabled.mapNotNull(String::toIntOrNull)
            .filterTo(linkedSetOf()) { it in validIndices }
    }

    override fun onEnable() {
        // Freeze the page set for this process. Changing these options is intentionally applied
        // only on the next WeChat launch because FragmentStatePagerAdapter cannot safely change
        // the meaning of already-instantiated positions.
        val orderedTabItems = normalizedTabOrder()
        val enabledTabIndices = normalizedEnabledTabIndices()
        val visibleTabItems = orderedTabItems.filter { it.wechatIndex in enabledTabIndices }

        if (visibleTabItems.isEmpty()) {
            WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
                val viewPager = thisObject!!.reflekt()
                    .firstField {
                        name = "mViewPager"
                    }
                    .get()!! as WxViewPager
                val viewParent = viewPager.parent as ViewGroup
                val bottomTabViewGroup = viewParent.getChildAt(1) as ViewGroup

                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.visibility = View.GONE
            }

            // Without a replacement bar, WeChat's bottom blur must also be disabled or it
            // leaves a frosted strip where the original navigation bar used to be.
            "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
                parameters { it[0] == bool && it[1] == int }
            }.hookBefore {
                args[0] = false
            }
            return
        }

        val visibleWechatIndices = visibleTabItems.map(NavItem::wechatIndex)
        val remapProgrammaticTab = ThreadLocal.withInitial { false }
        val animateNextPageChange = ThreadLocal.withInitial { false }
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
                        animateNextPageChange.set(true)
                    }
                }
                hookAfter(priority = 100) {
                    remapProgrammaticTab.remove()
                    animateNextPageChange.remove()
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

                val logicalIndex = args[0] as Int
                val pagerIndex = visibleWechatIndices.indexOf(logicalIndex)
                if (pagerIndex >= 0) {
                    val viewPager = thisObject!!.reflekt()
                        .firstField { name = "mViewPager" }
                        .get()!! as WxViewPager
                    if (viewPager.currentItem != pagerIndex) {
                        viewPager.setCurrentItem(pagerIndex, false)
                    }
                }
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
                    // The second parameter is the pager's `smoothScroll` flag. Flipping it to
                    // true makes WxViewPager animate the same horizontal slide a finger swipe
                    // produces. This is scoped to `onTabClick`-originated changes (actual tab
                    // taps) only: MainTabUI.a(int) is also driven by programmatic flows that
                    // fire rapid same-frame tab bounces — e.g. returning from the wallet
                    // "服务" page starts LauncherUI with FLAG_ACTIVITY_CLEAR_TOP +
                    // preferred_tab, which makes MainTabUI.f() call a(0) then a(3) back to
                    // back. Stock WeChat snaps both (smoothScroll=false) so the bounce is
                    // invisible; animating both round-trips desyncs the pager (content stays
                    // on the first page while the logical tab says the second). The
                    // state-restore and first-layout paths never reach here either because
                    // the `remapProgrammaticTab` guard is only armed by tab interactions.
                    // Non-adjacent jumps sweep past the pages in between, but MainTabUI sets
                    // an offscreen page limit of 4, so every one of them is alive and renders
                    // real content. The pager caps the scroll duration at 600ms on its own.
                    if (animatePageChange && animateNextPageChange.get() == true) args[1] = true
                }
            }
        }

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
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
                val isHome = visibleTabItems[index].wechatIndex == 0
                if (isHome && bottomTabClickListener != null &&
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
                    lastHomeTapUptime = if (isHome) SystemClock.uptimeMillis() else 0L
                }
            }

            bottomTabViewGroup.setLifecycleOwner(lifecycleOwner)

            val initialPagerIndex = viewPager.currentItem
            val selectedPageIndexState = mutableIntStateOf(initialPagerIndex)
            val scrollOffsetState = mutableFloatStateOf(0f)
            // Target page as soon as it's decided: immediately on a tab tap, and at the
            // half-way crossing during a finger swipe. Drives the discrete spring so a tap
            // still bulges + slides the pill instead of teleporting.
            val targetPageIndexState = mutableIntStateOf(initialPagerIndex)

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = callbackPagerIndex.get()
                        ?: visibleWechatIndices.indexOf(args[0] as Int).coerceAtLeast(0)
                    val positionOffset = args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset
                }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageSelected" }
                .hookBefore {
                    targetPageIndexState.intValue = callbackPagerIndex.get()
                        ?: visibleWechatIndices.indexOf(args[0] as Int).coerceAtLeast(0)
                }

            val useFloating = useFloating
            val useBackdrop = useBackdrop
            val showFinderBadge = showFinderBadge
            val hideLabels = hideLabels
            val dynamicGravityHighlight = dynamicGravityHighlight
            val barScale = barScalePercent.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE) / 100f

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
                                        val label = stringResource(item.labelRes)
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
                                                                    if (unreadCount <= 99) unreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 1 && contactUnreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (contactUnreadCount <= 99) contactUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (item.wechatIndex == 2 && showFinderBadge) {
                                                            if (finderUnreadCount > 0) {
                                                                Badge(containerColor = Color(0xFFFF3B30)) {
                                                                    Text(
                                                                        if (finderUnreadCount <= 99) finderUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
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
                                                            contentDescription = label,
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
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val bottomCenter = Modifier.align(Alignment.BottomCenter)

                                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                                    FloatingBottomBar(
                                        items = visibleTabItems,
                                        modifier = bottomCenter
                                            .padding(
                                                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                                                    .calculateBottomPadding()
                                            ),
                                        selectedIndex = { targetIndex },
                                        onSelected = { index ->
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            navigateToTab(index)
                                        },
                                        // Sample WeChat's real content (native ViewPager) into the
                                        // glass. rememberLayerBackdrop would only capture Compose
                                        // pixels, of which there are none behind this overlay bar.
                                        backdrop = rememberViewBackdrop(viewPager, lifecycleOwner),
                                        mode = if (useBackdrop) {
                                            FloatingBottomBarMode.LiquidGlass
                                        } else {
                                            FloatingBottomBarMode.None
                                        },
                                        colors = FloatingBottomBarDefaults.colors(
                                            containerColor = backgroundColor,
                                            indicatorColor = activeColor,
                                            contentColor = inactiveColor,
                                            activeContentColor = activeColor
                                        ),
                                        onSelectedTabTap = { index ->
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            if (visibleTabItems[index].wechatIndex == 0) {
                                                onTabClicked(index)
                                            }
                                        },
                                        onTabLongPress = { index ->
                                            if (visibleTabItems[index].wechatIndex == 2) {
                                                openImproveSnsTimeline()
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        liquidGlassBlurRadius = blurRadius.dp,
                                        dynamicGravityHighlight = dynamicGravityHighlight,
                                        iconContent = { item, index ->
                                            val label = stringResource(item.labelRes)
                                            // Key the fill crossfade to the target page (the same
                                            // driver as the pill), not the settled page: target
                                            // flips immediately on a tab tap and on finger release
                                            // during a swipe, while the settled page only advances
                                            // after the pager stops. This matches SettingsActivity's
                                            // Miuix bar, where the icon fills the moment the tab
                                            // decision is made instead of a beat after the pill.
                                            val isSelected = index == targetIndex

                                            BadgedBox(
                                                badge = {
                                                    if (index == 0 && unreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (unreadCount <= 99) unreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (item.wechatIndex == 1 && contactUnreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (contactUnreadCount <= 99) contactUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (item.wechatIndex == 2 && showFinderBadge) {
                                                        if (finderUnreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (finderUnreadCount <= 99) finderUnreadCount.toString() else stringResource(R.string.badge_count_overflow),
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
                                                        contentDescription = label
                                                    )
                                                }
                                            }
                                        },
                                        labelContent = { item, _ ->
                                            if (!hideLabels) {
                                                Text(
                                                    text = stringResource(item.labelRes),
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

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
                composeView.clipChildren = false
                composeView.clipToPadding = false

                viewParent.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                )
            } else {
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.addView(composeView)
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
            var dynamicGravityHighlightInput by remember { mutableStateOf(dynamicGravityHighlight) }
            var barScaleInput by remember {
                mutableFloatStateOf(barScalePercent.coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE).toFloat())
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_replace_navigation_bar_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_page_management),
                                    description = stringResource(R.string.nav_page_management_summary),
                                    onClick = { showTabManagementDialog(context) },
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Chevron_right,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_page_animation),
                                    description = stringResource(R.string.nav_page_animation_summary),
                                    checked = animatePageChangeInput,
                                    onCheckedChange = {
                                        animatePageChangeInput = it
                                        animatePageChange = it
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_use_floating_bar),
                                    checked = useFloatingInput,
                                    onCheckedChange = {
                                        useFloatingInput = it
                                        useFloating = it
                                    },
                                )
                            }
                            item(animatedVisibility = useFloatingInput) {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_use_liquid_glass),
                                    checked = useBackdropInput,
                                    onCheckedChange = {
                                        useBackdropInput = it
                                        useBackdrop = it
                                    },
                                )
                            }
                            item(animatedVisibility = useFloatingInput && useBackdropInput) {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_dynamic_gravity_highlight),
                                    description = stringResource(R.string.nav_dynamic_gravity_highlight_summary),
                                    checked = dynamicGravityHighlightInput,
                                    onCheckedChange = {
                                        dynamicGravityHighlightInput = it
                                        dynamicGravityHighlight = it
                                    },
                                )
                            }
                            item(animatedVisibility = useFloatingInput && useBackdropInput) {
                                BaseItemContainer {
                                    val radius = blurRadiusInput.roundToInt()
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.nav_blur_radius),
                                        value = radius,
                                        startInt = MIN_BLUR_RADIUS,
                                        endInt = MAX_BLUR_RADIUS,
                                        stepSize = 1,
                                        valueSuffix = "px",
                                        onValueChange = {
                                            blurRadiusInput = it.toFloat()
                                            blurRadius = it
                                        },
                                    )
                                }
                            }
                            item(animatedVisibility = useFloatingInput) {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_hide_labels),
                                    checked = hideLabelsInput,
                                    onCheckedChange = {
                                        hideLabelsInput = it
                                        hideLabels = it
                                    },
                                )
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.nav_bar_scale),
                                        value = barScaleInput.roundToInt(),
                                        startInt = MIN_BAR_SCALE,
                                        endInt = MAX_BAR_SCALE,
                                        stepSize = BAR_SCALE_STEP,
                                        valueSuffix = "%",
                                        onValueChange = {
                                            barScaleInput = it.toFloat()
                                            barScalePercent = it
                                        },
                                    )
                                }
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.nav_show_discover_badge),
                                    description = stringResource(R.string.nav_discover_badge_summary),
                                    checked = showFinderBadgeInput,
                                    onCheckedChange = {
                                        showFinderBadgeInput = it
                                        showFinderBadge = it
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showTabManagementDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            val currentOrder = remember { normalizedTabOrder().toMutableStateList() }
            val currentEnabled = remember {
                normalizedEnabledTabIndices().toMutableStateList()
            }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.nav_page_management)) },
                text = {
                    DefaultColumn {
                        Column {
                            Text(stringResource(R.string.nav_display_and_order), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.nav_reorder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReorderableList(
                            items = currentOrder,
                            itemKey = NavItem::wechatIndex,
                            onMove = { from, to ->
                                currentOrder.add(to, currentOrder.removeAt(from))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) { item, dragHandleModifier ->
                            val label = stringResource(item.labelRes)
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
                                        contentDescription = stringResource(R.string.nav_drag_tab_description, label),
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
                                    text = label,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            if (item.wechatIndex !in currentEnabled) {
                                                currentEnabled += item.wechatIndex
                                            }
                                        } else {
                                            currentEnabled.remove(item.wechatIndex)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        tabOrder = currentOrder.joinToString(",") { it.wechatIndex.toString() }
                        enabledTabs = currentEnabled.map(Int::toString).toSet()
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
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
