package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import dalvik.system.BaseDexClassLoader
import java.io.File

/** Business JNI loads from business code, retaining its defining ClassLoader ownership. */
object NativeLoader {
    private lateinit var nativeDirectory: File
    private lateinit var publicBootstrapApk: File
    private var loaded = false

    fun configurePayload(
        payloadApk: ParcelFileDescriptor,
        payloadSha256: String,
        nativeDirectory: File,
        bootstrapApk: File,
    ) {
        check(!loaded) { "business native libraries have already loaded" }
        this.nativeDirectory = nativeDirectory
        publicBootstrapApk = bootstrapApk
        ResourcesInjector.configurePayload(payloadApk, payloadSha256)
    }

    fun bootstrapApk(): File = publicBootstrapApk

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun init() {
        if (loaded) return
        val owner = NativeLoader::class.java.classLoader as BaseDexClassLoader
        for (name in listOf("androidx.graphics.path", "wekit_native")) {
            val library = File(nativeDirectory, "lib$name.so")
            require(library.isFile && library.canRead()) { "missing business library: $library" }
            check(File(owner.findLibrary(name)!!).canonicalFile == library.canonicalFile) {
                "business library resolves outside its verified directory: $name"
            }
            System.loadLibrary(name)
        }
        loaded = true
    }
}
