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
import dev.ujhhgtg.wekit.data.KvStore
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
    fun resolve(): Boolean =
        when (this) {
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

/**
 * Palette generation style. Only the four styles represented by [supportsSpec2025] honor Spec2025;
 * the rest fall back to Spec2021.
 */
enum class AppPaletteStyle(val displayName: String, val materialKolor: PaletteStyle) {
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
enum class AppColorSpec(val displayName: String, val materialKolor: ColorSpec.SpecVersion) {
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
 * - the UI injected into WeChat ([InjectedUiTheme]) only consult
 *   [dynamicWallpaper]/[seedColor]/[paletteStyle]/[colorSpec], and NOT live — it reads the
 *   persisted values once per WeChat launch.
 */
object ThemeSettings {
    var themeMode by
        mutableStateOf(AppThemeMode.fromName(KvStore.getString(Preferences.THEME_MODE)))
        private set

    var pageTransitionAnimation by
        mutableStateOf(
            PageTransitionAnimation.fromName(
                KvStore.getString(Preferences.THEME_PAGE_TRANSITION_ANIMATION),
            ),
        )
        private set

    /** Seed the accent from the platform wallpaper accent (SDK >= 31) instead of [seedColor]. */
    var dynamicWallpaper by
        mutableStateOf(KvStore.getBoolOrFalse(Preferences.THEME_DYNAMIC_WALLPAPER))
        private set

    var paletteStyle by
        mutableStateOf(
            AppPaletteStyle.fromName(KvStore.getString(Preferences.THEME_PALETTE_STYLE)),
        )
        private set

    var colorSpec by
        mutableStateOf(AppColorSpec.fromName(KvStore.getString(Preferences.THEME_COLOR_SPEC)))
        private set

    /** Seed color (ARGB int) used when wallpaper color is off. */
    var seedColor by
        mutableIntStateOf(KvStore.getIntOrDef(Preferences.THEME_SEED_COLOR, DEFAULT_SEED_COLOR))
        private set

    /** Spec coerced to 2021 when the current palette style can't honor 2025. */
    val effectiveColorSpec: AppColorSpec
        get() = if (paletteStyle.supportsSpec2025) colorSpec else AppColorSpec.SPEC_2021

    fun updateThemeMode(value: AppThemeMode) {
        themeMode = value
        KvStore.putString(Preferences.THEME_MODE, value.name)
    }

    fun updatePageTransitionAnimation(value: PageTransitionAnimation) {
        pageTransitionAnimation = value
        KvStore.putString(Preferences.THEME_PAGE_TRANSITION_ANIMATION, value.name)
    }

    fun updateDynamicWallpaper(value: Boolean) {
        dynamicWallpaper = value
        KvStore.putBool(Preferences.THEME_DYNAMIC_WALLPAPER, value)
    }

    fun updatePaletteStyle(value: AppPaletteStyle) {
        paletteStyle = value
        KvStore.putString(Preferences.THEME_PALETTE_STYLE, value.name)
    }

    fun updateColorSpec(value: AppColorSpec) {
        colorSpec = value
        KvStore.putString(Preferences.THEME_COLOR_SPEC, value.name)
    }

    fun updateSeedColor(value: Int) {
        seedColor = value
        KvStore.putInt(Preferences.THEME_SEED_COLOR, value)
    }

    /** Default seed accent (WeChat green 0xFF07C160). */
    const val DEFAULT_SEED_COLOR: Int = 0xFF07C160.toInt()
}
