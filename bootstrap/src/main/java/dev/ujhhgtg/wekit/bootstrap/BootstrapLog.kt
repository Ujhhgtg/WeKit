package dev.ujhhgtg.wekit.bootstrap

import android.util.Log

object BootstrapLog {
    fun i(tag: String, message: String) { Log.i(tag, message) }
    fun w(tag: String, message: String) { Log.w(tag, message) }
    fun e(tag: String, message: String, error: Throwable) { Log.e(tag, message, error) }
}
