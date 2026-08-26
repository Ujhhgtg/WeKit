package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MonetOverlayApkWriterTest {
    @Test
    fun `writer creates a readable empty overlay resource table`() {
        val dir = createTempDirectory("monet-writer").toFile()
        val output = File(dir, "overlay.apk")
        MonetOverlayApkWriter.create(output, "monet.test.com.tencent.mm", 31, 33, mapOf("x" to 0xff112233.toInt()))
        ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
            assertEquals("monet.test.com.tencent.mm", apk.packageName)
            assertEquals("x", apk.tableBlock.pickOne()!!.getResource("color", "x")!!.name)
        }
    }

    @Test
    fun `writer signs API31 and API34 overlays without templates`() {
        listOf(33 to (31 to 33), 34 to (34 to 36)).forEach { (sdk, expected) ->
            val output = File(createTempDirectory("monet-signed").toFile(), "overlay.apk")
            MonetOverlayApkWriter.createSigned(
                output,
                "monet.test.com.tencent.mm",
                sdk,
                mapOf("x" to 0xff112233.toInt()),
            )
            ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
                assertEquals(expected.first, apk.androidManifest.minSdkVersion)
                assertEquals(expected.second, apk.androidManifest.targetSdkVersion)
                assertEquals(
                    "com.tencent.mm",
                    apk.androidManifest.manifestElement.getElement("overlay")
                        .searchAttributeByName("targetPackage").valueAsString,
                )
                assertEquals(true, apk.hasSignatureBlock())
            }
        }
    }
}
