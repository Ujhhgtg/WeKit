package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import kotlin.math.roundToInt

enum class EditorPage { SETTINGS, PAGES }

private const val VEIL_DIM_ALPHA = 0.32f
private val VEIL_BLUR_RADIUS = 24.dp
private val CARD_BAR_GAP = 16.dp
private val SCREEN_MARGIN = 24.dp

/**
 * In-place bar editor on LauncherUI. It is a plain view on the decor, not a Dialog: a
 * dialog window would take focus from WeChat and could not keep the bar drawn above its
 * veil. Everything except the bar is blurred, dimmed and made untouchable.
 */
class NavBarEditor(private val session: NavBarSession) {

    private val decor = session.activity.window.decorView as ViewGroup
    private val view = ComposeView(session.activity)
    val veil = ContentVeil(decor, view)

    var page by mutableStateOf(EditorPage.SETTINGS)
    var visible by mutableStateOf(false)
        private set

    fun show() {
        view.setLifecycleOwner(session.lifecycleOwner)
        view.setContent { InjectedUiTheme { NavBarEditorOverlay(this, session) } }
        decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        visible = true
    }

    fun onBack() {
        if (page == EditorPage.PAGES) page = EditorPage.SETTINGS else dismiss()
    }

    fun dismiss() {
        visible = false
    }

    /** Called once the exit animation has finished. */
    fun remove() {
        veil.clear()
        session.reorder.reset()
        session.setBarLift(0f)
        // Detaching disposes this composition; never do it from inside its own frame.
        view.post { decor.removeView(view) }
        session.onEditorRemoved()
    }
}

/** Blurs and dims every view outside the chain that leads from the decor to the bar. */
class ContentVeil(private val decor: ViewGroup, private val overlay: View) {

    private val targets = mutableListOf<View>()

    // Below Android 12 there is no RenderEffect: dim through a foreground instead. From 12 on
    // the dim joins the blur effect, because the liquid-glass backdrop records the pager with
    // View.draw(), which would pick up a foreground but never a RenderEffect.
    private val dims = HashMap<View, ColorDrawable>()
    private val originalForegrounds = HashMap<View, Drawable?>()
    private var progress = 0f
    private var blurRadiusPx = 0f

    fun retarget(sharp: View?, blurRadiusPx: Float) {
        val current = progress
        clear()
        this.blurRadiusPx = blurRadiusPx
        if (sharp == null || !sharp.isAttachedToWindow) {
            decor.children.filterTo(targets) { it !== overlay }
        } else {
            var child: View = sharp
            while (child !== decor) {
                val parent = child.parent as ViewGroup
                val kept = child
                parent.children.filterTo(targets) { it !== kept && it !== overlay }
                child = parent
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            targets.forEach { target ->
                originalForegrounds[target] = target.foreground
                val dim = ColorDrawable(0)
                dims[target] = dim
                target.foreground = dim
            }
        }
        apply(current)
    }

    fun apply(progress: Float) {
        this.progress = progress
        val dimAlpha = (VEIL_DIM_ALPHA * progress * 255).roundToInt()
        val dimColor = android.graphics.Color.argb(dimAlpha, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = blurRadiusPx * progress
            // Skia folds a no-op filter (fully transparent SRC_ATOP) or blur to null, and
            // RenderEffect then throws "nativePtr is null": only build the effects that act.
            val dim = if (dimAlpha > 0) {
                RenderEffect.createColorFilterEffect(PorterDuffColorFilter(dimColor, PorterDuff.Mode.SRC_ATOP))
            } else {
                null
            }
            val blur = if (radius >= 1f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            val effect = if (dim != null && blur != null) RenderEffect.createChainEffect(dim, blur) else dim ?: blur
            targets.forEach { it.setRenderEffect(effect) }
        } else {
            dims.values.forEach { it.color = dimColor }
        }
    }

    fun clear() {
        targets.forEach { target ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.setRenderEffect(null)
            } else if (target.foreground === dims[target]) {
                target.foreground = originalForegrounds[target]
            }
        }
        targets.clear()
        dims.clear()
        originalForegrounds.clear()
    }
}

@Composable
private fun NavBarEditorOverlay(editor: NavBarEditor, session: NavBarSession) {
    val config by BeautifyNavigationBar.configState
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val appear = remember { Animatable(0f) }
    LaunchedEffect(editor.visible) {
        appear.animateTo(
            if (editor.visible) 1f else 0f,
            tween(if (editor.visible) 280 else 200, easing = FastOutSlowInEasing),
        )
        if (!editor.visible) editor.remove()
    }

    // The bar view changes with the style; mountStyle has already run by the time the
    // new configuration recomposes this overlay.
    val barView = session.barView
    val blurRadiusPx = with(density) { VEIL_BLUR_RADIUS.toPx() }
    DisposableEffect(barView) {
        editor.veil.retarget(barView, blurRadiusPx)
        onDispose { }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { appear.value }.collect { editor.veil.apply(it) }
    }

    if (config.style == NavBarStyle.NATIVE) NativeBarReorderEffects(session, config)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Any pointer input node makes this view consume the touch, so nothing under
            // the veil (including the bar) reacts while editing.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .pointerInput(editor.page) {
                if (editor.page != EditorPage.PAGES) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        val layout = session.barLayout ?: return@detectDragGesturesAfterLongPress
                        val top = layout.top + session.barLift
                        if (position.y !in top..top + layout.height || position.x !in layout.left..layout.right) {
                            return@detectDragGesturesAfterLongPress
                        }
                        val current = BeautifyNavigationBar.config
                        val slot = ((position.x - layout.left) / layout.step).toInt().coerceIn(0, layout.count - 1)
                        val tab = current.visibleTabs[slot]
                        session.reorder.start(tab, ReorderSource.BAR, current.order.indexOf(tab))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        val reorder = session.reorder
                        val tab = reorder.draggedTab
                        val layout = session.barLayout
                        if (tab == null || reorder.source != ReorderSource.BAR || layout == null) {
                            return@detectDragGesturesAfterLongPress
                        }
                        change.consume()
                        reorder.fingerOffset += amount.x
                        val current = BeautifyNavigationBar.config
                        val visible = current.visibleTabs
                        val from = visible.indexOf(tab)
                        val slot = reorderTarget(from, reorder.fingerOffset, layout.step, visible.lastIndex)
                        // Dropping onto a neighbour's slot takes its place in the full order,
                        // which also keeps hidden pages where they were relative to it.
                        val target = current.order.indexOf(visible[slot])
                        if (target != reorder.targetIndex) {
                            reorder.targetIndex = target
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    onDragEnd = {
                        if (session.reorder.source == ReorderSource.BAR) commitReorder(session)
                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    },
                    onDragCancel = {
                        if (session.reorder.source == ReorderSource.BAR) session.reorder.reset()
                    },
                )
            },
    ) {
        val screenHeight = constraints.maxHeight.toFloat()
        val barLayout = session.barLayout
        val gapPx = with(density) { CARD_BAR_GAP.toPx() }
        val marginPx = with(density) { SCREEN_MARGIN.toPx() }
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val topReserved = with(density) { topInset.toPx() } + marginPx
        var cardHeight by remember { mutableIntStateOf(0) }

        // Page management lifts the bar to sit right below the card and centres both together.
        val liftTarget = if (editor.page == EditorPage.PAGES && barLayout != null && editor.visible) 1f else 0f
        val lift by animateFloatAsState(liftTarget, tween(360, easing = FastOutSlowInEasing), label = "barLift")
        val barHeight = barLayout?.height ?: 0f
        val cardShift = -(gapPx + barHeight) / 2f * lift
        val barLift = if (barLayout == null) 0f else {
            val groupTop = (screenHeight - (cardHeight + gapPx + barHeight)) / 2f
            (groupTop + cardHeight + gapPx - barLayout.top) * lift
        }
        SideEffect { session.setBarLift(barLift) }

        val bottomReserved = barLayout?.let { screenHeight - it.top + gapPx }
            ?: (with(density) { bottomInset.toPx() } + marginPx)
        val maxCardHeightPx = if (editor.page == EditorPage.PAGES) {
            screenHeight - gapPx - barHeight - 2 * topReserved
        } else {
            screenHeight - 2 * maxOf(topReserved, bottomReserved)
        }

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(0, cardShift.roundToInt()) }
                .padding(horizontal = SCREEN_MARGIN)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .onSizeChanged { cardHeight = it.height }
                .graphicsLayer {
                    alpha = appear.value
                    val scale = 0.92f + 0.08f * appear.value
                    scaleX = scale
                    scaleY = scale
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.heightIn(max = with(density) { maxCardHeightPx.coerceAtLeast(0f).toDp() })) {
                EditorHeader(editor)
                AnimatedContent(
                    targetState = editor.page,
                    transitionSpec = { editorPageTransform(push = targetState == EditorPage.PAGES) },
                    label = "navBarEditorPage",
                ) { page ->
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        when (page) {
                            EditorPage.SETTINGS -> SettingsPage(editor, config)
                            EditorPage.PAGES -> PageManagementPage(session, config)
                        }
                    }
                }
            }
        }
    }
}

// Same push/pop motion as HomeSidePanel's settings pages.
private fun editorPageTransform(push: Boolean): ContentTransform = if (push) {
    (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { it / 4 })
        .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -it / 8 })
} else {
    (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { -it / 8 })
        .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { it / 4 })
}

@Composable
private fun EditorHeader(editor: NavBarEditor) {
    val inPages = editor.page == EditorPage.PAGES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = inPages,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            IconButton(onClick = { editor.page = EditorPage.SETTINGS }) {
                Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription = stringResource(R.string.action_back))
            }
        }
        Text(
            text = stringResource(if (inPages) R.string.nav_page_management else R.string.feature_beautify_navigation_bar_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (inPages) 4.dp else 12.dp),
        )
        TextButton(onClick = editor::dismiss) { Text(stringResource(R.string.action_done)) }
    }
}

@Composable
private fun SettingsPage(editor: NavBarEditor, config: NavBarConfig) {
    val style = config.style
    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
        item {
            DropDownMenuWidget(
                title = stringResource(R.string.nav_bar_style),
                description = null,
                value = style,
                options = NavBarStyle.entries.map { DropdownOption(it, stringResource(it.labelRes)) },
                onValueChange = { value -> BeautifyNavigationBar.updateConfig { copy(style = value) } },
            )
        }
        item(animatedVisibility = style != NavBarStyle.HIDDEN) {
            DropDownMenuWidget(
                title = stringResource(R.string.nav_icon_style),
                description = null,
                value = config.iconStyle,
                options = NavIconStyle.entries.map { DropdownOption(it, stringResource(it.labelRes)) },
                onValueChange = { value -> BeautifyNavigationBar.updateConfig { copy(iconStyle = value) } },
            )
        }
        item(animatedVisibility = style != NavBarStyle.HIDDEN) {
            DropDownMenuWidget(
                title = stringResource(R.string.nav_label_content),
                description = null,
                value = config.labelMode,
                options = NavLabelMode.entries.map { DropdownOption(it, stringResource(it.labelRes)) },
                onValueChange = { value -> BeautifyNavigationBar.updateConfig { copy(labelMode = value) } },
            )
        }
        item {
            BaseWidget(
                title = stringResource(R.string.nav_page_management),
                description = stringResource(R.string.nav_page_management_summary),
                onClick = { editor.page = EditorPage.PAGES },
                trailingContent = {
                    Icon(
                        MaterialSymbols.Outlined.Chevron_right,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        item(animatedVisibility = style.isFloating) {
            SwitchWidget(
                title = stringResource(R.string.nav_auto_hide_bar),
                description = stringResource(R.string.nav_auto_hide_bar_summary),
                checked = config.autoHide,
                onCheckedChange = { value -> BeautifyNavigationBar.updateConfig { copy(autoHide = value) } },
            )
        }
        item(animatedVisibility = style == NavBarStyle.LIQUID_GLASS) {
            BaseItemContainer {
                IntNumberPickerWidget(
                    title = stringResource(R.string.nav_blur_radius),
                    value = config.blurRadius,
                    startInt = NavBarPrefs.MIN_BLUR_RADIUS,
                    endInt = NavBarPrefs.MAX_BLUR_RADIUS,
                    stepSize = 1,
                    valueSuffix = "px",
                    onValueChange = { value -> BeautifyNavigationBar.updateConfig { copy(blurRadius = value) } },
                )
            }
        }
        item(animatedVisibility = style.usesComposeBar) {
            BaseItemContainer {
                IntNumberPickerWidget(
                    title = stringResource(R.string.nav_bar_scale),
                    value = config.scalePercent,
                    startInt = NavBarPrefs.MIN_BAR_SCALE,
                    endInt = NavBarPrefs.MAX_BAR_SCALE,
                    stepSize = NavBarPrefs.BAR_SCALE_STEP,
                    valueSuffix = "%",
                    onValueChange = { value -> BeautifyNavigationBar.updateConfig { copy(scalePercent = value) } },
                )
            }
        }
        item(animatedVisibility = style != NavBarStyle.HIDDEN) {
            SwitchWidget(
                title = stringResource(R.string.nav_page_animation),
                description = stringResource(R.string.nav_page_animation_summary),
                checked = config.animatePageChange,
                onCheckedChange = { value -> BeautifyNavigationBar.updateConfig { copy(animatePageChange = value) } },
            )
        }
    }
}

fun commitReorder(session: NavBarSession) {
    val moved = session.reorder.finish(BeautifyNavigationBar.config.order) ?: return
    BeautifyNavigationBar.updateConfig { copy(order = moved) }
}

/** Drives the real WeChat tab views the same way NavBarCustomBar offsets its Compose tabs. */
@Composable
private fun NativeBarReorderEffects(session: NavBarSession, config: NavBarConfig) {
    val step = session.barLayout?.step ?: 0f
    val liftZ = with(LocalDensity.current) { 8.dp.toPx() }
    config.visibleTabs.forEachIndexed { slot, tab ->
        key(tab) {
            val reorder = session.reorder
            val offset = rememberReorderOffset(
                slot = slot,
                step = step,
                target = reorderShift(reorder, config.order, { it in config.enabledTabs }, tab, step, ReorderSource.BAR),
                followFinger = reorder.follows(tab, ReorderSource.BAR),
            )
            val lift = rememberReorderLift(reorder.draggedTab == tab)
            val container = session.nativeTabs.getValue(tab).container
            LaunchedEffect(container) {
                snapshotFlow { offset.value to lift.value }.collect { (dx, lifted) ->
                    container.translationX = dx
                    container.translationZ = liftZ * lifted
                    container.scaleX = 1f + REORDER_LIFT_SCALE * lifted
                    container.scaleY = 1f + REORDER_LIFT_SCALE * lifted
                }
            }
            DisposableEffect(container) {
                onDispose {
                    container.translationX = 0f
                    container.translationZ = 0f
                    container.scaleX = 1f
                    container.scaleY = 1f
                }
            }
        }
    }
}
