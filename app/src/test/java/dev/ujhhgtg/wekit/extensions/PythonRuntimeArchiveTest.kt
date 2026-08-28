package dev.ujhhgtg.wekit.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PythonRuntimeArchiveTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `inspect returns every contiguous DEX in numeric order`() {
        val apk = writeRuntimeApk(listOf("classes2.dex", "classes.dex"))

        val contents = PythonRuntimeArchive.inspect(apk, PythonRuntimeArchive.encodeMetadata(metadata))
        val sdk = File(directory, "sdk")
        PythonRuntimeArchive.extractSdk(apk, sdk)

        assertEquals(listOf("classes.dex", "classes2.dex"), contents.dexEntries)
        assertEquals(listOf("lib/arm64-v8a/libprobe.so"), contents.nativeEntries)
        assertEquals(true, File(sdk, "wekit/__init__.py").isFile)
    }

    @Test
    fun `inspect rejects a DEX sequence gap`() {
        val apk = writeRuntimeApk(listOf("classes.dex", "classes3.dex"))

        assertThrows(IllegalArgumentException::class.java) {
            PythonRuntimeArchive.inspect(apk, PythonRuntimeArchive.encodeMetadata(metadata))
        }
    }

    private fun writeRuntimeApk(dexNames: List<String>): File {
        val apk = File(directory, "runtime.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            dexNames.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("Ldev/ujhhgtg/wekit/python/runtime/RuntimeEntrypoint;".encodeToByteArray())
                zip.closeEntry()
            }
            mapOf(
                "assets/chaquopy/build.json" to "{}".encodeToByteArray(),
                "assets/chaquopy/app.imy" to sdkArchive(),
                "assets/runtime-manifest.json" to PythonRuntimeArchive.encodeMetadata(metadata).encodeToByteArray(),
                "lib/arm64-v8a/libprobe.so" to byteArrayOf(1, 2, 3),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return apk
    }

    private fun sdkArchive(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("wekit/__init__.py"))
            zip.write(byteArrayOf())
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private val metadata = PythonRuntimeMetadata(
        packId = PythonRuntimePack.ID,
        chaquopy = "17.0.0",
        agp = "9.3.1",
        gradle = "9.7.0",
        jdk = "21",
        python = "3.13",
        ndk = "30.0.14904198",
        abi = PythonRuntimePack.ABI,
        patchRevision = "test",
        syncHookBudgetMs = 1,
        taskDrainTimeoutMs = 1,
        maxManifestBytes = 1,
        maxPluginFileBytes = 1,
        nativeLibraries = listOf("libprobe.so"),
    )
}
