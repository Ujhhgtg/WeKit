package dev.ujhhgtg.wekit.ui.content.nukex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.ujhhgtg.wekit.ui.utils.theme.SeedResolver
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings

private const val NUKE_DEFAULT_ACCENT = 0xFFEC4899.toInt()

@Immutable
data class NukeColors(
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val windowDimming: Color,
)

fun nukeLightColors(accent: Color): NukeColors =
    NukeColors(
        isLight = true,
        background = Color(0xFFF4F4F6),
        surface = Color.White,
        border = Color(0xFFEFEFEF),
        textPrimary = Color(0xFF1A1A1A),
        textSecondary = Color(0xFF757575),
        accent = accent,
        windowDimming = Color.Black.copy(alpha = 0.3f),
    )

fun nukeDarkColors(accent: Color): NukeColors =
    NukeColors(
        isLight = false,
        background = Color(0xFF0A0A0A),
        surface = Color(0xFF161616),
        border = Color(0xFF242424),
        textPrimary = Color.White,
        textSecondary = Color(0xFF888888),
        accent = accent,
        windowDimming = Color.Black.copy(alpha = 0.6f),
    )

@Stable
class NukeHapticFeedback internal constructor(
    val enabled: Boolean,
    private val platformHapticFeedback: HapticFeedback,
) {
    fun performHapticFeedback(type: HapticFeedbackType) {
        if (enabled) {
            platformHapticFeedback.performHapticFeedback(type)
        }
    }
}

@Stable
private data class NukeThemeState(
    val colors: NukeColors,
    val hapticFeedback: NukeHapticFeedback,
    val popupMotion: NukePopupMotionConfig,
    val immediatePressFeedback: Boolean,
)

private val DisabledNukeHapticFeedback = object : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

private val LocalNukeTheme = compositionLocalOf {
    NukeThemeState(
        colors = nukeLightColors(Color(0xFFEC4899)),
        hapticFeedback = NukeHapticFeedback(
            enabled = false,
            platformHapticFeedback = DisabledNukeHapticFeedback,
        ),
        popupMotion = NukePopupMotionConfig(),
        immediatePressFeedback = false,
    )
}

object NukeTheme {
    val colors: NukeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNukeTheme.current.colors

    val hapticFeedback: NukeHapticFeedback
        @Composable
        @ReadOnlyComposable
        get() = LocalNukeTheme.current.hapticFeedback

    val popupMotion: NukePopupMotionConfig
        @Composable
        @ReadOnlyComposable
        get() = LocalNukeTheme.current.popupMotion

    val immediatePressFeedback: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalNukeTheme.current.immediatePressFeedback
}

@Composable
fun NukeTheme(
    darkTheme: Boolean,
    accent: Color,
    hapticsEnabled: Boolean = true,
    popupMotion: NukePopupMotionConfig = NukePopupMotionConfig(),
    immediatePressFeedback: Boolean = false,
    lightColors: NukeColors = nukeLightColors(accent),
    darkColors: NukeColors = nukeDarkColors(accent),
    content: @Composable () -> Unit,
) {
    val platformHapticFeedback = LocalHapticFeedback.current
    val hapticFeedback = remember(platformHapticFeedback, hapticsEnabled) {
        NukeHapticFeedback(
            enabled = hapticsEnabled,
            platformHapticFeedback = platformHapticFeedback,
        )
    }
    val state = NukeThemeState(
        colors = if (darkTheme) darkColors else lightColors,
        hapticFeedback = hapticFeedback,
        popupMotion = popupMotion,
        immediatePressFeedback = immediatePressFeedback,
    )

    CompositionLocalProvider(
        LocalNukeTheme provides state,
        content = content,
    )
}

/**
 * 以当前模块设置装配 NukeTheme（明暗 + 主题色 + 动效/触感），供设置页与独立对话框共用，
 * 保证经 [dev.ujhhgtg.wekit.ui.utils.showComposeDialog] 弹出的 Nuke 组件拿到与设置页一致的主题色。
 */
@Composable
fun NukeModuleTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = ThemeSettings.themeMode.resolve()
    NukeTheme(
        darkTheme = darkTheme,
        accent = Color(
            SeedResolver.moduleAccent(
                context = context,
                dark = darkTheme,
                defaultAccent = NUKE_DEFAULT_ACCENT,
            ),
        ),
        hapticsEnabled = ThemeSettings.nukeHaptics,
        immediatePressFeedback = ThemeSettings.nukeImmediatePressFeedback,
        popupMotion = NukePopupMotionConfig(
            animationMode = ThemeSettings.nukePopupAnimation,
            useDialogHost = ThemeSettings.nukePopupDialogHost,
            predictiveExit = ThemeSettings.nukePopupPredictiveExit,
        ),
    ) {
        content()
    }
}
