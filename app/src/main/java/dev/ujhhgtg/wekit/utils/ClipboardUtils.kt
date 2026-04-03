package dev.ujhhgtg.wekit.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun copyToClipboard(context: Context, content: String) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("text", content)
    clipboard.setPrimaryClip(clip)
}

fun copyToClipboard(content: String) {
    val clipboard =
        HostInfo.application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("text", content)
    clipboard.setPrimaryClip(clip)
}
