package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import android.content.Intent
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarDefaults
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarMode
import dev.ujhhgtg.wekit.ui.content.UnreadBadge
import dev.ujhhgtg.wekit.ui.content.rememberViewBackdrop

// Matches the double-tap threshold WeChat's own tab listener (q8) uses.
private const val DOUBLE_TAP_WINDOW_MS = 300L

// Matches NagramXF's CubicBezierInterpolator.EASE_OUT_QUINT, the curve of its
// scroll-hide animator for the floating bottom bar.
private val SCROLL_HIDE_EASING = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val SCROLL_HIDE_DURATION_MS = 300
private const val SCROLL_HIDE_MIN_SCALE = 0.85f

// Horizontal inset of the tab row inside FloatingBottomBar's pill.
private val BAR_CONTENT_INSET = 4.dp

const val REORDER_LIFT_SCALE = 0.12f

@Composable
fun NavTabIcon(
    tab: NavTab,
    filled: Boolean,
    nativeIcons: Map<Int, WechatTabIcons>?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val label = stringResource(tab.labelRes)
    if (nativeIcons != null) {
        val icons = nativeIcons.getValue(tab.index)
        Image(
            bitmap = if (filled) icons.filled else icons.outlined,
            contentDescription = label,
            modifier = modifier.size(24.dp),
        )
    } else {
        Icon(
            imageVector = if (filled) tab.filled else tab.outlined,
            contentDescription = label,
            modifier = modifier,
            tint = tint,
        )
    }
}

@Composable
fun NavTabBadge(tab: Int, content: @Composable () -> Unit) {
    val count = when (tab) {
        TAB_HOME -> BeautifyNavigationBar.unreadCountState.intValue
        TAB_CONTACTS -> BeautifyNavigationBar.contactUnreadCountState.intValue
        TAB_DISCOVER -> BeautifyNavigationBar.finderUnreadCountState.intValue
        else -> 0
    }
    val dot = tab == TAB_DISCOVER && BeautifyNavigationBar.showFinderDotState.value
    val config = BeautifyNavigationBar.config
    BadgedBox(
        badge = {
            if (!config.showsBadge(tab)) return@BadgedBox
            if (count > 0) {
                UnreadBadge(count.toLong())
            } else if (dot) {
                UnreadBadge()
            }
        },
        content = { content() },
    )
}

@Composable
fun NavBarCustomBar(session: NavBarSession) {
    val config by BeautifyNavigationBar.configState
    val tabs = config.visibleTabs.map(::navTab)
    val view = LocalView.current
    val activity = session.activity
    val darkTheme = isSystemInDarkTheme()
    val nativeIcons = remember(config.iconStyle, darkTheme) {
        if (config.iconStyle == NavIconStyle.NATIVE) session.nativeIcons else null
    }

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

    val doubleTapProbeView = remember { android.view.View(activity).apply { tag = TAB_HOME } }
    val lastHomeTapUptime = remember { mutableLongStateOf(0L) }
    val onHomeTap = {
        if (SystemClock.uptimeMillis() - lastHomeTapUptime.longValue <= DOUBLE_TAP_WINDOW_MS) {
            // Second tap on the Chat tab within the double-tap window: drive WeChat's
            // own listener twice so its internal timing check trips and fires the
            // scroll-to-next-unread event.
            session.nativeTabClickListener.onClick(doubleTapProbeView)
            session.nativeTabClickListener.onClick(doubleTapProbeView)
        } else {
            session.navigateToTab(TAB_HOME)
        }
        lastHomeTapUptime.longValue = SystemClock.uptimeMillis()
    }
    val onSelectedTabTap = { index: Int ->
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (tabs[index].index == TAB_HOME) onHomeTap()
    }

    val targetIndex by session.targetPageIndexState

    val backgroundColor = if (darkTheme) Color(0xFF191919) else Color(0xFFF7F7F7)
    val activeColor = nativeIcons?.getValue(tabs.first().index)?.activeColor ?: MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Scale the bar by overriding the density rather than wrapping it in a
    // graphicsLayer: every dp/sp inside (height, icons, pill, blur radius,
    // shadows) is then laid out at the new size instead of being resampled,
    // so the glass stays crisp and touch targets match what's drawn. Window
    // insets are unaffected — they round-trip through the same density.
    val baseDensity = LocalDensity.current
    val barScale = config.scalePercent / 100f
    val scaledDensity = remember(baseDensity, barScale) {
        Density(baseDensity.density * barScale, baseDensity.fontScale)
    }

    // Share the slide/shrink/fade animation for both hide reasons; only scroll-driven
    // hiding is gated by the auto-hide setting, and editing always shows the bar.
    // barHeightPx includes the bottom padding because onSizeChanged observes the
    // padded bounds, so translating by it moves the bar fully off screen.
    val editing = session.editor != null
    val barHideProgress by animateFloatAsState(
        targetValue = if (!editing && (BeautifyNavigationBar.recentPageHiddenState.value ||
                (config.autoHide && BeautifyNavigationBar.barScrollHiddenState.value))
        ) 1f else 0f,
        animationSpec = tween(SCROLL_HIDE_DURATION_MS, easing = SCROLL_HIDE_EASING),
        label = "navBarHide"
    )
    val barHeightPx = remember { mutableIntStateOf(0) }
    val bottomPadding = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val reorder = session.reorder
    val step = session.customBarLayout?.step ?: 0f
    val itemOffsets = HashMap<Int, State<Float>>()
    val itemLifts = HashMap<Int, State<Float>>()
    tabs.forEachIndexed { slot, tab ->
        key(tab.index) {
            itemOffsets[tab.index] = rememberReorderOffset(
                slot = slot,
                step = step,
                target = reorderShift(reorder, config.order, { it in config.enabledTabs }, tab.index, step, ReorderSource.BAR),
                followFinger = reorder.follows(tab.index, ReorderSource.BAR),
            )
            itemLifts[tab.index] = rememberReorderLift(reorder.draggedTab == tab.index)
        }
    }

    if (config.style == NavBarStyle.DOCKED) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            DockedBar(
                session = session,
                config = config,
                tabs = tabs,
                nativeIcons = nativeIcons,
                backgroundColor = backgroundColor,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                itemOffsets = itemOffsets,
                itemLifts = itemLifts,
                onTabClick = { tab ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (tab == TAB_HOME) onHomeTap() else session.navigateToTab(tab)
                },
                onDiscoverLongPress = openImproveSnsTimeline,
            )
        }
    } else Box(modifier = Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            val contentInsetPx = with(scaledDensity) { BAR_CONTENT_INSET.toPx() }
            FloatingBottomBar(
                items = tabs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { barHeightPx.intValue = it.height }
                    .padding(bottom = bottomPadding)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInWindow()
                        session.customBarLayout = BarLayout(
                            left = position.x + contentInsetPx,
                            top = position.y - session.barLift,
                            height = coordinates.size.height.toFloat(),
                            step = (coordinates.size.width - 2 * contentInsetPx) / tabs.size,
                            count = tabs.size,
                        )
                    }
                    .graphicsLayer {
                        val progress = barHideProgress
                        translationY = progress * barHeightPx.intValue
                        alpha = 1f - progress
                        val scale = 1f - (1f - SCROLL_HIDE_MIN_SCALE) * progress
                        scaleX = scale
                        scaleY = scale
                    },
                selectedIndex = { targetIndex },
                onSelected = { index ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    session.navigateToTab(tabs[index].index)
                },
                // Sample WeChat's real content (native ViewPager) into the glass.
                // rememberLayerBackdrop would only capture Compose pixels, of which
                // there are none behind this overlay bar.
                backdrop = rememberViewBackdrop(session.viewPager, session.lifecycleOwner),
                mode = if (config.style == NavBarStyle.LIQUID_GLASS) {
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
                onSelectedTabTap = onSelectedTabTap,
                onTabLongPress = { index ->
                    if (tabs[index].index == TAB_DISCOVER) {
                        openImproveSnsTimeline()
                        true
                    } else {
                        false
                    }
                },
                liquidGlassBlurRadius = config.blurRadius.dp,
                dynamicGravityHighlight = config.gravityHighlight,
                itemModifier = { index ->
                    val tab = tabs[index].index
                    Modifier.graphicsLayer {
                        translationX = itemOffsets.getValue(tab).value
                        val scale = 1f + REORDER_LIFT_SCALE * itemLifts.getValue(tab).value
                        scaleX = scale
                        scaleY = scale
                    }
                },
                iconContent = { item, _, activeLayer ->
                    if (config.labelMode.showsIcon) {
                        // Masked like the labels: the active layer only shows inside the
                        // selection pill, so it always carries the filled variant (native
                        // bitmaps bake their accent in; Material icons take the layer tint).
                        NavTabBadge(item.index) {
                            NavTabIcon(item, filled = activeLayer, nativeIcons = nativeIcons)
                        }
                    }
                },
                labelContent = { item, _ ->
                    if (config.labelMode.showsText) {
                        val label = @Composable {
                            Text(
                                text = stringResource(item.labelRes),
                                fontSize = if (config.labelMode.showsIcon) 11.sp else 14.sp,
                                lineHeight = if (config.labelMode.showsIcon) 14.sp else 18.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible
                            )
                        }
                        // Without an icon the badge moves onto the label.
                        if (config.labelMode.showsIcon) label() else NavTabBadge(item.index, label)
                    }
                },
            )
        }
    }
}

/** The non-floating Material 3 bar, drawn inside WeChat's own bottom tab container. */
@Composable
private fun DockedBar(
    session: NavBarSession,
    config: NavBarConfig,
    tabs: List<NavTab>,
    nativeIcons: Map<Int, WechatTabIcons>?,
    backgroundColor: Color,
    activeColor: Color,
    inactiveColor: Color,
    itemOffsets: Map<Int, State<Float>>,
    itemLifts: Map<Int, State<Float>>,
    onTabClick: (tab: Int) -> Unit,
    onDiscoverLongPress: () -> Unit,
) {
    val selectedIndex by session.selectedPageIndexState
    val offset by session.scrollOffsetState
    val labelMode = config.labelMode
    val itemLefts = remember(tabs.size) { FloatArray(tabs.size) }

    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (labelMode == NavLabelMode.ICON_AND_TEXT) DOCKED_LABELED_HEIGHT else DOCKED_HEIGHT),
        containerColor = backgroundColor,
    ) {
        tabs.forEachIndexed { index, item ->
            val label = stringResource(item.labelRes)
            val isSelected = index == selectedIndex
            val isNext = index == selectedIndex + 1

            // Follow the finger between pager neighbours like WeChat's own tabs do.
            val tint = when {
                isSelected -> lerp(activeColor, inactiveColor, offset.coerceIn(0f, 1f))
                isNext -> lerp(inactiveColor, activeColor, offset.coerceIn(0f, 1f))
                else -> inactiveColor
            }
            val showFilled = if (offset < 0.5f) isSelected else isNext

            NavigationBarItem(
                selected = isSelected && offset < 0.5f,
                onClick = { onTabClick(item.index) },
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInWindow()
                        itemLefts[index] = position.x
                        session.customBarLayout = BarLayout(
                            left = itemLefts[0],
                            top = position.y - session.barLift,
                            height = coordinates.size.height.toFloat(),
                            step = if (tabs.size > 1) itemLefts[1] - itemLefts[0] else coordinates.size.width.toFloat(),
                            count = tabs.size,
                        )
                    }
                    .graphicsLayer {
                        translationX = itemOffsets.getValue(item.index).value
                        val scale = 1f + REORDER_LIFT_SCALE * itemLifts.getValue(item.index).value
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(if (item.index == TAB_DISCOVER) Modifier.onLongPress(onDiscoverLongPress) else Modifier),
                icon = {
                    NavTabBadge(item.index) {
                        if (labelMode.showsIcon) {
                            Crossfade(targetState = showFilled, animationSpec = tween(200), label = "navIcon") { filled ->
                                NavTabIcon(item, filled, nativeIcons, tint = tint)
                            }
                        } else {
                            Text(label, color = tint, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                },
                label = if (labelMode == NavLabelMode.ICON_AND_TEXT) {
                    { Text(label, color = tint, fontSize = 11.sp, maxLines = 1) }
                } else {
                    null
                },
                alwaysShowLabel = labelMode == NavLabelMode.ICON_AND_TEXT,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = activeColor.copy(alpha = 0.15f),
                    selectedIconColor = activeColor,
                    unselectedIconColor = inactiveColor,
                    selectedTextColor = activeColor,
                    unselectedTextColor = inactiveColor,
                ),
            )
        }
    }
}

private val DOCKED_HEIGHT = 56.dp
private val DOCKED_LABELED_HEIGHT = 64.dp

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
