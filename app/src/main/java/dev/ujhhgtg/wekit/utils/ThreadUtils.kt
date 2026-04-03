package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.comptime.nameOf

fun logStackTrace(tag: String = nameOf(logStackTrace())) {
    Thread.currentThread().stackTrace
        .drop(2) // drop getStackTrace() and logStackTrace() itself
        .joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        .let { WeLogger.d(tag, "\n$it") }
}
