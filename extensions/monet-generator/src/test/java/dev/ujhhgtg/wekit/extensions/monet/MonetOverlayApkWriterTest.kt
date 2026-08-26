package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MonetOverlayApkWriterTest {
    @Test
    fun `runtime S4 drawable specs write without template APKs`() {
        val resolved = MONET_RULES.mapIndexed { index, rule ->
            rule.id to MonetResourceNode(index + 1, MonetResourceKey(rule.type, "target_$index"), emptyList())
        }.toMap()
        val palette = MonetCustomOverlays.Palette(
            0x01060070, 0x0106009b, 0x0106003a, 0x01060041,
            0x0106006c, 0x01060097, 0x01060060, 0x0106008b,
        )
        val drawables = MonetCustomOverlays.baseVisuals(resolved, palette) +
            MonetCustomOverlays.bubbles(resolved, dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle.PRO, palette) +
            MonetCustomOverlays.corners(resolved, palette) +
            MonetCustomOverlays.themedIcon(resolved, palette)
        val output = File(createTempDirectory("monet-s4-writer").toFile(), "overlay.apk")
        MonetOverlayApkWriter.createReferenced(
            output,
            "monet.test.com.tencent.mm",
            34,
            36,
            emptyList(),
            drawables.distinctBy(MonetOverlayApkWriter.DrawableTarget::name),
        )
        ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
            assertTrue(apk.listResFiles().isNotEmpty())
            assertTrue(apk.listResFiles().all { it.isBinaryXml })
        }
    }

    @Test
    fun `writer creates a readable empty overlay resource table`() {
        val dir = createTempDirectory("monet-writer").toFile()
        val output = File(dir, "overlay.apk")
        MonetOverlayApkWriter.createReferenced(
            output,
            "monet.test.com.tencent.mm",
            31,
            33,
            listOf(MonetOverlayApkWriter.ColorTarget("x", 0x0106006c)),
            listOf(
                MonetOverlayApkWriter.DrawableTarget(
                    "bubble",
                    MonetOverlayApkWriter.XmlNode(
                        "shape",
                        children = listOf(
                            MonetOverlayApkWriter.XmlNode(
                                "corners",
                                listOf(
                                    MonetOverlayApkWriter.XmlAttribute(
                                        "radius",
                                        0x010101a8,
                                        MonetOverlayApkWriter.XmlValue.Dimension(16f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
            assertEquals("monet.test.com.tencent.mm", apk.packageName)
            assertEquals("x", apk.tableBlock.pickOne()!!.getResource("color", "x")!!.name)
            assertEquals("bubble", apk.tableBlock.pickOne()!!.getResource("drawable", "bubble")!!.name)
            assertEquals(true, apk.listResFiles().single().isBinaryXml)
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
