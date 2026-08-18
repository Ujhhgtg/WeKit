@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.ujhhgtg.wekit.utils.HostInfo

fun copyToClipboard(context: Context, content: String) {
    copyToClipboard(context, "text", content)
}

fun copyToClipboard(context: Context, label: CharSequence, content: String) {
    val clipboard = context.getSystemService<ClipboardManager>()
    val clip = ClipData.newPlainText(label, content)
    clipboard.setPrimaryClip(clip)
}

inline fun copyToClipboard(content: String) = copyToClipboard(HostInfo.application, content)

inline fun readTextFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService<ClipboardManager>()
    val item = clipboard.primaryClip?.getItemAt(0) ?: return null
    return item.text?.toString()
}
