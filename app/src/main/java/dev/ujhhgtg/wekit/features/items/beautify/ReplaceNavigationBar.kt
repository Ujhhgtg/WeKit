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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import dev.ujhhgtg.reflekt.reflekt
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
import dev.ujhhgtg.wekit.ui.utils.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import kotlin.math.roundToInt

@Feature(name = "美化首页底部导航栏", categories = ["界面美化"], description = "将首页底部导航栏替换为 Material Design 或 Backdrop 风格")
object ReplaceNavigationBar : ClickableFeature(), IResolveDex {

    private data class NavItem(
        val outlined: ImageVector,
        val filled: ImageVector,
        val label: String
    )

    @Stable
    private val TAB_ITEMS = listOf(
        NavItem(MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, "主页"),
        NavItem(MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, "通讯录"),
        NavItem(MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, "发现"),
        NavItem(MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, "我")
    )

    private var useFloating by prefOption("nav_bar_use_floating", false)
    private var useBackdrop by prefOption("nav_bar_use_backdrop", false)
    private var showFinderBadge by prefOption("nav_bar_show_finder_badge", true)
    private var hideLabels by prefOption("nav_bar_hide_labels", false)
    private var blurRadius by prefOption("nav_bar_blur_radius", 8)

    private const val MIN_BLUR_RADIUS = 0
    private const val MAX_BLUR_RADIUS = 40

    // Matches the double-tap threshold WeChat's own tab listener (f8/r8) uses.
    private const val DOUBLE_TAP_WINDOW_MS = 300L

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val viewPager = thisObject.reflekt()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as ViewGroup
            val tabsAdapter = thisObject.reflekt()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!
            val methodOnTabClick = tabsAdapter.reflekt()
                .firstMethod {
                    name = "onTabClick"
                }.self

            val navigateToTab = { index: Int -> methodOnTabClick.invoke(tabsAdapter, index) }

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

            val selectedPageIndexState = mutableIntStateOf(0)
            val scrollOffsetState = mutableFloatStateOf(0f)
            // Settled page index: only advances once the pager comes to rest on a page
            // (positionOffset == 0). The floating bar highlights from this so the tab
            // change happens *after* the content stops in both directions. The raw
            // `position` above flips to the target the instant a backward swipe starts,
            // which would move the pill early; the NavigationBar branch still needs that
            // raw value for its scroll-driven color cross-fade.
            val settledPageIndexState = mutableIntStateOf(0)
            // Target page as soon as it's decided: immediately on a tab tap, and at the
            // half-way crossing during a finger swipe. Drives the discrete spring so a tap
            // still bulges + slides the pill instead of teleporting.
            val targetPageIndexState = mutableIntStateOf(0)
            // True only while the pager is being moved by a finger (SCROLL_STATE_DRAGGING),
            // through to the follow-on settle. A tab tap smooth-scrolls (SETTLING) without
            // ever passing through DRAGGING, so it stays false and takes the spring path.
            val isSwipingState = mutableStateOf(false)
            var pageDidDrag = false

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = args[0] as Int
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
                    targetPageIndexState.intValue = args[0] as Int
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

                        val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                        val activeColor = MaterialTheme.colorScheme.primary
                        val inactiveColor = if (isSystemInDarkTheme()) Color(0xFF999999) else Color(0xFF181818)

                        if (!useFloating) {
                            val offset by scrollOffsetState
                            NavigationBar(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                containerColor = backgroundColor
                            ) {
                                TAB_ITEMS.forEachIndexed { index, item ->
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
                                        modifier = if (index == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier,
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
                                                    } else if (index == 2 && showFinderBadge) {
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
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FloatingBottomBar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
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
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        onTabClicked(index)
                                    },
                                    // Long-pressing the "发现" tab while it is already selected:
                                    // the pill sits on top and eats the event, so the item's
                                    // onLongPress modifier never fires — forward it here instead.
                                    onTabReselectedLongPress = { index ->
                                        if (index == 2) openImproveSnsTimeline()
                                    },
                                    // Sample WeChat's real content (native ViewPager) into the
                                    // glass. rememberLayerBackdrop would only capture Compose
                                    // pixels, of which there are none behind this overlay bar.
                                    backdrop = rememberViewBackdrop(viewPager),
                                    tabsCount = TAB_ITEMS.size,
                                    isBlurEnabled = useBackdrop,
                                    blurRadius = blurRadius.dp,
                                    colors = FloatingBottomBarDefaults.colors(
                                        containerColor = backgroundColor,
                                        indicatorColor = activeColor,
                                        contentColor = inactiveColor,
                                        activeContentColor = activeColor
                                    )
                                ) {
                                    TAB_ITEMS.forEachIndexed { index, item ->
                                        val isSelected = index == settledIndex

                                        FloatingBottomBarItem(
                                            onClick = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                onTabClicked(index)
                                            },
                                            modifier = Modifier
                                                .then(if (index == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier)
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
                                                    } else if (index == 2 && showFinderBadge) {
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
    }

    private val unreadCountState = mutableIntStateOf(0)
    private val finderUnreadCountState = mutableIntStateOf(0)
    private val showFinderDotState = mutableStateOf(false)

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
            var showFinderBadgeInput by remember { mutableStateOf(showFinderBadge) }
            var hideLabelsInput by remember { mutableStateOf(hideLabels) }
            var blurRadiusInput by remember { mutableFloatStateOf(blurRadius.toFloat()) }

            AlertDialogContent(
                title = { Text("美化首页底部导航栏") },
                text = {
                    DefaultColumn {
                        ListItem(
                            trailingContent = {
                                Switch(
                                    useFloatingInput,
                                    { useFloatingInput = it })
                            },
                            headlineContent = { Text("使用悬浮底栏") },
                        )
                        ListItem(
                            trailingContent = {
                                Switch(
                                    useBackdropInput,
                                    { useBackdropInput = it })
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            headlineContent = { Text("启用液态玻璃效果") },
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
                                headlineContent = {
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
                            headlineContent = { Text("隐藏标签文本") },
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
                            headlineContent = { Text("显示「发现」标签角标") },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        useFloating = useFloatingInput
                        useBackdrop = useBackdropInput
                        hideLabels = hideLabelsInput
                        showFinderBadge = showFinderBadgeInput
                        blurRadius = blurRadiusInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private val methodUpdateTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
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
}
