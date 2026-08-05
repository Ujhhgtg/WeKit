package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

internal object HomeSidePanelPreferenceKeys {
    const val WEATHER_CITY = "home_side_panel_weather_city"
    const val WEATHER_LAST_SUCCESS = "home_side_panel_weather_last_success"
    const val WEATHER_PROFILE_INITIALIZED = "home_side_panel_weather_profile_initialized"
    const val WEATHER_PROFILE_ACCOUNT = "home_side_panel_weather_profile_account"
    const val WEATHER_LAST_ERROR = "home_side_panel_weather_last_error"
    const val HITOKOTO_SETTINGS = "home_side_panel_hitokoto_settings"
    const val HITOKOTO_LAST_SUCCESS = "home_side_panel_hitokoto_last_success"
    const val SHOW_TOOLBAR_PROFILE = "home_side_panel_show_toolbar_profile"
    const val HIDE_WECHAT_TITLE = "home_side_panel_hide_wechat_title"
    const val HIDE_WALLET_BALANCE = "home_side_panel_hide_wallet_balance"
}

internal object HomeSidePanelPreferences {

    private const val TAG = "HomeSidePanelPreferences"

    var showToolbarProfile by prefOption(HomeSidePanelPreferenceKeys.SHOW_TOOLBAR_PROFILE, true)
    var hideWeChatTitle by prefOption(HomeSidePanelPreferenceKeys.HIDE_WECHAT_TITLE, false)
    var hideWalletBalance by prefOption(HomeSidePanelPreferenceKeys.HIDE_WALLET_BALANCE, true)

    var selectedWeatherCity: WeatherCity
        get() = decode(HomeSidePanelPreferenceKeys.WEATHER_CITY) ?: DEFAULT_WEATHER_CITY
        set(value) = encode(HomeSidePanelPreferenceKeys.WEATHER_CITY, value)

    var weatherLastSuccess: WeatherSnapshot?
        get() = decode(HomeSidePanelPreferenceKeys.WEATHER_LAST_SUCCESS)
        set(value) = setNullable(HomeSidePanelPreferenceKeys.WEATHER_LAST_SUCCESS, value)

    var weatherProfileInitialized: Boolean
        get() = WePrefs.getBoolOrDef(HomeSidePanelPreferenceKeys.WEATHER_PROFILE_INITIALIZED, false)
        set(value) {
            WePrefs.putBool(HomeSidePanelPreferenceKeys.WEATHER_PROFILE_INITIALIZED, value)
        }

    var weatherLastError: String?
        get() = WePrefs.getString(HomeSidePanelPreferenceKeys.WEATHER_LAST_ERROR)
        set(value) {
            if (value == null) {
                WePrefs.remove(HomeSidePanelPreferenceKeys.WEATHER_LAST_ERROR)
            } else {
                WePrefs.putString(HomeSidePanelPreferenceKeys.WEATHER_LAST_ERROR, value)
            }
        }

    var hitokotoSettings: HitokotoSettings
        get() = decode(HomeSidePanelPreferenceKeys.HITOKOTO_SETTINGS) ?: HitokotoSettings()
        set(value) = encode(HomeSidePanelPreferenceKeys.HITOKOTO_SETTINGS, value)

    var hitokotoLastSuccess: HitokotoSnapshot?
        get() = decode(HomeSidePanelPreferenceKeys.HITOKOTO_LAST_SUCCESS)
        set(value) = setNullable(HomeSidePanelPreferenceKeys.HITOKOTO_LAST_SUCCESS, value)

    private inline fun <reified T> decode(key: String): T? {
        val raw = WePrefs.getString(key) ?: return null
        return runCatching { DefaultJson.decodeFromString<T>(raw) }
            .onFailure { WeLogger.w(TAG, "failed to decode preference $key", it) }
            .getOrNull()
    }

    var weatherProfileAccount: String?
        get() = WePrefs.getString(HomeSidePanelPreferenceKeys.WEATHER_PROFILE_ACCOUNT)
        set(value) {
            if (value.isNullOrBlank()) {
                WePrefs.remove(HomeSidePanelPreferenceKeys.WEATHER_PROFILE_ACCOUNT)
            } else {
                WePrefs.putString(HomeSidePanelPreferenceKeys.WEATHER_PROFILE_ACCOUNT, value)
            }
        }

    private inline fun <reified T> encode(key: String, value: T) {
        runCatching { DefaultJson.encodeToString(value) }
            .onSuccess { WePrefs.putString(key, it) }
            .onFailure { WeLogger.w(TAG, "failed to encode preference $key", it) }
    }

    private inline fun <reified T> setNullable(key: String, value: T?) {
        if (value == null) {
            WePrefs.remove(key)
        } else {
            encode(key, value)
        }
    }
}
