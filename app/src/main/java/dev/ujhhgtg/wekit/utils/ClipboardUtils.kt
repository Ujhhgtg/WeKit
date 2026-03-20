package dev.ujhhgtg.wekit.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.utils.logging.WeLogger

fun copyToClipboard(context: Context, content: String, label: String = BuildConfig.TAG) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Native Crash Log", content)
    clipboard.setPrimaryClip(clip)
    WeLogger.i(nameof(::copyToClipboard), "copied to clipboard")
}
