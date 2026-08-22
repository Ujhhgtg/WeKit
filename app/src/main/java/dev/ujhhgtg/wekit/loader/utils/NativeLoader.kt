package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.wekit.extensions.CloudflaredPack
import dev.ujhhgtg.wekit.extensions.CloudflaredPackNotInstalledException
import dev.ujhhgtg.wekit.extensions.LlamaNativePack
import dev.ujhhgtg.wekit.extensions.LlamaPackNotInstalledException
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.loader.utils.NativeLoader.init
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.div
import kotlin.io.path.exists

data class LlamaLaunchFiles(
    val bootstrapApk: File,
    val controllerLibrary: File,
    val childLibrary: File,
)

object NativeLoader {

    private data class ZygiskPayload(
        val apk: File,
        val dataDir: File,
    )

    private val nativeLoadLock = Any()
    private var zygiskPayload: ZygiskPayload? = null
    private var zygiskNativeLibraries: Map<String, File> = emptyMap()
    private var nativeLibrariesLoaded = false
    private var materializedInvokeTool: File? = null
    private var materializedChrootCleanup: File? = null
    private var packagedProot: File? = null
    private var packagedProotLoader: File? = null
    private var installedNativeLibraryDir: File? = null

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
            if (zygiskPayload == null) {
                val instructionSet = if (Process.is64Bit()) "arm64" else "arm"
                installedNativeLibraryDir = File(
                    requireNotNull(File(StartupInfo.modulePath).parentFile),
                    "lib/$instructionSet",
                ).also {
                    require(it.isDirectory) { "installed WeKit native-library directory is unavailable: $it" }
                }
            }
        }
        ensureNativeLibrariesLoaded()
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        val libLoader = if (zygiskPayload == null) installedMmkvLibLoader() else zygiskMmkvLibLoader()
        MMKV.initialize(hostCtx, mmkvDir.toString(), libLoader)

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }

    private fun ensureNativeLibrariesLoaded() {
        synchronized(nativeLoadLock) {
            if (nativeLibrariesLoaded) {
                return@synchronized
            }
            val payload = zygiskPayload
            if (payload == null) {
                System.load(installedNativeLibrary("androidx.graphics.path").absolutePath)
                System.load(installedNativeLibrary("dexkit").absolutePath)
                System.load(installedNativeLibrary("wekit_native").absolutePath)
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

    @Volatile
    private var llamaControllerLoaded = false

    /** Whether the base llama controller library has been mapped in this process. */
    @JvmStatic
    fun isLlamaLoaded(): Boolean = llamaControllerLoaded

    /**
     * Resolves every file needed to launch one inference child. The parent
     * always maps the base library for controller JNI; the fresh app_process
     * child maps the requested base or OpenCL variant independently.
     */
    @JvmStatic
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun prepareLlamaLaunch(backend: String): LlamaLaunchFiles = synchronized(nativeLoadLock) {
        val bootstrap = zygiskPayload?.apk ?: File(StartupInfo.modulePath)
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

    fun invokeToolExecutable(): File = synchronized(nativeLoadLock) {
        materializedInvokeTool ?: (zygiskNativeLibraries["invoke_tool"]
            ?: installedExecutable("invoke_tool")).also {
                materializedInvokeTool = it
            }
    }.also { require(it.isFile && it.canExecute()) { "invoke_tool is not executable: $it" } }

    fun chrootCleanupExecutable(): File = synchronized(nativeLoadLock) {
        materializedChrootCleanup ?: (zygiskNativeLibraries["chroot_cleanup"]
            ?: installedExecutable("chroot_cleanup")).also {
                materializedChrootCleanup = it
            }
    }.also { require(it.isFile && it.canExecute()) { "chroot_cleanup is not executable: $it" } }

    fun prootExecutable(): File = synchronized(nativeLoadLock) {
        packagedProot ?: installedExecutable("proot").also { packagedProot = it }
    }

    fun prootLoaderExecutable(): File = synchronized(nativeLoadLock) {
        packagedProotLoader ?: installedExecutable("proot_loader").also { packagedProotLoader = it }
    }

    private fun installedExecutable(name: String): File {
        return installedNativeArtifact(name).also {
            require(it.isFile && it.canExecute()) { "$name is not executable: $it" }
        }
    }

    private fun installedNativeLibrary(name: String): File {
        return installedNativeArtifact(name).also {
            require(it.isFile && it.canRead()) { "$name is not readable: $it" }
        }
    }

    private fun installedNativeArtifact(name: String): File {
        val directory = installedNativeLibraryDir
            ?: error("packaged $name requires an installed WeKit APK")
        return File(directory, "lib$name.so")
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

    private fun installedMmkvLibLoader(): MMKV.LibLoader = MMKV.LibLoader { libraryName ->
        System.load(installedNativeLibrary(libraryName).absolutePath)
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
