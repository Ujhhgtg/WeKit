package dev.ujhhgtg.wekit.agent.model.local

import android.annotation.SuppressLint
import dev.ujhhgtg.wekit.extensions.LlamaNativePack
import dev.ujhhgtg.wekit.extensions.LlamaPackNotInstalledException
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import java.io.File

data class LlamaLaunchFiles(
    val bootstrapApk: File,
    val controllerLibrary: File,
    val childLibrary: File,
)

/** Owns the parent controller mapping and resolves files for each inference child. */
object LlamaNativeLoader {

    private val loadLock = Any()

    @Volatile
    private var llamaControllerLoaded = false

    /** Whether the base llama controller library has been mapped in this process. */
    @JvmStatic
    fun isLoaded(): Boolean = llamaControllerLoaded

    /**
     * Resolves every file needed to launch one inference child. The parent
     * always maps the base library for controller JNI; the fresh app_process
     * child maps the requested base or OpenCL variant independently.
     */
    @JvmStatic
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun prepareLaunch(backend: String): LlamaLaunchFiles = synchronized(loadLock) {
        val bootstrap = NativeLoader.bootstrapApk()
        require(bootstrap.isFile && bootstrap.canRead()) {
            "llama bootstrap APK is unreadable: $bootstrap"
        }
        val base = LlamaNativePack.libraryFile(opencl = false)
            ?: throw LlamaPackNotInstalledException("llama-native extension pack is not installed")
        require(base.isFile && base.canRead()) { "llama controller library is unreadable: $base" }
        val child = if (backend == "opencl") {
            LlamaNativePack.libraryFile(opencl = true)
                ?: throw LlamaPackNotInstalledException(
                    "llama-native OpenCL variant is not installed"
                )
        } else {
            base
        }
        require(child.isFile && child.canRead()) { "llama child library is unreadable: $child" }
        if (!llamaControllerLoaded) {
            System.load(base.absolutePath)
            llamaControllerLoaded = true
        }
        LlamaLaunchFiles(bootstrap, base, child)
    }
}
