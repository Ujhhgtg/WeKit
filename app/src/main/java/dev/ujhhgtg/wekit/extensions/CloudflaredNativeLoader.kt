package dev.ujhhgtg.wekit.extensions

import android.annotation.SuppressLint

/** Loads the cloudflared extension once in the current module ClassLoader. */
object CloudflaredNativeLoader {

    private val loadLock = Any()

    @Volatile
    private var cloudflaredLoaded = false

    /** Whether the cloudflared bridge has been System.load-ed in this process. */
    @JvmStatic
    fun isLoaded(): Boolean = cloudflaredLoaded

    /**
     * Lazily loads the Go cloudflared bridge from the cloudflared extension pack
     * when the built-in read-receipts backend is first used. Throws
     * [CloudflaredPackNotInstalledException] when the
     * pack has not been downloaded — callers surface the install dialog.
     */
    @JvmStatic
    fun ensureLoaded() {
        if (cloudflaredLoaded) return
        synchronized(loadLock) {
            if (cloudflaredLoaded) return
            val library = CloudflaredPack.libraryFile()
                ?: throw CloudflaredPackNotInstalledException(
                    "cloudflared extension pack is not installed"
                )
            @SuppressLint("UnsafeDynamicallyLoadedCode")
            System.load(library.absolutePath)
            cloudflaredLoaded = true
        }
    }
}
