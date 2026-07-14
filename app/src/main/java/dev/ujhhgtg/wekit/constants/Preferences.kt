package dev.ujhhgtg.wekit.constants

import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption

object Preferences {

    const val VERBOSE_LOG = "verbose_log"
    const val NO_DEX_RESOLVE = "no_dex_resolve"
    const val SHOW_STARTUP_TOAST = "toast_startup"
    const val RESET_DEX_ON_HOT_UPDATE = "reset_dex_on_hot_upd"
    const val MATCH_GENERIC_WXID_EXP = "match_generic_wxid"

    // Settings UI theming
    const val THEME_MODE = "settings_theme_mode"
    const val THEME_CUSTOM_COLOR = "settings_theme_custom_color"
    const val THEME_DYNAMIC_WALLPAPER = "settings_theme_dynamic_wallpaper"
    const val THEME_PALETTE_STYLE = "settings_theme_palette_style"
    const val THEME_COLOR_SPEC = "settings_theme_color_spec"
    const val THEME_SEED_COLOR = "settings_theme_seed_color"
    const val THEME_APPLY_TO_WECHAT = "settings_theme_apply_to_wechat"

    var verboseLog by prefOption(VERBOSE_LOG, false)
    var noDexResolve by prefOption(NO_DEX_RESOLVE, false)
    var showStartupToast by prefOption(SHOW_STARTUP_TOAST, false)
    var resetDexCacheOnHotUpdate by prefOption(RESET_DEX_ON_HOT_UPDATE, false)

    // ALWAYS check whether sender is group chat!!!
    var matchGenericWxIdExp by prefOption(MATCH_GENERIC_WXID_EXP, true)

    // use this when Google fucked up itself again
//    var useActivityInsteadOfDialog: Boolean
//        get() = false
//        set(value) { WePrefs.putBool(USE_ACTIVITY_INSTEAD_OF_DIALOG, value) }
}
