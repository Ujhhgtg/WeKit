package dev.ujhhgtg.wekit.activity.testsettings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.ujhhgtg.wekit.ui.content.nukex.NukePopupMotionConfig
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import dev.ujhhgtg.wekit.ui.utils.theme.SeedResolver
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings

private const val NUKE_DEFAULT_ACCENT = 0xFFEC4899.toInt()

@Composable
fun NukeSettingsContent() {
    val context = LocalContext.current
    val darkTheme = ThemeSettings.themeMode.resolve()
    val accent = Color(
        SeedResolver.moduleAccent(
            context = context,
            dark = darkTheme,
            defaultAccent = NUKE_DEFAULT_ACCENT,
        )
    )

    NukeTheme(
        darkTheme = darkTheme,
        accent = accent,
        hapticsEnabled = ThemeSettings.nukeHaptics,
        immediatePressFeedback = ThemeSettings.nukeImmediatePressFeedback,
        popupMotion = NukePopupMotionConfig(
            animationMode = ThemeSettings.nukePopupAnimation,
            useDialogHost = ThemeSettings.nukePopupDialogHost,
            predictiveExit = ThemeSettings.nukePopupPredictiveExit,
        ),
    ) {
        NukeSettingsRoot()
    }
}
