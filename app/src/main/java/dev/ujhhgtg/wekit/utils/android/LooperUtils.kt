package dev.ujhhgtg.wekit.utils.android

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed

@Suppress("ObjectPropertyName")
val _mainHandler = Handler(Looper.getMainLooper())

inline fun postOnUiThread(crossinline action: () -> Unit) {
    _mainHandler.post {
        action()
    }
}

inline fun runOnUiThread(crossinline action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else postOnUiThread(action)
}

inline fun postOnUiThread(delayInMillis: Long, crossinline action: () -> Unit) {
    _mainHandler.postDelayed(delayInMillis) {
        action()
    }
}
