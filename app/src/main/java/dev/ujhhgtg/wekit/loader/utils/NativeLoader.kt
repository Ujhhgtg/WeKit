package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.wekit.extensions.CloudflaredPack
import dev.ujhhgtg.wekit.extensions.CloudflaredPackNotInstalledException
import dev.ujhhgtg.wekit.loader.utils.NativeLoader.init
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import java.util.zip.ZipFile
import dalvik.system.BaseDexClassLoader
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private data class ZygiskPayload(
        val apk: File,
        val dataDir: File,
    )

    private val nativeLoadLock = Any()
    private var zygiskPayload: ZygiskPayload? = null
    private var zygiskNativeLibraries: Map<String, File> = emptyMap()
    private var nativeLibrariesLoaded = false
    private var nativeArtifactDir: File? = null
    private var materializedInvokeTool: File? = null
    private var materializedChrootCleanup: File? = null

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
        synchronized(nativeLoadLock) {
            nativeArtifactDir = File(hostCtx.filesDir, "wekit-native-artifacts").also {
                if (!it.exists() && !it.mkdirs()) error("cannot create native artifact directory: $it")
            }
        }
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
            if (nativeLibrariesLoaded) {
                return@synchronized
            }
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

    @Volatile
    private var cloudflaredLoaded = false

    /** Whether the cloudflared bridge has been System.load-ed in this process. */
    @JvmStatic
    fun isCloudflaredLoaded(): Boolean = cloudflaredLoaded

    /**
     * Lazily loads the Go cloudflared bridge from the cloudflared extension pack
     * when the built-in read-receipts backend is first used. Throws
     * [dev.ujhhgtg.wekit.extensions.CloudflaredPackNotInstalledException] when the
     * pack has not been downloaded — callers surface the install dialog.
     */
    @JvmStatic
    fun ensureCloudflaredLoaded() {
        if (cloudflaredLoaded) return
        synchronized(nativeLoadLock) {
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

    fun invokeToolExecutable(): File = synchronized(nativeLoadLock) {
        materializedInvokeTool ?: (zygiskNativeLibraries["invoke_tool"]
            ?: (NativeLoader::class.java.classLoader as? BaseDexClassLoader)?.findLibrary("invoke_tool")
                ?.let(::materializePackagedExecutable)
            ?: error("packaged invoke_tool executable is unavailable")).also {
                materializedInvokeTool = it
            }
    }.also { require(it.isFile && it.canExecute()) { "invoke_tool is not executable: $it" } }

    fun chrootCleanupExecutable(): File = synchronized(nativeLoadLock) {
        materializedChrootCleanup ?: (zygiskNativeLibraries["chroot_cleanup"]
            ?: (NativeLoader::class.java.classLoader as? BaseDexClassLoader)?.findLibrary("chroot_cleanup")
                ?.let { materializePackagedExecutable(it, "chroot_cleanup") }
            ?: error("packaged chroot_cleanup executable is unavailable")).also {
                materializedChrootCleanup = it
            }
    }.also { require(it.isFile && it.canExecute()) { "chroot_cleanup is not executable: $it" } }

    private fun materializePackagedExecutable(path: String, name: String = "invoke_tool"): File {
        if (!path.contains("!/")) return File(path)
        val apk = File(path.substringBefore("!/"))
        val entryName = path.substringAfter("!/")
        val destinationDir = nativeArtifactDir ?: error("native loader is not initialized")
        val destination = File(destinationDir, name)
        ZipFile(apk).use { archive ->
            archive.getEntry(entryName) ?: error("packaged invoke_tool entry is missing")
            destination.delete()
            extractLibrary(archive, entryName, destinationDir, destination.name)
        }
        destination.setExecutable(true, true)
        return destination
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
                "invoke_tool" to "libinvoke_tool.so",
                "chroot_cleanup" to "libchroot_cleanup.so",
            )
            for (name in names) {
                val (libraryName, fileName) = name
                val entry = archive.getEntry("lib/$abi/$fileName") ?: continue
                val extracted = extractLibrary(archive, entry.name, libraryDir, fileName)
                libraries[libraryName] = extracted
                if (libraryName != "mmkv" && libraryName != "invoke_tool" && libraryName != "chroot_cleanup") {
                    System.load(extracted.absolutePath)
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
