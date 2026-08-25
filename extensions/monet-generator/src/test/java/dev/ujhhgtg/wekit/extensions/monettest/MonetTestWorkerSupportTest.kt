package dev.ujhhgtg.wekit.extensions.monettest

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MonetTestWorkerSupportTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `APKS preparation keeps full resource graph and every dex in deterministic order`() {
        val apks = tempDir.resolve("wechat.apks")
        writeZip(
            apks,
            linkedMapOf(
                "split_delivery.apk" to apkBytes(
                    "resources.arsc" to "delivery-res",
                    "classes.dex" to "delivery-dex",
                ),
                "split_config.zh.apk" to apkBytes("resources.arsc" to "zh-res"),
                "base.apk" to apkBytes(
                    "resources.arsc" to "base-res",
                    "classes2.dex" to "base-dex-2",
                    "classes.dex" to "base-dex-1",
                ),
            ),
        )

        val extractionRoot: File
        prepareApkInput(MonetTestInputKind.APKS, apks, tempDir.resolve("work")).use { prepared ->
            extractionRoot = requireNotNull(prepared.extractionRoot)
            assertEquals(3, prepared.nestedApkCount)
            assertEquals(
                listOf("base.apk", "split_config.zh.apk", "split_delivery.apk"),
                prepared.resourceApks.map { it.sourceName },
            )
            assertEquals(
                listOf("base-dex-1", "base-dex-2", "delivery-dex"),
                prepared.dexBytes.map { it.decodeToString() },
            )
            assertTrue(extractionRoot.isDirectory)
        }
        assertFalse(extractionRoot.exists())
    }

    @Test
    fun `APKS preparation rejects traversal without leaving extraction`() {
        val apks = tempDir.resolve("unsafe.apks")
        writeZip(apks, linkedMapOf("../base.apk" to apkBytes("resources.arsc" to "res")))
        val workRoot = tempDir.resolve("unsafe-work")

        val error = assertThrows(IllegalArgumentException::class.java) {
            prepareApkInput(MonetTestInputKind.APKS, apks, workRoot)
        }

        assertTrue(error.message.orEmpty().contains("unsafe nested APK"))
        assertFalse(workRoot.exists() && workRoot.listFiles().orEmpty().isNotEmpty())
    }

    private fun apkBytes(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun writeZip(output: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value)
                zip.closeEntry()
            }
        }
    }
}
