package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/**
 * Theme for the module's OWN UI (settings page + module dialogs). Wraps content in BOTH a Material 3
 * [MaterialExpressiveTheme] and a miuix [MiuixTheme] driven by [ThemeSettings], so components from
 * either design system share one accent:
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

    // TODO: currently we don't have any Miuix components injected into WeChat
    // ---- miuix ----
//    val controller = if (!ThemeSettings.customColor) {
//        ThemeController(
//            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
//            isDark = darkTheme,
//        )
//    } else {
//        ThemeController(
//            colorSchemeMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
//            keyColor = Color(SeedResolver.customSeed(context, darkTheme)),
//            colorSpec = ThemeSettings.effectiveColorSpec.miuix,
//            paletteStyle = ThemeSettings.paletteStyle.miuix,
//            isDark = darkTheme,
//        )
//    }

    // ---- Material 3 ----
    val materialScheme = when {
        ThemeSettings.customColor -> {
            SeedResolver.materialScheme(SeedResolver.customSeed(context, darkTheme), darkTheme)
        }

        ThemeSettings.uiEngine == SettingsUiEngine.NUKE -> defaultNukeMaterialScheme(darkTheme)
        else -> defaultMiuixMaterialScheme(darkTheme)
    }

    MaterialExpressiveTheme(
        colorScheme = materialScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
//        MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
        ) {
            content()
        }
//        }
    }
}

/**
 * Material 3 is still the host for existing feature dialogs. When those dialogs are reached from
 * Nuke, use Nuke's fixed pink rather than Miuix's unrelated default blue. This deliberately does
 * not read the hidden custom-palette controls; that remains exclusive to the custom-color branch.
 */
private fun defaultNukeMaterialScheme(darkTheme: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = Color(0xFFEC4899),
    isDark = darkTheme,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2021,
)

private fun defaultMiuixMaterialScheme(darkTheme: Boolean): ColorScheme {
    val miuixColors = if (darkTheme) miuixDarkColorScheme() else miuixLightColorScheme()
    val dialogSurface = miuixColors.surfaceContainer

    return dynamicColorScheme(
        seedColor = miuixColors.primary,
        isDark = darkTheme,
        style = PaletteStyle.TonalSpot,
        specVersion = ColorSpec.SpecVersion.SPEC_2021,
    ).copy(
        primary = miuixColors.primary,
        onPrimary = miuixColors.onPrimary,
        primaryContainer = miuixColors.primaryContainer,
        onPrimaryContainer = miuixColors.onPrimaryContainer,
        error = miuixColors.error,
        onError = miuixColors.onError,
        errorContainer = miuixColors.errorContainer,
        onErrorContainer = miuixColors.onErrorContainer,
        background = miuixColors.background,
        onBackground = miuixColors.onBackground,
        surface = dialogSurface,
        onSurface = miuixColors.onSurfaceContainer,
        surfaceVariant = miuixColors.surfaceVariant,
        onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
        surfaceTint = dialogSurface,
        outline = miuixColors.outline,
        outlineVariant = miuixColors.dividerLine,
        surfaceBright = dialogSurface,
        surfaceContainerLowest = dialogSurface,
        surfaceContainerLow = dialogSurface,
        surfaceContainer = dialogSurface,
        surfaceContainerHigh = miuixColors.surfaceContainerHigh,
        surfaceContainerHighest = miuixColors.surfaceContainerHighest,
        surfaceDim = miuixColors.surface,
    )
}
