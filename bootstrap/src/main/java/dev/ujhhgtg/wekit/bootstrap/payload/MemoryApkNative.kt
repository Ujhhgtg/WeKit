package dev.ujhhgtg.wekit.bootstrap.payload

import android.annotation.SuppressLint
import java.io.File

/** Its JNI library belongs to the bootstrap loader, never to the business loader. */
object MemoryApkNative {
    private var initialized = false

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun initialize(library: File) {
        if (initialized) return
        System.load(library.absolutePath)
        initialized = true
    }

    external fun create(): Int
    external fun seal(fd: Int)
}
