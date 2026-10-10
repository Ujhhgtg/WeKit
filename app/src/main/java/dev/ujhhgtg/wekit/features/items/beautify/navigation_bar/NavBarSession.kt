package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.tencent.mm.ui.mogic.WxViewPager
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int

private const val TAB_ICON_VIEW_CLASS = "com.tencent.mm.ui.TabIconView"
private const val FROSTED_CONTENT_VIEW_CLASS = "com.tencent.mm.ui.FrostedContentView"

data class WechatTabIcons(
    val outlined: ImageBitmap,
    val filled: ImageBitmap,
    val activeColor: Color,
    val inactiveColor: Color,
)

/** Rest geometry of the visible bar items in window pixels, ignoring the editor's lift. */
@Stable
data class BarLayout(
    val left: Float,
    val top: Float,
    val height: Float,
    val step: Float,
    val count: Int,
) {
    val right: Float get() = left + step * count
}

/** One logical tab inside WeChat's LauncherUIBottomTabView (layout by5 / by6). */
class NativeTab(val index: Int, val container: View) {
    val icon: View = (container as ViewGroup).descendants.first { it.javaClass.name == TAB_ICON_VIEW_CLASS }
    val iconBox = icon.parent as RelativeLayout
    val unread: View = iconBox.children.first { it is TextView }
    val dot: View = iconBox.children.first { it is ImageView && it !== icon }
    val label = (iconBox.parent as ViewGroup).children.first { it is TextView } as TextView
    val focusAlpha = mutableIntStateOf(icon.reflekt().fields { type = int }.single().get() as Int)
    var materialHost: ComposeView? = null

    private val setFocusAlpha = icon.reflekt().firstMethod { name = "setFocusAlpha"; parameters(int) }

    fun applyFocus(fraction: Float, activeColor: Int, inactiveColor: Int) {
        setFocusAlpha.invoke((fraction * 255).toInt())
        label.setTextColor(lerpArgb(inactiveColor, activeColor, fraction))
    }

    fun readIcons(): WechatTabIcons {
        // TabIconView draws three host-loaded Bitmaps itself; ImageView.drawable is empty.
        // Its sole declared int is the focus alpha (0 = outlined, 255 = filled). Render
        // through the host's onDraw so resource decoding and native Paint colors stay intact.
        val reflected = icon.reflekt()
        val bitmaps = reflected.fields { type = Bitmap::class }.map { it.get() as Bitmap }
        val width = bitmaps.maxOf { it.width }
        val height = bitmaps.maxOf { it.height }
        val focusAlpha = reflected.fields { type = int }.single()
        val originalAlpha = focusAlpha.get() as Int
        val draw = reflected.firstMethod { name = "onDraw"; parameters(Canvas::class) }
        fun render(alpha: Int): ImageBitmap {
            focusAlpha.set(alpha)
            val bitmap = createBitmap(width, height)
            draw.invoke(Canvas(bitmap))
            return bitmap.asImageBitmap()
        }
        return try {
            val outlined = render(0)
            // At focus alpha 0, onDraw leaves the active Paint transparent and the
            // inactive Paint opaque. Read their filters instead of resource IDs or
            // anti-aliased bitmap pixels, so the colors exactly match WeChat.
            // Read them before rendering the filled state, which makes both Paints opaque.
            val paints = reflected.fields { type = Paint::class }.map { it.get() as Paint }
            val activeColor = filterColor(paints.single { it.alpha == 0 })
            val inactiveColor = filterColor(paints.single { it.alpha != 0 })
            WechatTabIcons(outlined, render(255), activeColor, inactiveColor)
        } finally {
            focusAlpha.set(originalAlpha)
        }
    }

    // Apply the filter to an opaque white swatch: ColorFilter exposes no public color getter.
    private fun filterColor(paint: Paint): Color {
        val swatch = createBitmap(1, 1)
        Canvas(swatch).drawPaint(Paint().apply {
            color = android.graphics.Color.WHITE
            colorFilter = paint.colorFilter
        })
        return Color(swatch[0, 0]).also { swatch.recycle() }
    }
}

class NavBarSession(
    val activity: Activity,
    val mainTabUI: Any,
    val viewPager: WxViewPager,
    val tabsAdapter: Any,
    val bottomTab: ViewGroup,
) {
    val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)

    val selectedPageIndexState = mutableIntStateOf(viewPager.currentItem)
    val scrollOffsetState = mutableFloatStateOf(0f)

    // Target page as soon as it's decided: immediately on a tab tap, and at the
    // half-way crossing during a finger swipe. Drives the discrete spring so a tap
    // still bulges + slides the pill instead of teleporting.
    val targetPageIndexState = mutableIntStateOf(viewPager.currentItem)

    val reorder = NavBarReorderState()

    var customBarLayout by mutableStateOf<BarLayout?>(null)
    private var nativeBarLayout by mutableStateOf<BarLayout?>(null)

    var editor by mutableStateOf<NavBarEditor?>(null)
        private set
    val homeBarOverride = LauncherNavigation.barOverride(activity)
    private var frostedEnabled: Boolean? = null
    private var previousHomeOverride: Boolean? = null
    private var disposed = false

    private val nativeRow = bottomTab.children.first { it is LinearLayout } as LinearLayout
    val nativeTabs: Map<Int, NativeTab> = nativeRow.children.associate { container ->
        val index = container.tag as Int
        index to NativeTab(index, container)
    }

    // WeChat's own tab listener (q8) with its double-tap state machine and the LiveData
    // event it fires. Double-tapping the Chat tab makes it fire WeChat's "scroll to next
    // unread conversation" event, which MainUI already observes. Replaying two rapid
    // clicks reproduces that behaviour without resolving the obfuscated event class.
    val nativeTabClickListener = bottomTab.reflekt()
        .firstField { type = View.OnClickListener::class }
        .get() as View.OnClickListener

    private val methodOnTabClick = tabsAdapter.reflekt().firstMethod { name = "onTabClick"; parameters(int) }

    @Suppress("UNCHECKED_CAST")
    private val fragmentCache = mainTabUI.reflekt()
        .firstField { type = HashMap::class; modifiers { Modifiers.STATIC !in it } }
        .get() as HashMap<Int, Any>

    val composeBar = ComposeView(activity).apply {
        setLifecycleOwner(lifecycleOwner)
        setContent { InjectedUiTheme { NavBarCustomBar(this@NavBarSession) } }
    }

    private val frostedMethod = FROSTED_CONTENT_VIEW_CLASS.toClass().firstMethod {
        parameters { it[0] == bool && it[1] == int }
    }

    // Native colors don't change for the lifetime of this activity: a theme switch recreates it.
    private var nativeIconCache: Map<Int, WechatTabIcons>? = null

    val nativeIcons: Map<Int, WechatTabIcons>
        get() = nativeIconCache ?: nativeTabs.mapValues { it.value.readIcons() }.also { nativeIconCache = it }

    val barLayout: BarLayout?
        get() = when (BeautifyNavigationBar.config.style) {
            NavBarStyle.NATIVE -> nativeBarLayout
            NavBarStyle.DOCKED, NavBarStyle.FLOATING, NavBarStyle.LIQUID_GLASS -> customBarLayout
            NavBarStyle.HIDDEN -> null
        }

    /** The view that must stay sharp and clickable-looking above the editor veil. */
    val barView: View?
        get() = when (BeautifyNavigationBar.config.style) {
            NavBarStyle.NATIVE, NavBarStyle.DOCKED -> bottomTab
            NavBarStyle.FLOATING, NavBarStyle.LIQUID_GLASS -> composeBar
            NavBarStyle.HIDDEN -> null
        }

    init {
        nativeTabs.values.forEach { BeautifyNavigationBar.registerFocusAlpha(it.icon, it.focusAlpha) }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateNativeBarLayout() }
        bottomTab.addOnLayoutChangeListener(layoutListener)
        nativeRow.addOnLayoutChangeListener(layoutListener)
    }

    fun navigateToTab(tab: Int) {
        methodOnTabClick.invoke(tab)
    }

    fun openEditor() {
        if (editor != null) return
        editor = NavBarEditor(this).also { it.show() }
        applyHomeVisibility(resetStyle = true)
    }

    fun onEditorRemoved() {
        editor = null
        if (!disposed) applyHomeVisibility(resetStyle = true)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        editor?.remove()
        composeBar.disposeComposition()
        (composeBar.parent as? ViewGroup)?.removeView(composeBar)
        nativeTabs.values.forEach { tab ->
            tab.materialHost?.let { host ->
                host.disposeComposition()
                (host.parent as? ViewGroup)?.removeView(host)
            }
            tab.materialHost = null
        }
    }

    /** Current editor lift of the bar, in pixels (negative is up). */
    var barLift = 0f
        private set

    fun setBarLift(px: Float) {
        barLift = px
        val style = BeautifyNavigationBar.config.style
        // The docked bar lives inside the native container, so both move with it.
        bottomTab.translationY = if (style == NavBarStyle.NATIVE || style == NavBarStyle.DOCKED) px else 0f
        composeBar.translationY = if (style.isFloating) px else 0f
    }

    fun applyConfig(new: NavBarConfig) {
        mountStyle(new.style)
        applyNativeTabs(new)
        applyHomeVisibility(resetStyle = true)
        updateNativeBarLayout()
    }

    /** Home owns visibility while present; scroll auto-hide remains independent underneath. */
    fun applyHomeVisibility(resetStyle: Boolean = false) {
        val style = BeautifyNavigationBar.config.style
        // The editor is an overlay with its own bar preview, rather than the home page.
        val override = homeBarOverride.value
        val homeHidden = override == true && editor == null
        if (override != null || previousHomeOverride != null || resetStyle) {
            val nativeContainerVisible = !homeHidden && (style == NavBarStyle.NATIVE || style == NavBarStyle.DOCKED)
            val nativeVisibility = if (nativeContainerVisible) View.VISIBLE else View.GONE
            if (bottomTab.visibility != nativeVisibility) bottomTab.visibility = nativeVisibility
            // Floating bars animate the home override through the same progress as
            // scroll auto-hide. Keep their Compose host visible throughout the animation.
            val floatingVisibility = if (style.isFloating) View.VISIBLE else View.GONE
            // A docked Compose bar follows its native parent, and must remain visible inside it.
            val composeVisibility = if (style == NavBarStyle.DOCKED) View.VISIBLE else floatingVisibility
            if (composeBar.visibility != composeVisibility) composeBar.visibility = composeVisibility
        }
        // Once released, leave the host's Recent-page animation in control of its container.
        previousHomeOverride = override
        val frost = style == NavBarStyle.NATIVE && !homeHidden
        if (frostedEnabled != frost) {
            frostedEnabled = frost
            refreshFrosted(frost)
        }
    }

    private fun mountStyle(style: NavBarStyle) {
        val docked = style == NavBarStyle.DOCKED
        bottomTab.visibility = if (style == NavBarStyle.NATIVE || docked) View.VISIBLE else View.GONE
        // The docked bar takes the native tabs' place inside their own container, so it
        // inherits WeChat's insets and Recent-page hide animation. For every other non-native
        // style the tabs are hidden too: WeChat makes its container visible again on some
        // paths (e.g. returning to LauncherUI), which must not reveal the native tabs.
        nativeRow.visibility = if (style == NavBarStyle.NATIVE) View.VISIBLE else View.GONE
        when {
            docked -> attachDockedBar()
            style.isFloating -> attachFloatingBar()
            else -> (composeBar.parent as? ViewGroup)?.removeView(composeBar)
        }
    }

    private fun attachDockedBar() {
        if (composeBar.parent === bottomTab) return
        (composeBar.parent as? ViewGroup)?.removeView(composeBar)
        composeBar.z = 0f
        bottomTab.addView(
            composeBar,
            RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun attachFloatingBar() {
        // HomeSidePanel moves the pager and its siblings into contentWrapper.
        // Follow that current parent; reusing the doOnCreate parent would fight
        // absorbStrayChildren(), repeatedly detaching the bar and its gesture state.
        val contentParent = viewPager.parent as ViewGroup
        if (composeBar.parent === contentParent) return
        (composeBar.parent as? ViewGroup)?.removeView(composeBar)
        contentParent.clipChildren = false
        contentParent.clipToPadding = false
        composeBar.clipChildren = false
        composeBar.clipToPadding = false
        // Prepare the sampled RenderNodes before the pager's real traversal so its
        // SurfaceView position wins. Z ordering still paints/hit-tests the bar on top.
        composeBar.z = viewPager.z + 1f
        contentParent.addView(
            composeBar,
            contentParent.indexOfChild(viewPager),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
    }

    private fun refreshFrosted(enabled: Boolean) {
        // Pages only re-run FrostedContentView.a(...) with their current enabled flag, so
        // turning the native blur back on must be pushed explicitly once the bar has a size.
        val apply = {
            viewPager.children.filter { it.javaClass.name == FROSTED_CONTENT_VIEW_CLASS }.forEach { view ->
                if (frostedMethod.parameterTypes.size == 3) {
                    frostedMethod.invoke(view, enabled, bottomTab.height, bottomTab.translationY)
                } else {
                    frostedMethod.invoke(view, enabled, bottomTab.height)
                }
            }
        }
        if (enabled && bottomTab.height == 0) bottomTab.doOnNextLayout { apply() } else apply()
    }

    private fun applyNativeTabs(config: NavBarConfig) {
        config.order.forEachIndexed { position, tab ->
            val container = nativeTabs.getValue(tab).container
            if (nativeRow.getChildAt(position) !== container) {
                val params = container.layoutParams
                nativeRow.removeView(container)
                nativeRow.addView(container, position, params)
            }
        }
        val material = config.iconStyle == NavIconStyle.MATERIAL
        nativeTabs.values.forEach { tab ->
            tab.container.visibility = if (tab.index in config.enabledTabs) View.VISIBLE else View.GONE
            tab.iconBox.visibility = if (config.labelMode.showsIcon) View.VISIBLE else View.GONE
            tab.label.visibility = if (config.labelMode.showsText) View.VISIBLE else View.GONE
            tab.icon.visibility = if (material) View.INVISIBLE else View.VISIBLE
            if (material && tab.materialHost == null) tab.materialHost = createMaterialHost(tab)
            tab.materialHost?.visibility = if (material) View.VISIBLE else View.GONE
            // WeChat toggles badge visibility itself from many paths (counters, newtips,
            // text points); alpha is never touched by the host, so it hides all of them.
            val badgeAlpha = if (config.showsBadge(tab.index)) 1f else 0f
            tab.unread.alpha = badgeAlpha
            tab.dot.alpha = badgeAlpha
        }
        // A reordered bar breaks WeChat's own logical-neighbour color interpolation.
        syncNativeFocus(viewPager.currentItem, 0f)
    }

    private fun createMaterialHost(tab: NativeTab): ComposeView {
        val icons = nativeIcons.getValue(tab.index)
        val item = navTab(tab.index)
        val host = ComposeView(activity).apply {
            setLifecycleOwner(lifecycleOwner)
            setContent {
                val focus by tab.focusAlpha
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val fraction = focus / 255f
                    Icon(
                        item.outlined,
                        contentDescription = null,
                        tint = icons.inactiveColor,
                        modifier = Modifier
                            .fillMaxSize(0.86f)
                            .graphicsLayer { alpha = 1f - fraction },
                    )
                    Icon(
                        item.filled,
                        contentDescription = null,
                        tint = icons.activeColor,
                        modifier = Modifier
                            .fillMaxSize(0.86f)
                            .graphicsLayer { alpha = fraction },
                    )
                }
            }
        }
        val params = RelativeLayout.LayoutParams(tab.icon.layoutParams.width, tab.icon.layoutParams.height)
            .apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
        // Directly above the icon, below the unread counter and the dot.
        tab.iconBox.addView(host, tab.iconBox.indexOfChild(tab.icon) + 1, params)
        return host
    }

    /** Re-applies WeChat's page-scroll highlight between pager neighbours, not logical ones. */
    fun syncNativeFocus(pagerPosition: Int, offset: Float) {
        if (BeautifyNavigationBar.config.style != NavBarStyle.NATIVE) return
        val pagerTabs = BeautifyNavigationBar.pagerTabs
        val icons = nativeIcons
        val current = pagerTabs.getOrNull(pagerPosition) ?: return
        val next = pagerTabs.getOrNull(pagerPosition + 1)
        nativeTabs.values.forEach { tab ->
            val fraction = when (tab.index) {
                current -> 1f - offset
                next -> offset
                else -> 0f
            }
            val colors = icons.getValue(tab.index)
            tab.applyFocus(fraction, colors.activeColor.toArgb(), colors.inactiveColor.toArgb())
        }
    }

    private fun updateNativeBarLayout() {
        val first = nativeRow.children.firstOrNull { it.isVisible && it.width > 0 }
        if (first == null) {
            nativeBarLayout = null
            return
        }
        val location = IntArray(2)
        nativeRow.getLocationInWindow(location)
        nativeBarLayout = BarLayout(
            left = location[0] + first.left.toFloat(),
            top = location[1] + first.top - bottomTab.translationY,
            height = first.height.toFloat(),
            step = first.width.toFloat(),
            count = nativeRow.children.count { it.isVisible },
        )
    }

    // ---------- live page set ----------

    fun fragmentTab(fragment: Any): Int? = fragmentCache.entries.firstOrNull { it.value === fragment }?.key

    fun switchAwayFrom(oldTabs: List<Int>, newTabs: List<Int>) {
        val position = viewPager.currentItem
        if (oldTabs[position] in newTabs) return
        val fallback = (oldTabs.subList(position + 1, oldTabs.size) + oldTabs.subList(0, position).asReversed())
            .first { it in newTabs }
        BeautifyNavigationBar.changeTab(mainTabUI, fallback)
    }

    /**
     * Rebuilds FragmentStatePagerAdapter's position-indexed bookkeeping for [BeautifyNavigationBar.pagerTabs].
     *
     * Hidden pages keep their fragment added (only its view is GONE), so showing them again
     * reuses the live instance instead of re-adding a removed fragment or restoring saved
     * state into a shifted position.
     */
    @Suppress("UNCHECKED_CAST")
    fun syncPager() {
        val tabs = BeautifyNavigationBar.pagerTabs
        val adapter = tabsAdapter.reflekt()
        val fragments = adapter.getField("mFragments", superclass = true) as ArrayList<Any?>
        val savedStates = adapter.getField("mSavedState", superclass = true) as ArrayList<Any?>
        fragments.clear()
        tabs.forEach { tab -> fragments += fragmentCache[tab]?.takeIf(::isFragmentAdded) }
        savedStates.clear()
        fragmentCache.forEach { (tab, fragment) ->
            fragmentView(fragment)?.visibility = if (tab in tabs) View.VISIBLE else View.GONE
        }
        adapter.firstMethod { name = "notifyDataSetChanged"; superclass() }.invoke()
    }

    private fun isFragmentAdded(fragment: Any): Boolean =
        fragment.reflekt().firstMethod { name = "isAdded"; superclass() }.invoke() as Boolean

    fun fragmentView(fragment: Any): View? =
        fragment.reflekt().firstMethod { name = "getView"; superclass() }.invoke() as View?
}

private fun lerpArgb(start: Int, stop: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val a = (start shr shift) and 0xFF
        val b = (stop shr shift) and 0xFF
        return (a + (b - a) * f).toInt() and 0xFF
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
