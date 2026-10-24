package dev.ujhhgtg.wekit.bootstrap

import android.annotation.SuppressLint
import android.app.Application
import dalvik.system.BaseDexClassLoader
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.bootstrap.payload.LoaderNatives
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Both injection modes load bootstrap JNI from this owner ClassLoader. */
object BootstrapNativeLibraries {
    private var loadedDirectory: File? = null

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun initialize(application: Application, bootstrapApk: File): File {
        loadedDirectory?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        bootstrapApk.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val directory = File(application.noBackupFilesDir, "wekit-loader/native/$sha256/arm64-v8a")
        check(directory.isDirectory || directory.mkdirs()) { "cannot prepare bootstrap native directory" }
        ZipFile(bootstrapApk).use { zip ->
            val entries = zip.entries().asSequence().filter {
                !it.isDirectory && it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") &&
                    it.name != "lib/arm64-v8a/libwekit_zygisk.so"
            }.toList()
            check(entries.any { it.name.endsWith("/libwekit_loader.so") }) { "bootstrap is missing loader JNI" }
            check(entries.any { it.name.endsWith("/libandroidx.graphics.path.so") }) { "bootstrap is missing graphics JNI" }
            for (entry in entries) {
                val name = entry.name.removePrefix("lib/arm64-v8a/")
                require('/' !in name && '\\' !in name && name !in setOf(".", "..")) { "invalid native entry" }
                val destination = File(directory, name)
                if (destination.isFile && destination.length() == entry.size && crc(destination) == entry.crc) continue
                val temporary = File(directory, "$name.${UUID.randomUUID()}.part")
                try {
                    zip.getInputStream(entry).use { input -> temporary.outputStream().use(input::copyTo) }
                    check(temporary.length() == entry.size && crc(temporary) == entry.crc) { "native entry failed CRC" }
                    check(temporary.setReadable(true, true) && temporary.setExecutable(true, true) &&
                        temporary.setWritable(false, false)) { "cannot protect bootstrap native library" }
                    check(temporary.renameTo(destination)) { "cannot publish bootstrap native library" }
                } finally {
                    temporary.delete()
                }
            }
        }
        // Load the tiny native verifier directly from the freshly extracted copy.
        // It must run before any other bootstrap JNI is accepted.
        LoaderNatives.initialize(File(directory, "libwekit_loader.so"))
        check(LoaderNatives.verifyBootstrap(application, bootstrapApk.absolutePath)) {
            "bootstrap APK/process verification failed"
        }
        val owner = BootstrapNativeLibraries::class.java.classLoader as BaseDexClassLoader
        // LibXposed's module loader supplies its own APK lookup and namespace path.
        // Zygisk's plain memory loader needs a path before its first native load.
        if (listOf("wekit_loader", "androidx.graphics.path").any { owner.findLibrary(it) == null }) {
            installNativeSearchPath(owner, directory)
        }
        for (name in listOf("wekit_loader", "androidx.graphics.path")) {
            System.loadLibrary(name)
        }
        loadedDirectory = directory
        return directory
    }

    @Suppress("UNCHECKED_CAST")
    private fun installNativeSearchPath(owner: BaseDexClassLoader, directory: File) {
        val pathList = owner.reflekt().getField("pathList", superclass = true)!!
        val paths = pathList.reflekt()
        val original = paths.getField("nativeLibraryDirectories") as List<File>
        val directories = (listOf(directory) + original).distinct()
        val systemDirectories = paths.getField("systemNativeLibraryDirectories") as List<File>
        // addNativePath alone leaves getLdLibraryPath empty for Zygisk's memory loader.
        // Install both lookup elements and namespace paths before the first native load.
        paths.setField("nativeLibraryDirectories", directories)
        val elements = paths.firstMethod {
            name = "makePathElements"
            parameters(List::class.java)
        }.invoke(directories + systemDirectories)
        paths.setField("nativeLibraryPathElements", elements)
        for (name in listOf("wekit_loader", "androidx.graphics.path")) {
            val resolved = checkNotNull(owner.findLibrary(name)) {
                val linkerPath = owner.reflekt().firstMethod {
                    this.name = "getLdLibraryPath"
                    parameters()
                    superclass()
                }.invoke()
                "bootstrap library lookup failed: library=$name owner=${owner.javaClass.name} " +
                    "linkerPath=$linkerPath preparedDirectory=$directory " +
                    "fileReadable=${File(directory, "lib$name.so").canRead()}"
            }
            check(File(resolved).canonicalFile == File(directory, "lib$name.so").canonicalFile) {
                "bootstrap library resolves outside its verified directory: $name"
            }
        }
    }

    private fun crc(file: File): Long = CRC32().apply {
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
        }
    }.value
}
