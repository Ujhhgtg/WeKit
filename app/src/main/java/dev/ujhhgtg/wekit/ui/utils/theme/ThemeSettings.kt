package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.nukex.NukePopupAnimationMode
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings.applyToWechat
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings.colorSpec
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings.dynamicWallpaper
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings.paletteStyle
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings.seedColor

/** How the Settings UI decides light vs. dark. */
enum class AppThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式");

    /** Reads [isSystemInDarkTheme] for [SYSTEM]; must be called in a composable. */
    @Composable
    fun resolve(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: SYSTEM
    }
}

enum class PageTransitionAnimation {
    AOSP,
    MIUIX;

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: AOSP
    }
}

enum class SettingsUiEngine(val displayName: String) {
    MATERIAL3("Material 3"),
    NUKE("Nuke");

    companion object {
        fun fromName(value: String?): SettingsUiEngine = when (value) {
            // legacy names written by older builds
            "MIUIX", "Miuix" -> MATERIAL3
            NUKE.name -> NUKE
            else -> MATERIAL3
        }
    }
}

/**
 * Palette generation style. Only the four styles represented by [supportsSpec2025] honor Spec2025;
 * the rest fall back to Spec2021.
 */
enum class AppPaletteStyle(
    val displayName: String,
    val materialKolor: PaletteStyle,
) {
    TONAL_SPOT("Tonal Spot", PaletteStyle.TonalSpot),
    NEUTRAL("Neutral", PaletteStyle.Neutral),
    VIBRANT("Vibrant", PaletteStyle.Vibrant),
    EXPRESSIVE("Expressive", PaletteStyle.Expressive),
    RAINBOW("Rainbow", PaletteStyle.Rainbow),
    FRUIT_SALAD("Fruit Salad", PaletteStyle.FruitSalad),
    MONOCHROME("Monochrome", PaletteStyle.Monochrome),
    FIDELITY("Fidelity", PaletteStyle.Fidelity),
    CONTENT("Content", PaletteStyle.Content);

    val supportsSpec2025: Boolean
        get() = this == TONAL_SPOT || this == NEUTRAL || this == VIBRANT || this == EXPRESSIVE

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: TONAL_SPOT
    }
}

/** Material color specification version. */
enum class AppColorSpec(
    val displayName: String,
    val materialKolor: ColorSpec.SpecVersion,
) {
    SPEC_2021("Material 3 (2021)", ColorSpec.SpecVersion.SPEC_2021),
    SPEC_2025("Expressive (2025)", ColorSpec.SpecVersion.SPEC_2025);

    companion object {
        fun fromName(value: String?) = entries.find { it.name == value } ?: SPEC_2025
    }
}

/**
 * Observable theme state. Backed by [mutableStateOf] seeded from MMKV, so a change from a settings
 * row re-themes the visible module UI immediately; setters persist to MMKV. Enums are stored by
 * [Enum.name].
 *
 * Two consumers:
 * - the module's own UI ([ModuleTheme]) re-themes live from every value here;
 * - the UI injected into WeChat ([InjectedUiTheme]) + native recoloring ([dev.ujhhgtg.wekit.features.items.beautify.MonetEngine]) only
 *   consult [applyToWechat]/[dynamicWallpaper]/[seedColor]/[paletteStyle]/[colorSpec], and NOT live —
 *   they read the persisted values once per WeChat launch.
 */
object ThemeSettings {

    var uiEngine by mutableStateOf(
        SettingsUiEngine.fromName(WePrefs.getString(Preferences.THEME_UI_ENGINE))
    )
        private set
    var themeMode by mutableStateOf(AppThemeMode.fromName(WePrefs.getString(Preferences.THEME_MODE)))
        private set
    var predictiveBackEnabled by mutableStateOf(
        WePrefs.getBoolOrFalse(Preferences.THEME_PREDICTIVE_BACK_ENABLED)
    )
        private set
    /** Installing the platform flags is a process-start operation. */
    val appliedPredictiveBackEnabled = predictiveBackEnabled
    var pageTransitionAnimation by mutableStateOf(
        PageTransitionAnimation.fromName(
            WePrefs.getString(Preferences.THEME_PAGE_TRANSITION_ANIMATION)
        )
    )
        private set
    /** Haptic feedback for the Nuke component engine; ignored by Material 3. */
    var nukeHaptics by mutableStateOf(
        WePrefs.getBoolOrDef(Preferences.THEME_NUKE_HAPTICS, true)
    )
        private set
    var nukePopupAnimation by mutableStateOf(
        NukePopupAnimationMode.fromName(
            WePrefs.getString(Preferences.THEME_NUKE_POPUP_ANIMATION)
        )
    )
        private set
    var nukePopupDialogHost by mutableStateOf(
        WePrefs.getBoolOrDef(Preferences.THEME_NUKE_POPUP_DIALOG_HOST, true)
    )
        private set
    var nukePopupPredictiveExit by mutableStateOf(
        WePrefs.getBoolOrDef(Preferences.THEME_NUKE_POPUP_PREDICTIVE_EXIT, true)
    )
        private set
    var nukePageExitOptimization by mutableStateOf(
        WePrefs.getBoolOrDef(Preferences.THEME_NUKE_PAGE_EXIT_OPTIMIZATION, true)
    )
        private set
    var nukeImmediatePressFeedback by mutableStateOf(
        WePrefs.getBoolOrDef(Preferences.THEME_NUKE_IMMEDIATE_PRESS_FEEDBACK, true)
    )
        private set
    /** Seed the accent from the platform wallpaper accent (SDK >= 31) instead of [seedColor]. */
    var dynamicWallpaper by mutableStateOf(WePrefs.getBoolOrFalse(Preferences.THEME_DYNAMIC_WALLPAPER))
        private set
    var paletteStyle by mutableStateOf(
        AppPaletteStyle.fromName(WePrefs.getString(Preferences.THEME_PALETTE_STYLE))
    )
        private set
    var colorSpec by mutableStateOf(AppColorSpec.fromName(WePrefs.getString(Preferences.THEME_COLOR_SPEC)))
        private set

    /** Seed color (ARGB int) used when wallpaper color is off. */
    var seedColor by mutableIntStateOf(WePrefs.getIntOrDef(Preferences.THEME_SEED_COLOR, DEFAULT_SEED_COLOR))
        private set

    /**
     * Whether the selected color also applies to WeChat itself (injected WeKit ComposeUI + native
     * recoloring via [dev.ujhhgtg.wekit.features.items.beautify.MonetEngine]). Does NOT take effect live — requires restarting WeChat.
     */
    var applyToWechat by mutableStateOf(WePrefs.getBoolOrFalse(Preferences.THEME_APPLY_TO_WECHAT))
        private set

    /** Spec coerced to 2021 when the current palette style can't honor 2025. */
    val effectiveColorSpec: AppColorSpec
        get() = if (paletteStyle.supportsSpec2025) colorSpec else AppColorSpec.SPEC_2021

    fun updateUiEngine(value: SettingsUiEngine) {
        uiEngine = value
        WePrefs.putString(Preferences.THEME_UI_ENGINE, value.name)
    }

    fun updateThemeMode(value: AppThemeMode) {
        themeMode = value
        WePrefs.putString(Preferences.THEME_MODE, value.name)
    }

    fun updatePredictiveBackEnabled(value: Boolean) {
        predictiveBackEnabled = value
        WePrefs.putBool(Preferences.THEME_PREDICTIVE_BACK_ENABLED, value)
    }

    fun updatePageTransitionAnimation(value: PageTransitionAnimation) {
        pageTransitionAnimation = value
        WePrefs.putString(Preferences.THEME_PAGE_TRANSITION_ANIMATION, value.name)
    }

    fun updateNukeHaptics(value: Boolean) {
        nukeHaptics = value
        WePrefs.putBool(Preferences.THEME_NUKE_HAPTICS, value)
    }

    fun updateNukePopupAnimation(value: NukePopupAnimationMode) {
        nukePopupAnimation = value
        WePrefs.putString(Preferences.THEME_NUKE_POPUP_ANIMATION, value.name)
    }

    fun updateNukePopupDialogHost(value: Boolean) {
        nukePopupDialogHost = value
        WePrefs.putBool(Preferences.THEME_NUKE_POPUP_DIALOG_HOST, value)
    }

    fun updateNukePopupPredictiveExit(value: Boolean) {
        nukePopupPredictiveExit = value
        WePrefs.putBool(Preferences.THEME_NUKE_POPUP_PREDICTIVE_EXIT, value)
    }

    fun updateNukePageExitOptimization(value: Boolean) {
        nukePageExitOptimization = value
        WePrefs.putBool(Preferences.THEME_NUKE_PAGE_EXIT_OPTIMIZATION, value)
    }

    fun updateNukeImmediatePressFeedback(value: Boolean) {
        nukeImmediatePressFeedback = value
        WePrefs.putBool(Preferences.THEME_NUKE_IMMEDIATE_PRESS_FEEDBACK, value)
    }

    fun applyNukeRecommendedFineTuning() {
        updateNukeImmediatePressFeedback(true)
        updateNukePageExitOptimization(true)
        updateNukePopupAnimation(NukePopupAnimationMode.Vanilla)
        updateNukePopupDialogHost(true)
        updateNukePopupPredictiveExit(true)
    }

    fun restoreNukeOriginalFineTuning() {
        updateNukeImmediatePressFeedback(false)
        updateNukePageExitOptimization(false)
        updateNukePopupAnimation(NukePopupAnimationMode.Vanilla)
        updateNukePopupDialogHost(false)
        updateNukePopupPredictiveExit(false)
    }

    fun updateDynamicWallpaper(value: Boolean) {
        dynamicWallpaper = value
        WePrefs.putBool(Preferences.THEME_DYNAMIC_WALLPAPER, value)
    }

    fun updatePaletteStyle(value: AppPaletteStyle) {
        paletteStyle = value
        WePrefs.putString(Preferences.THEME_PALETTE_STYLE, value.name)
    }

    fun updateColorSpec(value: AppColorSpec) {
        colorSpec = value
        WePrefs.putString(Preferences.THEME_COLOR_SPEC, value.name)
    }

    fun updateSeedColor(value: Int) {
        seedColor = value
        WePrefs.putInt(Preferences.THEME_SEED_COLOR, value)
    }

    fun updateApplyToWechat(value: Boolean) {
        applyToWechat = value
        WePrefs.putBool(Preferences.THEME_APPLY_TO_WECHAT, value)
    }

    /** Default seed accent (WeChat green 0xFF07C160). */
    const val DEFAULT_SEED_COLOR: Int = 0xFF07C160.toInt()

}
