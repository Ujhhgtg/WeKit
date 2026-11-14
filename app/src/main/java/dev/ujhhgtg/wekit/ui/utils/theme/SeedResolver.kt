package dev.ujhhgtg.wekit.ui.utils.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamicColorScheme

/**
 * Single source of truth for turning [ThemeSettings] into a concrete accent seed and the derived
 * Material 3 color schemes. Shared by [ModuleTheme] and [InjectedUiTheme] so the module UI and the
 * WeKit UI injected into WeChat agree on the same colors.
 */
object SeedResolver {
    private val wallpaperSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Platform wallpaper accent (primary), or `null` when unavailable (SDK < 31). */
    @SuppressLint("NewApi") // gated on [wallpaperSupported]
    private fun wallpaperAccent(context: Context, dark: Boolean): Int? {
        if (!wallpaperSupported) return null
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return scheme.primary.toArgb()
    }

    /** The platform wallpaper accent when enabled, otherwise the user's chosen seed color. */
    fun customSeed(context: Context, dark: Boolean): Int =
        if (ThemeSettings.dynamicWallpaper) {
            wallpaperAccent(context, dark) ?: ThemeSettings.seedColor
        } else {
            ThemeSettings.seedColor
        }

    /** Material 3 [ColorScheme] generated from [seed] with the current palette style + spec. */
    fun materialScheme(seed: Int, dark: Boolean): ColorScheme =
        dynamicColorScheme(
            seedColor = Color(seed),
            isDark = dark,
            style = ThemeSettings.paletteStyle.materialKolor,
            specVersion = ThemeSettings.effectiveColorSpec.materialKolor,
        )
}
