package dev.ujhhgtg.wekit.utils.polyfills

import android.os.Build

fun Thread.getThreadId(): Long {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        threadId()
    } else {
        @Suppress("DEPRECATION") id
    }
}
