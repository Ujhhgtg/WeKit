package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class MonetOverlayApkWriterTest {
    @Test
    fun `runtime S4 drawable specs write without template APKs`() {
        val resolved = MONET_RULES.mapIndexed { index, rule ->
            rule.id to MonetResourceNode(index + 1, MonetResourceKey(rule.type, "target_$index"), emptyList())
        }.toMap()
        val palette = MonetCustomOverlays.Palette(
            surfaceLight = 0x01060070,
            surfaceDark = 0x01060097,
            surfaceContainerLight = 0x01060071,
            surfaceContainerDark = 0x0106009b,
            surfaceContainerHighLight = 0x01060072,
            surfaceContainerHighDark = 0x0106009c,
            primaryLight = 0x01060060,
            primaryDark = 0x0106008b,
            primaryContainerLight = 0x01060061,
            primaryContainerDark = 0x0106008c,
            accent1_300 = 0x0106003a,
            accent1_400 = 0x0106003b,
            accent1_500 = 0x0106003c,
            accent1_700 = 0x0106003e,
            accent2_100 = 0x01060041,
            neutral2_700 = 0x0106006c,
        )
        val groups = mapOf(
            "base" to MonetCustomOverlays.baseVisuals(resolved, palette, 0x7f080001) +
                MonetCustomOverlays.modernBubbles(resolved, palette) +
                MonetCustomOverlays.themedIcon(resolved, palette),
            "pro" to MonetCustomOverlays.proBubbles(resolved, palette),
            "classic" to MonetCustomOverlays.classicBubbles(resolved, palette),
            "corners" to MonetCustomOverlays.corners(resolved, palette),
        )
        val dir = createTempDirectory("monet-s4-writer").toFile()
        groups.forEach { (name, drawables) ->
            val output = File(dir, "$name.apk")
            MonetOverlayApkWriter.createReferenced(
                output,
                "monet.test.$name.com.tencent.mm",
                34,
                36,
                "8.0.77",
                3100,
                20,
                emptyList(),
                drawables.distinctBy(MonetOverlayApkWriter.DrawableTarget::name),
                strings = if (name == "base") listOf(
                    MonetOverlayApkWriter.StringTarget("title", "WeChat Monet Pro"),
                    MonetOverlayApkWriter.StringTarget("title", "WeChat Monet Pro", "-en"),
                ) else emptyList(),
            )
            ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
                assertEquals("manifest", apk.androidManifest.documentElement.name)
                assertTrue(apk.listResFiles().isNotEmpty())
                assertTrue(apk.listResFiles().all { it.isBinaryXml })
                if (name == "base") {
                    val pkg = apk.tableBlock.pickOne()!!
                    val title = pkg.getResource("string", "title")!!
                    assertEquals(2, title.configsCount)
                    assertEquals(0x00000004, title.get().specFlag.integer)
                    val icon = pkg.getResource("mipmap", resolved.getValue("launcher.themed.icon").key.name)!!
                    assertEquals(0x00000500, icon.get().specFlag.integer)
                }
            }
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
            "8.0.77",
            3100,
            10,
            listOf(
                MonetOverlayApkWriter.ColorTarget(
                    "x",
                    MonetOverlayApkWriter.ColorValue.Reference(0x0106006c),
                    null,
                ),
            ),
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
    fun `writer creates a readable light and night literal-only overlay`() {
        val output = File(createTempDirectory("monet-literal").toFile(), "overlay.apk")
        MonetOverlayApkWriter.createReferenced(
            output,
            "monet.blurtab.com.tencent.mm",
            34,
            36,
            "8.0.76",
            3141,
            10,
            emptyList(),
            literalColors = listOf(
                MonetOverlayApkWriter.LiteralColorTarget("df", 0xb0123456.toInt(), 0xc7abcdef.toInt()),
            ),
        )
        ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
            assertEquals(2, apk.tableBlock.pickOne()!!.getResource("color", "df")!!.configsCount)
        }
        val table = ZipFile(output).use { zip -> zip.getInputStream(zip.getEntry("resources.arsc")).readBytes() }
        val packageOffset = 12 + i32(table, 16)
        assertEquals(28, i32(table, 32))
        assertEquals(0, i32(table, packageOffset + 0x110))
        assertEquals(0, i32(table, packageOffset + 0x118))
        var chunkOffset = packageOffset + u16(table, packageOffset + 2)
        while (u16(table, chunkOffset) != 0x0202) chunkOffset += i32(table, chunkOffset + 4)
        assertEquals(2, u16(table, chunkOffset + 10))
        assertEquals(0x00001000, i32(table, chunkOffset + 16))
    }

    @Test
    fun `writer signs API31 and API34 overlays without templates`() {
        listOf(33 to (31 to 33), 34 to (34 to 36)).forEach { (sdk, expected) ->
            val output = File(createTempDirectory("monet-signed").toFile(), "overlay.apk")
            MonetOverlayApkWriter.createSigned(
                output,
                "monet.test.com.tencent.mm",
                sdk,
                "8.0.77",
                3100,
                mapOf("x" to 0xff112233.toInt()),
            )
            ApkModule.loadApkFile(output).apply { setLoadDefaultFramework(false) }.use { apk ->
                assertEquals("manifest", apk.androidManifest.documentElement.name)
                assertEquals("8.0.77", apk.androidManifest.versionName)
                assertEquals(3100, apk.androidManifest.versionCode)
                assertEquals(false, apk.androidManifest.applicationElement.searchAttributeByName("hasCode").valueAsBoolean)
                assertEquals(false, apk.androidManifest.isExtractNativeLibs)
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

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun i32(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}
