package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * Material 3 theme for the module's own UI (settings page + module dialogs), driven by
 * [ThemeSettings]:
 *
 * - custom color OFF → the selected settings engine's fixed default palette;
 * - custom color ON → the palette style + color spec generated from the user's custom seed
 *   ([SeedResolver.customSeed]: wallpaper accent when 动态壁纸取色 is on, else the chosen seed color).
 *
 * Re-themes live: every [ThemeSettings] value is observable, so a settings row change recomposes.
 *
 * NEVER CALL THIS INSIDE MODULE APP.
 */
@Composable
fun ModuleTheme(
    darkTheme: Boolean = ThemeSettings.themeMode.resolve(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val materialScheme = when {
        ThemeSettings.customColor -> {
            SeedResolver.materialScheme(SeedResolver.customSeed(context, darkTheme), darkTheme)
        }

        ThemeSettings.uiEngine == SettingsUiEngine.NUKE -> defaultNukeMaterialScheme(darkTheme)
        else -> defaultMaterial3Scheme(darkTheme)
    }

    MaterialExpressiveTheme(
        colorScheme = materialScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/**
 * Material 3 is still the host for existing feature dialogs. When those dialogs are reached from
 * Nuke, use Nuke's fixed pink. This deliberately does not read the hidden custom-palette controls;
 * that remains exclusive to the custom-color branch.
 */
private fun defaultNukeMaterialScheme(darkTheme: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = Color(0xFFEC4899),
    isDark = darkTheme,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2021,
)

/**
 * Default Material 3 scheme for the Material 3 engine (no custom color): WeChat-green Tonal Spot.
 */
private fun defaultMaterial3Scheme(darkTheme: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = Color(ThemeSettings.DEFAULT_SEED_COLOR),
    isDark = darkTheme,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2021,
)
