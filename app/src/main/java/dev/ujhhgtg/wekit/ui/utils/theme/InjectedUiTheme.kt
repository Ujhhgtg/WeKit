package dev.ujhhgtg.wekit.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.utils.HostInfo

/**
 * Theme for WeKit UI injected INTO WeChat.
 *
 * The seed is [SeedResolver.customSeed]: the user's selected seed (WeChat green by default, or the
 * wallpaper accent with dynamic color on). This is read once when the composition enters — it does
 * NOT re-theme live (the user must restart WeChat for a change to apply).
 *
 * NEVER CALL THIS INSIDE MODULE APP.
 */
@Composable
fun InjectedUiTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
        val dark = darkTheme ?: isSystemInDarkTheme()
        val seed = SeedResolver.customSeed(HostInfo.application, dark)

        MaterialExpressiveTheme(
            colorScheme = SeedResolver.materialScheme(seed, dark),
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
