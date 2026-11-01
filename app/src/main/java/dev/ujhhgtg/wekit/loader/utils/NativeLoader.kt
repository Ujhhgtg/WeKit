package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import dalvik.system.BaseDexClassLoader
import dev.ujhhgtg.dexsql.DexSqlLibrary
import java.io.File

/** Business JNI loads from business code, retaining its defining ClassLoader ownership. */
object NativeLoader {
    private val libraries =
        listOf("androidx.graphics.path", "wekit_native", DexSqlLibrary.LIBRARY_NAME)
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
        // The payload's native directory is scrubbed right after startup returns, so every business
        // JNI library has to be mapped now, and every lazy loader has to run now as well: a later
        // System.loadLibrary would have to resolve the library file through the scrubbed directory,
        // which throws once that directory disappears with the host process still running.
        for (name in libraries) {
            val library = File(nativeDirectory, "lib$name.so")
            require(library.isFile && library.canRead()) { "missing business library: $library" }
            check(File(owner.findLibrary(name)!!).canonicalFile == library.canonicalFile) {
                "business library resolves outside its verified directory: $name"
            }
            System.loadLibrary(name)
        }
        // DexSqlNative's initializer calls DexSqlLibrary.ensureLoaded(); recording the library as
        // loaded keeps the first DexSQL query from calling System.loadLibrary again.
        DexSqlLibrary.load(File(nativeDirectory, "lib${DexSqlLibrary.LIBRARY_NAME}.so"))
        check(DexSqlLibrary.isLoaded) { "dexsql native library did not load" }
        loaded = true
    }

    /**
     * Payload code that needs a business library must not repeat System.loadLibrary; see [init].
     */
    fun requireLoaded(name: String) {
        check(loaded && name in libraries) { "business library is not loaded: $name" }
    }
}
