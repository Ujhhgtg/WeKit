package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

object HomeSidePanelPreferenceKeys {
    const val LAYOUT = "home_side_panel_layout"
    const val CARD_WEATHER_CACHE_PREFIX = "home_side_panel_card_weather_cache_"
    const val CARD_HITOKOTO_CACHE_PREFIX = "home_side_panel_card_hitokoto_cache_"
    const val WEATHER_CITY = "home_side_panel_weather_city"
    const val WEATHER_LAST_SUCCESS = "home_side_panel_weather_last_success"
    const val HITOKOTO_SETTINGS = "home_side_panel_hitokoto_settings"
    const val HITOKOTO_LAST_SUCCESS = "home_side_panel_hitokoto_last_success"
    const val SHOW_TOOLBAR_PROFILE = "home_side_panel_show_toolbar_profile"
    const val HIDE_WECHAT_TITLE = "home_side_panel_hide_wechat_title"
    const val HIDE_WALLET_BALANCE = "home_side_panel_hide_wallet_balance"
}

object HomeSidePanelPreferences {

    private const val TAG = "HomeSidePanelPreferences"

    var showToolbarProfile by prefOption(HomeSidePanelPreferenceKeys.SHOW_TOOLBAR_PROFILE, true)
    var hideWeChatTitle by prefOption(HomeSidePanelPreferenceKeys.HIDE_WECHAT_TITLE, false)

    var layoutRaw: String?
        get() = WePrefs.getString(HomeSidePanelPreferenceKeys.LAYOUT)
        set(value) {
            if (value == null) {
                WePrefs.remove(HomeSidePanelPreferenceKeys.LAYOUT)
            } else {
                WePrefs.putString(HomeSidePanelPreferenceKeys.LAYOUT, value)
            }
        }

    val legacySelectedWeatherCity: WeatherCity
        get() = decode(HomeSidePanelPreferenceKeys.WEATHER_CITY) ?: DEFAULT_WEATHER_CITY

    val legacyHideWalletBalance: Boolean
        get() = WePrefs.getBoolOrDef(HomeSidePanelPreferenceKeys.HIDE_WALLET_BALANCE, false)

    val legacyWeatherLastSuccess: WeatherSnapshot?
        get() = decode(HomeSidePanelPreferenceKeys.WEATHER_LAST_SUCCESS)

    val legacyHitokotoSettings: HitokotoSettings
        get() = decode(HomeSidePanelPreferenceKeys.HITOKOTO_SETTINGS) ?: HitokotoSettings()

    val legacyHitokotoLastSuccess: HitokotoSnapshot?
        get() = decode(HomeSidePanelPreferenceKeys.HITOKOTO_LAST_SUCCESS)

    private inline fun <reified T> decode(key: String): T? {
        val raw = WePrefs.getString(key) ?: return null
        return runCatching { DefaultJson.decodeFromString<T>(raw) }
            .onFailure { WeLogger.w(TAG, "failed to decode preference $key", it) }
            .getOrNull()
    }

}
