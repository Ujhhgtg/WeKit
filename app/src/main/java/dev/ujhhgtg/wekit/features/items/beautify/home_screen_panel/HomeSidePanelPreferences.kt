package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.data.KvStore.prefOption

object HomeSidePanelPreferenceKeys {
    const val LAYOUT = "home_side_panel_layout"
    const val CARD_WEATHER_CACHE_PREFIX = "home_side_panel_card_weather_cache_"
    const val CARD_HITOKOTO_CACHE_PREFIX = "home_side_panel_card_hitokoto_cache_"
    const val SHOW_TOOLBAR_PROFILE = "home_side_panel_show_toolbar_profile"
    const val HIDE_WECHAT_TITLE = "home_side_panel_hide_wechat_title"
}

object HomeSidePanelPreferences {

    var showToolbarProfile by prefOption(HomeSidePanelPreferenceKeys.SHOW_TOOLBAR_PROFILE, true)
    var hideWeChatTitle by prefOption(HomeSidePanelPreferenceKeys.HIDE_WECHAT_TITLE, false)

    var layoutRaw: String?
        get() = KvStore.getString(HomeSidePanelPreferenceKeys.LAYOUT)
        set(value) {
            if (value == null) {
                KvStore.remove(HomeSidePanelPreferenceKeys.LAYOUT)
            } else {
                KvStore.putString(HomeSidePanelPreferenceKeys.LAYOUT, value)
            }
        }
}
