package moe.ouom.wekit.utils

import android.os.Build
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.logging.WeLogger

// this is what we call 'technical debt'
fun Thread.getThreadId(): Long {
    return this.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            it.threadId()
        } else {
            @Suppress("DEPRECATION")
            it.id
        }
    }
}

fun logStackTrace() {
    Thread.currentThread().stackTrace
        .drop(2) // drop getStackTrace() and logStackTrace() itself
        .joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        .let { WeLogger.d(nameof(logStackTrace()), "\n$it") }
}
