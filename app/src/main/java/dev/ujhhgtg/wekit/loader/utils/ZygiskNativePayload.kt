package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import dev.ujhhgtg.wekit.utils.fs.copyFrom
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Native artifacts from the copied APK; accessed under NativeLoader's load lock. */
class ZygiskNativePayload(val apk: File, private val dataDir: File) {

    /**
     * InMemoryDexClassLoader has no native-library directory on API 28. Match
     * FunBox's workaround: extract packaged libraries into app data, then use
     * absolute System.load paths from this module ClassLoader.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibraries(): Map<String, File> = ZipFile(apk).use { archive ->
        val abi = currentProcessAbi(archive)
        val libraryDir = File(dataDir, ".wekit-native-$abi")
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            error("cannot create Zygisk native-library directory: $libraryDir")
        }
        require(libraryDir.isDirectory) { "Zygisk native-library path is not a directory: $libraryDir" }

        val libraries = mutableMapOf<String, File>()
        val names = listOf(
            "androidx.graphics.path",
            "dexkit",
            "mmkv",
            "wekit_native",
            "invoke_tool",
            "chroot_cleanup",
        )
        for (name in names) {
            val fileName = "lib$name.so"
            val entry = archive.getEntry("lib/$abi/$fileName") ?: continue
            val extracted = extractLibrary(archive, entry, libraryDir, fileName)
            libraries[name] = extracted
            // MMKV loads through its callback; executable artifacts must never be dlopen-ed.
            when (name) {
                "androidx.graphics.path", "dexkit", "wekit_native" -> System.load(extracted.absolutePath)
            }
        }
        for (name in listOf("dexkit", "wekit_native")) {
            require(name in libraries) { "Zygisk payload is missing lib$name.so for $abi" }
        }
        libraries
    }

    private fun currentProcessAbi(archive: ZipFile): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.asList()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.asList()
        }
        return candidates.firstOrNull { abi ->
            archive.getEntry("lib/$abi/libwekit_native.so") != null
        } ?: error("Zygisk payload has no native library for this process ABI")
    }

    private fun extractLibrary(
        archive: ZipFile,
        entry: ZipEntry,
        destinationDir: File,
        fileName: String,
    ): File {
        val destination = File(destinationDir, fileName)
        val temporary = File(destinationDir, "$fileName.${Process.myPid()}.tmp")
        temporary.delete()
        archive.getInputStream(entry).use { input ->
            temporary.toPath().copyFrom(input)
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
