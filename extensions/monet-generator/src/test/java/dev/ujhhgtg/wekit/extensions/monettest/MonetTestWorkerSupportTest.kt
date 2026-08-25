package dev.ujhhgtg.wekit.extensions.monettest

import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.extensions.monet.MonetRoleCatalog
import dev.ujhhgtg.wekit.extensions.monet.loadMonetTemplate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
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

    @Test
    fun `Blur validation checks compiled default and night bytes by qualifier`() {
        val payload = File("../../app/embedded/monet")
        val catalog = MonetRoleCatalog.load(payload)
        val definition = catalog.overlays.single { it.id == "blur-tab" }
        val templateKey = requireNotNull(definition.templateResources["main.tab.background"])

        loadMonetTemplate(payload.resolve(definition.templateFile)).use { apk ->
            val pkg = requireNotNull(apk.tableBlock.pickOne())
            val resource = requireNotNull(pkg.getResource(templateKey.type, templateKey.name))
            resource.iterator(false).forEachRemaining { entry ->
                if (!entry.isNull) {
                    val isNight = entry.resConfig.qualifiers.orEmpty().split('-').contains("night")
                    entry.setValueAsRaw(
                        ValueType.COLOR_ARGB8,
                        if (isNight) 0xc7abcdefL.toInt() else 0xb0123456L.toInt(),
                    )
                }
            }

            validateBlurResourceValues(resource, 0xb0123456L.toInt(), 0xc7abcdefL.toInt())

            val night = resource.iterator(false).asSequence().single { entry ->
                !entry.isNull && entry.resConfig.qualifiers.orEmpty().split('-').contains("night")
            }
            night.setValueAsRaw(ValueType.COLOR_ARGB8, 0xb0123456L.toInt())
            val error = assertThrows(IllegalArgumentException::class.java) {
                validateBlurResourceValues(resource, 0xb0123456L.toInt(), 0xc7abcdefL.toInt())
            }
            assertTrue(error.message.orEmpty().contains("night"))
        }
    }

    @Test
    fun `failed Dex collector report retains bounded attempted candidate batch`() {
        val provider = RecordingDexEvidenceProvider { error("collector boom") }
        val candidates = (1..1_001).map { id -> MonetDexCandidate(id, "color", "c$id") }

        val error = assertThrows(IllegalStateException::class.java) {
            provider.query(candidates)
        }
        val report = provider.report()

        assertEquals("collector boom", error.message)
        assertEquals(MonetTestDexEvidenceStatus.FAILED, report.status)
        assertEquals("collector boom", report.reason)
        assertEquals(1, report.queries.size)
        assertEquals(1_001, report.queries.single().requestedCandidates.totalCount)
        assertTrue(report.queries.single().requestedCandidates.truncated)
        assertEquals(0, report.queries.single().evidenceResources.totalCount)
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
