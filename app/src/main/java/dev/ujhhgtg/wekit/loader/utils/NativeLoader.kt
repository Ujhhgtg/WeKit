package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.wekit.loader.utils.NativeLoader.init
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private const val TAG = "NativeLoader"

    private data class ZygiskPayload(
        val apk: File,
        val dataDir: File,
    )

    private val nativeLoadLock = Any()
    private var zygiskPayload: ZygiskPayload? = null
    private var zygiskNativeLibraries: Map<String, File> = emptyMap()
    private var nativeLibrariesLoaded = false

    /**
     * Configures native loading for the copied APK that the FunBox-style
     * bootstrap placed in the target app's data directory. This must run before
     * module startup reaches [init].
     */
    @JvmStatic
    fun configureZygiskPayload(apkPath: String, dataDir: String) = synchronized(nativeLoadLock) {
        check(!nativeLibrariesLoaded) { "native libraries were already loaded" }
        val apk = File(apkPath)
        require(apk.isFile && apk.canRead()) { "Zygisk payload APK is unreadable: $apkPath" }
        val appDataDir = File(dataDir)
        require(appDataDir.isDirectory) { "Zygisk app data directory is unavailable: $dataDir" }
        zygiskPayload = ZygiskPayload(apk, appDataDir)
    }

    fun init(hostCtx: Context) {
        ensureNativeLibrariesLoaded()
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        val libLoader = zygiskPayload?.let { zygiskMmkvLibLoader() }
        if (libLoader == null) {
            MMKV.initialize(hostCtx, mmkvDir.toString())
        } else {
            MMKV.initialize(hostCtx, mmkvDir.toString(), libLoader)
        }

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    private fun ensureNativeLibrariesLoaded() {
        synchronized(nativeLoadLock) {
            if (nativeLibrariesLoaded) return@synchronized
            val payload = zygiskPayload
            if (payload == null) {
                // Xposed/Frida paths use the normal installed-APK library lookup.
                System.loadLibrary("dexkit")
                System.loadLibrary("wekit_native")
            } else {
                loadZygiskLibraries(payload)
            }
            nativeLibrariesLoaded = true
        }
    }

    /**
     * InMemoryDexClassLoader has no native-library directory on API 28. Match
     * FunBox's workaround: extract packaged libraries into app data, then use
     * absolute System.load paths from this module ClassLoader.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadZygiskLibraries(payload: ZygiskPayload) {
        val abi = currentProcessAbi(payload.apk)
        val libraryDir = File(payload.dataDir, ".wekit-native-$abi")
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            error("cannot create Zygisk native-library directory: $libraryDir")
        }
        require(libraryDir.isDirectory) { "Zygisk native-library path is not a directory: $libraryDir" }

        val libraries = mutableMapOf<String, File>()
        ZipFile(payload.apk).use { archive ->
            val names = listOf(
                "androidx.graphics.path" to "libandroidx.graphics.path.so",
                "dexkit" to "libdexkit.so",
                "mmkv" to "libmmkv.so",
                "wekit_native" to "libwekit_native.so",
            )
            for (name in names) {
                val (libraryName, fileName) = name
                val entry = archive.getEntry("lib/$abi/$fileName") ?: continue
                val extracted = extractLibrary(archive, entry.name, libraryDir, fileName)
                libraries[libraryName] = extracted
                if (libraryName != "mmkv") {
                    System.load(extracted.absolutePath)
                    Log.i(TAG, "loaded Zygisk native library: ${extracted.name}")
                }
            }
            require(archive.getEntry("lib/$abi/libdexkit.so") != null) {
                "Zygisk payload is missing libdexkit.so for $abi"
            }
            require(archive.getEntry("lib/$abi/libwekit_native.so") != null) {
                "Zygisk payload is missing libwekit_native.so for $abi"
            }
        }
        zygiskNativeLibraries = libraries
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun zygiskMmkvLibLoader(): MMKV.LibLoader = MMKV.LibLoader { libraryName ->
        val library = zygiskNativeLibraries[libraryName]
        if (library != null) {
            System.load(library.absolutePath)
            Log.i(TAG, "loaded Zygisk native library through MMKV loader: ${library.name}")
        } else {
            System.loadLibrary(libraryName)
        }
    }

    private fun currentProcessAbi(apk: File): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.asList()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.asList()
        }
        ZipFile(apk).use { archive ->
            return candidates.firstOrNull { abi ->
                archive.getEntry("lib/$abi/libwekit_native.so") != null
            } ?: error("Zygisk payload has no native library for this process ABI")
        }
    }

    private fun extractLibrary(
        archive: ZipFile,
        entryName: String,
        destinationDir: File,
        libraryName: String,
    ): File {
        val destination = File(destinationDir, libraryName)
        val temporary = File(destinationDir, "$libraryName.${Process.myPid()}.tmp")
        temporary.delete()
        archive.getInputStream(archive.getEntry(entryName)).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        temporary.setReadable(true, true)
        temporary.setExecutable(true, true)
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("cannot publish Zygisk native library: $destination")
        }
        return destination
    }
}
