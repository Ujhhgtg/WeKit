package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import dev.ujhhgtg.wekit.utils.HostInfo

/**
 * Theme for WeKit UI injected INTO WeChat.
 *
 * The seed is [SeedResolver.injectedSeed]: WeChat green by default, or the user's custom color when
 * they opted it into WeChat ([ThemeSettings.applyToWechat]). This is read once when the composition
 * enters — it does NOT re-theme live (the user must restart WeChat for a change to apply).
 *
 * NEVER CALL THIS INSIDE MODULE APP.
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalConfiguration provides HostInfo.application.resources.configuration
    ) {
        val dark = darkTheme ?: isSystemInDarkTheme()
        val applyCustom = ThemeSettings.applyToWechat && ThemeSettings.customColor
        val seed = SeedResolver.injectedSeed(HostInfo.application, dark)

        // ---- Material 3 ----
        val materialScheme = if (!applyCustom) {
            if (dark) darkScheme else lightScheme
        } else {
            SeedResolver.materialScheme(seed, dark)
        }

        // TODO: currently we don't have any Miuix components injected into WeChat
        // ---- miuix ----
//        val controller = if (!applyCustom) {
//            ThemeController(
//                colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
//                isDark = dark,
//            )
//        } else {
//            ThemeController(
//                colorSchemeMode = if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
//                keyColor = Color(seed),
//                colorSpec = ThemeSettings.effectiveColorSpec.miuix,
//                paletteStyle = ThemeSettings.paletteStyle.miuix,
//                isDark = dark,
//            )
//        }

//        MiuixTheme(controller = controller) {
        MaterialExpressiveTheme(
            colorScheme = materialScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
//            CompositionLocalProvider(
//                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
//            ) {
            content()
//            }
        }
//        }
    }
}
