package dev.ujhhgtg.wekit.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.utils.rememberDeviceCornerRadius
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects

/** Shared NavDisplay effects for WeKit's Material 3 settings UIs. */
@Composable
fun rememberM3NavEffects(): NavDisplayEffects {
    val cornerRadius = rememberDeviceCornerRadius(defaultRadius = 32.dp)
    val backdropColor = MaterialTheme.colorScheme.surfaceContainer
    return remember(cornerRadius, backdropColor) {
        NavDisplayEffects(
            enableCornerClip = true,
            cornerClipRadius = cornerRadius,
            cornerClipMode = NavCornerClipMode.Leading,
            dimAmount = 0.5f,
            backdropColor = backdropColor,
            blockInputDuringTransition = false,
        )
    }
}
