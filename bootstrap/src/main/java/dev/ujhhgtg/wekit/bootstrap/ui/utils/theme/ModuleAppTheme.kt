package dev.ujhhgtg.wekit.bootstrap.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
fun ModuleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    GeneratedTheme(darkTheme = darkTheme, content = content)
}
