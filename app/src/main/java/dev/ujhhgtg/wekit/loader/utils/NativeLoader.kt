package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import kotlin.io.path.div
import kotlin.io.path.exists

/** Initializes bundled native libraries and resolves the APK's executable artifacts. */
object NativeLoader {

    private val nativeLoadLock = Any()
    private var zygiskPayload: ZygiskNativePayload? = null
    private var zygiskNativeLibraries: Map<String, File> = emptyMap()
    private var installedNativeLibraryDir: File? = null
    private var nativeLibrariesLoaded = false

    /** Configures the copied Zygisk APK before module startup reaches [init]. */
    @JvmStatic
    fun configureZygiskPayload(apkPath: String, dataDir: String) = synchronized(nativeLoadLock) {
        check(!nativeLibrariesLoaded) { "native libraries were already loaded" }
        val apk = File(apkPath)
        require(apk.isFile && apk.canRead()) { "Zygisk payload APK is unreadable: $apkPath" }
        val appDataDir = File(dataDir)
        require(appDataDir.isDirectory) { "Zygisk app data directory is unavailable: $dataDir" }
        zygiskPayload = ZygiskNativePayload(apk, appDataDir)
    }

    /** The module APK used as the class path for standalone child processes. */
    fun bootstrapApk(): File = synchronized(nativeLoadLock) {
        zygiskPayload?.apk ?: File(StartupInfo.modulePath)
    }

    fun init(hostCtx: Context) {
        val libLoader = synchronized(nativeLoadLock) {
            ensureNativeLibrariesLoaded()
            mmkvLibLoader()
        }
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }
        MMKV.initialize(hostCtx, mmkvDir.toString(), libLoader)
        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    // Called under nativeLoadLock. Publish success only after all startup libraries load.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun ensureNativeLibrariesLoaded() {
        if (nativeLibrariesLoaded) return

        val payload = zygiskPayload
        if (payload == null) {
            val instructionSet = if (Process.is64Bit()) "arm64" else "arm"
            installedNativeLibraryDir = File(
                requireNotNull(File(StartupInfo.modulePath).parentFile),
                "lib/$instructionSet",
            ).also {
                require(it.isDirectory) { "installed WeKit native-library directory is unavailable: $it" }
            }
            for (name in listOf("androidx.graphics.path", "dexkit", "wekit_native")) {
                System.load(installedNativeLibrary(name).absolutePath)
            }
        } else {
            zygiskNativeLibraries = payload.loadLibraries()
        }
        nativeLibrariesLoaded = true
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun mmkvLibLoader(): MMKV.LibLoader = if (zygiskPayload == null) {
        MMKV.LibLoader { name -> System.load(installedNativeLibrary(name).absolutePath) }
    } else {
        MMKV.LibLoader { name ->
            val library = zygiskNativeLibraries[name]
            if (library != null) {
                System.load(library.absolutePath)
            } else {
                System.loadLibrary(name)
            }
        }
    }

    fun invokeToolExecutable(): File = bundledExecutable("invoke_tool")

    fun chrootCleanupExecutable(): File = bundledExecutable("chroot_cleanup")

    // PRoot requires the installed APK's native directory; the Zygisk payload does not provide it.
    fun prootExecutable(): File = synchronized(nativeLoadLock) {
        installedNativeArtifact("proot").requireExecutable("proot")
    }

    fun prootLoaderExecutable(): File = synchronized(nativeLoadLock) {
        installedNativeArtifact("proot_loader").requireExecutable("proot_loader")
    }

    private fun bundledExecutable(name: String): File = synchronized(nativeLoadLock) {
        (zygiskNativeLibraries[name] ?: installedNativeArtifact(name)).requireExecutable(name)
    }

    private fun File.requireExecutable(name: String): File = also {
        require(isFile && canExecute()) { "$name is not executable: $this" }
    }

    private fun installedNativeLibrary(name: String): File = installedNativeArtifact(name).also {
        require(it.isFile && it.canRead()) { "$name is not readable: $it" }
    }

    private fun installedNativeArtifact(name: String): File {
        val directory = installedNativeLibraryDir
            ?: error("packaged $name requires an installed WeKit APK")
        return File(directory, "lib$name.so")
    }
}
