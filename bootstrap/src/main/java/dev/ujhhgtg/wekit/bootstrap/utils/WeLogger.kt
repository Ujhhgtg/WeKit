package dev.ujhhgtg.wekit.bootstrap.utils

import android.util.Log

/** Standalone tools do not depend on the host's business logger or storage. */
object WeLogger {
    fun i(tag: String, message: String) { Log.i(tag, message) }
    fun w(tag: String, message: String) { Log.w(tag, message) }
    fun e(tag: String, message: String, error: Throwable? = null) { Log.e(tag, message, error) }
}
