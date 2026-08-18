package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 theme for the module's own UI (settings page + module dialogs), driven by
 * [ThemeSettings]:
 *
 * - the palette style + color spec generated from the selected seed
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

    val materialScheme = SeedResolver.materialScheme(
        SeedResolver.customSeed(context, darkTheme),
        darkTheme,
    )

    MaterialExpressiveTheme(
        colorScheme = materialScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
