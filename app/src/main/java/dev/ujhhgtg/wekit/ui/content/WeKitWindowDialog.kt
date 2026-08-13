package dev.ujhhgtg.wekit.ui.content

import androidx.compose.runtime.Composable
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun WeKitWindowDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismissRequest) {
        WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost, content = content)
    }
}
