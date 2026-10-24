package dev.ujhhgtg.wekit.features.items.beautify.navigation_bar

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.KvStore

// WeChat's logical tab indices; MainTabUI, its fragment cache and the native tab tags all use them.
const val TAB_HOME = 0
const val TAB_CONTACTS = 1
const val TAB_DISCOVER = 2
const val TAB_ME = 3

enum class NavBarStyle(@StringRes val labelRes: Int) {
    NATIVE(R.string.nav_bar_style_native),
    DOCKED(R.string.nav_bar_style_docked),
    FLOATING(R.string.nav_bar_style_floating),
    LIQUID_GLASS(R.string.nav_bar_style_liquid_glass),
    HIDDEN(R.string.nav_bar_style_hidden);

    val isFloating: Boolean
        get() = this == FLOATING || this == LIQUID_GLASS

    /** Drawn by WeKit's Compose bar rather than WeChat's own tab views. */
    val usesComposeBar: Boolean
        get() = isFloating || this == DOCKED
}

enum class NavIconStyle(@StringRes val labelRes: Int) {
    NATIVE(R.string.nav_icon_style_native),
    MATERIAL(R.string.nav_icon_style_material),
}

enum class NavLabelMode(@StringRes val labelRes: Int) {
    ICON_AND_TEXT(R.string.nav_label_icon_and_text),
    ICON(R.string.nav_label_icon),
    TEXT(R.string.nav_label_text);

    val showsIcon: Boolean
        get() = this != TEXT

    val showsText: Boolean
        get() = this != ICON
}

data class NavTab(
    val index: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
    @StringRes val labelRes: Int,
) {
    // WeChat never shows a meaningful badge on the Me tab, so it has no badge toggle.
    val supportsBadge: Boolean
        get() = index != TAB_ME
}

val NAV_TABS =
    listOf(
        NavTab(
            TAB_HOME,
            MaterialSymbols.Outlined.Home,
            MaterialSymbols.OutlinedFilled.Home,
            R.string.nav_tab_home,
        ),
        NavTab(
            TAB_CONTACTS,
            MaterialSymbols.Outlined.Contacts,
            MaterialSymbols.OutlinedFilled.Contacts,
            R.string.nav_tab_contacts,
        ),
        NavTab(
            TAB_DISCOVER,
            MaterialSymbols.Outlined.Explore,
            MaterialSymbols.OutlinedFilled.Explore,
            R.string.nav_tab_discover,
        ),
        NavTab(
            TAB_ME,
            MaterialSymbols.Outlined.Person,
            MaterialSymbols.OutlinedFilled.Person,
            R.string.nav_tab_me,
        ),
    )

fun navTab(index: Int): NavTab = NAV_TABS[index]

@Immutable
data class NavBarConfig(
    val style: NavBarStyle,
    val iconStyle: NavIconStyle,
    val labelMode: NavLabelMode,
    /** All four logical tabs, in display order. */
    val order: List<Int>,
    val enabledTabs: Set<Int>,
    val badgeTabs: Set<Int>,
    val autoHide: Boolean,
    val blurRadius: Int,
    val gravityHighlight: Boolean,
    val scalePercent: Int,
    val animatePageChange: Boolean,
) {
    val visibleTabs: List<Int>
        get() = order.filter { it in enabledTabs }

    fun showsBadge(tab: Int): Boolean = navTab(tab).supportsBadge && tab in badgeTabs

    fun normalized(): NavBarConfig {
        val validOrder = order.filter { it in NAV_TABS.indices }.distinct().toMutableList()
        NAV_TABS.forEach { if (it.index !in validOrder) validOrder += it.index }
        val validEnabled = enabledTabs.filterTo(linkedSetOf()) { it in NAV_TABS.indices }
        return copy(
            order = validOrder,
            enabledTabs = validEnabled.ifEmpty { NAV_TABS.mapTo(linkedSetOf(), NavTab::index) },
            badgeTabs = badgeTabs.filterTo(linkedSetOf()) { it in NAV_TABS.indices },
            blurRadius =
                blurRadius.coerceIn(NavBarPrefs.MIN_BLUR_RADIUS, NavBarPrefs.MAX_BLUR_RADIUS),
            scalePercent =
                scalePercent.coerceIn(NavBarPrefs.MIN_BAR_SCALE, NavBarPrefs.MAX_BAR_SCALE),
        )
    }
}

object NavBarPrefs {

    const val MIN_BLUR_RADIUS = 0
    const val MAX_BLUR_RADIUS = 40
    const val MIN_BAR_SCALE = 50
    const val MAX_BAR_SCALE = 150
    const val BAR_SCALE_STEP = 5

    private const val KEY_STYLE = "nav_bar_style"
    private const val KEY_ICON_STYLE = "nav_bar_icon_style"
    private const val KEY_LABEL_MODE = "nav_bar_label_mode"
    private const val KEY_BADGE_TABS = "nav_bar_badge_tabs"
    private const val KEY_AUTO_HIDE = "nav_bar_auto_hide_on_scroll"
    private const val KEY_BLUR_RADIUS = "nav_bar_blur_radius"
    private const val KEY_GRAVITY_HIGHLIGHT = "nav_bar_dynamic_gravity_highlight"
    private const val KEY_SCALE = "nav_bar_scale"
    private const val KEY_ANIMATE_PAGE_CHANGE = "nav_bar_animate_page_change"
    private const val KEY_TAB_ORDER = "nav_bar_tab_order"
    private const val KEY_ENABLED_TABS = "nav_bar_enabled_tabs"

    fun load(): NavBarConfig {
        return NavBarConfig(
                style =
                    enumValueOf(KvStore.getStringOrDef(KEY_STYLE, NavBarStyle.LIQUID_GLASS.name)),
                iconStyle =
                    enumValueOf(KvStore.getStringOrDef(KEY_ICON_STYLE, NavIconStyle.NATIVE.name)),
                labelMode =
                    enumValueOf(
                        KvStore.getStringOrDef(KEY_LABEL_MODE, NavLabelMode.ICON_AND_TEXT.name),
                    ),
                order =
                    KvStore.getStringOrDef(KEY_TAB_ORDER, "")
                        .split(",")
                        .mapNotNull(String::toIntOrNull),
                enabledTabs =
                    KvStore.getStringSetOrDef(KEY_ENABLED_TABS, allTabs())
                        .mapNotNullTo(linkedSetOf(), String::toIntOrNull),
                badgeTabs =
                    KvStore.getStringSetOrDef(KEY_BADGE_TABS, defaultBadgeTabs())
                        .mapNotNullTo(linkedSetOf(), String::toIntOrNull),
                autoHide = KvStore.getBoolOrDef(KEY_AUTO_HIDE, false),
                blurRadius = KvStore.getIntOrDef(KEY_BLUR_RADIUS, 8),
                gravityHighlight = KvStore.getBoolOrDef(KEY_GRAVITY_HIGHLIGHT, false),
                scalePercent = KvStore.getIntOrDef(KEY_SCALE, 100),
                animatePageChange = KvStore.getBoolOrDef(KEY_ANIMATE_PAGE_CHANGE, true),
            )
            .normalized()
    }

    fun save(config: NavBarConfig) {
        KvStore.putString(KEY_STYLE, config.style.name)
        KvStore.putString(KEY_ICON_STYLE, config.iconStyle.name)
        KvStore.putString(KEY_LABEL_MODE, config.labelMode.name)
        KvStore.putString(KEY_TAB_ORDER, config.order.joinToString(","))
        KvStore.putStringSet(
            KEY_ENABLED_TABS,
            config.enabledTabs.mapTo(linkedSetOf(), Int::toString),
        )
        KvStore.putStringSet(KEY_BADGE_TABS, config.badgeTabs.mapTo(linkedSetOf(), Int::toString))
        KvStore.putBool(KEY_AUTO_HIDE, config.autoHide)
        KvStore.putInt(KEY_BLUR_RADIUS, config.blurRadius)
        KvStore.putBool(KEY_GRAVITY_HIGHLIGHT, config.gravityHighlight)
        KvStore.putInt(KEY_SCALE, config.scalePercent)
        KvStore.putBool(KEY_ANIMATE_PAGE_CHANGE, config.animatePageChange)
    }

    private fun allTabs(): Set<String> = NAV_TABS.mapTo(linkedSetOf()) { it.index.toString() }

    private fun defaultBadgeTabs(): Set<String> =
        NAV_TABS.filter(NavTab::supportsBadge).mapTo(linkedSetOf()) { it.index.toString() }
}
