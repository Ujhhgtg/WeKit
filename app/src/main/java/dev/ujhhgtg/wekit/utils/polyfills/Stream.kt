package dev.ujhhgtg.wekit.utils.polyfills

import android.os.Build
import java.util.stream.Stream

fun <T> Stream<T>.intoList(): List<T> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        toList()
    } else {
        @Suppress("UNCHECKED_CAST")
        toArray().toList() as List<T>
    }
}
