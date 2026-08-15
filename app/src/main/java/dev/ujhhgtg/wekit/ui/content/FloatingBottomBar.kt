// InstallerX-Revived
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
//
// The liquid-glass branch is adapted from Kyant0/AndroidLiquidGlass
// (https://github.com/Kyant0/AndroidLiquidGlass) — Apache 2.0.
package dev.ujhhgtg.wekit.ui.content

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import dev.ujhhgtg.wekit.ui.content.animation.DampedDragAnimation
import dev.ujhhgtg.wekit.ui.content.animation.InteractiveHighlight
import dev.ujhhgtg.wekit.ui.content.liquid.InnerShadow
import dev.ujhhgtg.wekit.ui.content.liquid.innerShadow
import dev.ujhhgtg.wekit.ui.content.liquid.lens
import dev.ujhhgtg.wekit.ui.content.liquid.rememberCombinedBackdrop
import dev.ujhhgtg.wekit.ui.content.liquid.vibrancy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.material3.LocalContentColor as M3LocalContentColor
import androidx.compose.ui.graphics.shadow.Shadow as ComposeShadow

private val LocalFloatingBottomBarContentColor = staticCompositionLocalOf { Color.Unspecified }
private val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

// State class holding all colors for the bottom bar
@Immutable
class FloatingBottomBarColors(
    val containerColor: Color,
    val indicatorColor: Color,
    val contentColor: Color,
    val activeContentColor: Color
)

// Defaults object for creating the Colors instance
object FloatingBottomBarDefaults {
    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
        indicatorColor: Color = MaterialTheme.colorScheme.primary,
        contentColor: Color = MaterialTheme.colorScheme.outline,
        activeContentColor: Color = indicatorColor
    ): FloatingBottomBarColors = FloatingBottomBarColors(
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        contentColor = contentColor,
        activeContentColor = activeContentColor
    )
}

@Composable
fun <T> FloatingBottomBar(
    items: List<T>,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    // Tap on a tab item (NOT drag-release, NOT pill tap). Callers that need
    // tap-vs-switch distinction (e.g. double-tap state machines) pass a different
    // lambda here than onSelected. Pill reselection still goes to onTabReselected.
    onTabClick: (index: Int) -> Unit = onSelected,
    // Called when the already-selected tab is tapped. In blur mode the glass pill sits on
    // top of the selected tab and swallows the tap before it reaches the tab item, so the
    // item's own onClick never fires for a reselection; this forwards that tap instead.
    // Defaults to routing through onSelected so callers that don't care keep old behaviour.
    onTabReselected: (index: Int) -> Unit = onSelected,
    // Called when the already-selected tab is long-pressed. Same pill-occlusion problem as
    // onTabReselected: the glass pill eats the long-press, so the tab item's own modifier
    // never fires. Defaults to no-op so callers that don't need it are unaffected.
    onTabReselectedLongPress: (index: Int) -> Unit = {},
    // Long-press on an *unselected* tab item (the selected one is pill-occluded and
    // goes to onTabReselectedLongPress). Null disables per-item long-press.
    itemLongPress: ((index: Int) -> Unit)? = null,
    // Optional continuous position driver (e.g. a pager's fractional scroll offset,
    // 0f..items.size-1). When null the indicator springs between whole tabs as before.
    progress: (() -> Float)? = null,
    // Gate for the continuous driver. The indicator only tracks `progress` 1:1 while this
    // returns true (e.g. an active finger swipe). When it returns false, a change in
    // selectedIndex springs the indicator across with the press/glass animation — so a tab
    // *tap* still bulges and slides rather than teleporting.
    isTracking: (() -> Boolean)? = null,
    isBlurEnabled: Boolean = true,
    // Radius of the glass blur, in dp. Higher = frostier / less legible content behind the bar.
    blurRadius: Dp = 8.dp,
    colors: FloatingBottomBarColors = FloatingBottomBarDefaults.colors(),
    iconContent: @Composable (item: T, index: Int) -> Unit,
    labelContent: @Composable (item: T, index: Int) -> Unit,
) {
    val isInDark = isSystemInDarkTheme()
    val pillShape = remember { CircleShape }
    // A zero radius means "no glass at all": drop the blur, the frost tint and the lens refraction
    // so the panel is fully transparent and WeChat's content shows through untouched.
    val isGlassTransparent = isBlurEnabled && blurRadius <= 0.dp
    // The glass layer is translucent so WeChat's content shows through it. At radius 0 the surface
    // tint is removed entirely.
    val containerColor = when {
        isGlassTransparent -> Color.Transparent
        isBlurEnabled -> colors.containerColor.copy(0.4f)
        else -> colors.containerColor
    }

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex()) }

    // Kept fresh so the remembered animation and tabsContent lambdas (captured once per
    // key change) always call the latest callbacks.
    val selectedIndexUpdated by rememberUpdatedState(selectedIndex)
    val onSelectedUpdated by rememberUpdatedState(onSelected)
    val onTabClickUpdated by rememberUpdatedState(onTabClick)
    val onTabReselectedState by rememberUpdatedState(onTabReselected)
    val onTabReselectedLongPressState by rememberUpdatedState(onTabReselectedLongPress)
    val itemLongPressUpdated by rememberUpdatedState(itemLongPress)

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = currentIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            // Only start a drag when the touch lands within the tab strip bounds. Without this
            // guard the pill swallows a tap on the already-selected tab as a zero-distance drag,
            // so the tap never falls through to the item's own click handler (double-tap-home).
            // The drag modifier is attached to the pill Box, so this is effectively always true;
            // it is kept as a bounds sanity check like upstream.
            canDrag = { position ->
                position.x in 0f..totalWidthPx
            },
            // Unlike upstream, whose drag modifier sits on the base row and snaps the pill to
            // the touched tab, WeKit's drags start on the pill itself — which already rests on
            // the current tab — so there is nothing to snap and the value must not be remapped.
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onSelectedUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            // The drag was cancelled by an external event (not a normal release): spring the
            // pill back to the current tab instead of committing wherever it was dragged to.
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
            // The pill only ever rests over the selected tab, so a tap on it is a tap on the
            // current tab. Report that tab's index to the (kept-fresh) reselect callback.
            onTap = {
                val index = value.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                onTabReselectedState(index)
            },
            onLongPress = {
                val index = value.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                onTabReselectedLongPressState(index)
            }
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { selectedIndexUpdated() }.collectLatest { index ->
            if (currentIndex != index) {
                currentIndex = index
                // Spring the indicator across (press + glass bulge) whenever the selection
                // settles on a new tab and we're NOT mid finger-swipe. While tracking, the
                // progress effect below owns the position, so we only keep currentIndex (icon
                // fill / semantics) in sync here and let the final snap land it exactly.
                if (isTracking?.invoke() != true) {
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
            }
        }
    }

    if (progress != null) {
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { progress() }.collectLatest { value ->
                // Track 1:1 only during an active swipe, and never fight a pill drag.
                if (isTracking?.invoke() == true && !dampedDragAnimation.isDragging) {
                    dampedDragAnimation.snapToValue(value)
                }
            }
        }
    }

    val activateTab = remember(dampedDragAnimation) {
        { index: Int ->
            if (currentIndex != index) {
                currentIndex = index
                onSelectedUpdated(index)
            }
            dampedDragAnimation.animateToValue(index.toFloat())
        }
    }

    val tabsContent: @Composable RowScope.() -> Unit = {
        val scale = LocalFloatingBottomBarTabScale.current
        // Read the dynamic color provided by the surrounding layer.
        val contentColor = LocalFloatingBottomBarContentColor.current
        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier
                    .defaultMinSize(minWidth = 76.dp)
                    .semantics(mergeDescendants = true) {
                        selected = index == currentIndex
                        role = Role.Tab
                        onClick {
                            activateTab(index)
                            true
                        }
                    }
                    // A real touch handler in addition to the semantics onClick above: the
                    // semantics action covers accessibility activation, while this dispatches
                    // actual taps (kept separate from onSelected so callers can distinguish
                    // a tab tap from a drag-release / programmatic switch).
                    .then(
                        if (itemLongPress != null) {
                            Modifier.combinedClickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onTabClickUpdated(index) },
                                onLongClick = { itemLongPressUpdated?.invoke(index) }
                            )
                        } else {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onTabClickUpdated(index) }
                            )
                        }
                    )
                    .onKeyEvent { event ->
                        val isActivationKey = event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.Spacebar
                        if (isActivationKey) {
                            if (event.type == KeyEventType.KeyUp) activateTab(index)
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .fillMaxHeight()
                    .weight(1f)
                    .graphicsLayer {
                        val itemScale = scale()
                        scaleX = itemScale
                        scaleY = itemScale
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(M3LocalContentColor provides contentColor) {
                    iconContent(item, index)
                    labelContent(item, index)
                }
            }
        }
    }

    // The interactive touch highlight uses an AGSL RuntimeShader, so it needs API 33+.
    val interactiveHighlight =
        if (isBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            remember(animationScope, tabWidthPx) {
                InteractiveHighlight(
                    animationScope = animationScope,
                    position = { size, _ ->
                        Offset(
                            if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                            else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                            size.height / 2f
                        )
                    }
                )
            }
        } else {
            null
        }

    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        // Base layer — unselected content.
        CompositionLocalProvider(LocalFloatingBottomBarContentColor provides colors.contentColor) {
            Row(
                Modifier
                    .onGloballyPositioned { coords ->
                        totalWidthPx = coords.size.width.toFloat()
                        val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .dropShadow(
                        shape = pillShape,
                        shadow = ComposeShadow(
                            radius = 10.dp,
                            color = Color.Black,
                            alpha = if (isInDark) 0.2f else 0.1f,
                        ),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .then(
                        if (isBlurEnabled) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    if (!isGlassTransparent) {
                                        vibrancy()
                                        blur(blurRadius.toPx(), blurRadius.toPx())
                                        lens(
                                            refractionHeight = 24.dp.toPx(),
                                            refractionAmount = 24.dp.toPx(),
                                        )
                                    }
                                },
                                highlight = { if (isGlassTransparent) null else Highlight.Default.copy(alpha = 0.75f) },
                                layerBlock = {
                                    val width = size.width.coerceAtLeast(1f)
                                    val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDragAnimation.pressProgress)
                                    scaleX = s
                                    scaleY = s
                                },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        }
                    )
                    .then(interactiveHighlight?.modifier ?: Modifier)
                    .height(64.dp)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = tabsContent
            )
        }

        // Active overlay — captured into tabsBackdrop and revealed through the sliding pill.
        if (isBlurEnabled) {
            CompositionLocalProvider(
                LocalFloatingBottomBarTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
                LocalFloatingBottomBarContentColor provides colors.activeContentColor
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                if (!isGlassTransparent) {
                                    vibrancy()
                                    blur(blurRadius.toPx(), blurRadius.toPx())
                                    lens(
                                        refractionHeight = 24.dp.toPx(),
                                        refractionAmount = 24.dp.toPx(),
                                    )
                                }
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight?.modifier ?: Modifier)
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabsContent()
                }
            }
        }

        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            if (isBlurEnabled) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .then(interactiveHighlight?.gestureModifier ?: Modifier)
                        .then(dampedDragAnimation.modifier)
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    refractionHeight = 10.dp.toPx() * progress,
                                    refractionAmount = 14.dp.toPx() * progress,
                                    depthEffect = true,
                                    chromaticAberration = 0.5f,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    color = if (!isInDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                        // miuix's drawBackdrop has no innerShadow param (kyant did); apply it as a
                        // separate modifier, matching InstallerX's liquid-glass FloatingBottomBar.
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(alpha = 0.15f),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
                        .height(56.dp)
                        .width(tabWidthDp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .then(dampedDragAnimation.modifier)
                        .clip(pillShape)
                        .background(colors.indicatorColor.copy(alpha = 0.15f), pillShape)
                        .height(56.dp)
                        .width(tabWidthDp),
                    // Force start alignment for the Box container to prevent centering
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Provide the active content color to the non-blur active layer
                    CompositionLocalProvider(LocalFloatingBottomBarContentColor provides colors.activeContentColor) {
                        Row(
                            Modifier
                                .clearAndSetSemantics {}
                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                .requiredWidth(with(density) { (totalWidthPx - 8.dp.toPx()).toDp() })
                                .height(56.dp)
                                .graphicsLayer {
                                    val progressOffset = dampedDragAnimation.value * tabWidthPx
                                    translationX = if (isLtr) -progressOffset else progressOffset
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            content = tabsContent
                        )
                    }
                }
            }
        }
    }
}
