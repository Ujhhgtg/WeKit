package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class MonetModulePackagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `module contains only selected API 31 overlays and state scripts`() {
        val output = pack(
            sdkInt = 33,
            options = options(MonetBubbleStyle.PRO, corners = false, MonetTabStyle.SOLID),
            overlayIds = listOf("base-api31", "pro-bubble", "solid-tab"),
        )

        ZipFile(output).use { zip ->
            assertEquals(
                setOf(
                    "module.prop",
                    "config.conf",
                    "customize.sh",
                    "common.sh",
                    "service.sh",
                    "boot-completed.sh",
                    "monet-resolution.json",
                    "META-INF/com/google/android/update-binary",
                    "META-INF/com/google/android/updater-script",
                    "system/product/overlay/MonetWeChat.apk",
                    "system/product/overlay/MonetWeChatBubblePro.apk",
                    "system/product/overlay/MonetWeChatSolidTab.apk",
                ),
                zip.entries().asSequence().map(ZipEntry::getName).toSet(),
            )
            assertEquals(
                """
                    bubble_style=PRO
                    multi_scene_corners_enabled=false
                    tab_style=SOLID
                    user_scope=CURRENT
                    generated_user_id=10
                """.trimIndent() + "\n",
                zip.readText("config.conf"),
            )
            assertEquals("{\"fixture\":true}\n", zip.readText("monet-resolution.json"))
            val moduleProp = zip.readText("module.prop")
            assertTrue(moduleProp.contains("枯れ木, H_1e93d, HSSkyBoy"))
            assertTrue(moduleProp.contains("WeKit runtime adaptation"))

            val scripts = listOf("customize.sh", "common.sh", "service.sh", "boot-completed.sh")
                .joinToString("\n") { zip.readText(it).lowercase() }
            listOf("aapt2", "tinker", "build_monet_overlay", "cmd overlay lookup").forEach { forbidden ->
                assertFalse(scripts.contains(forbidden), "generated module contains $forbidden")
            }
        }
    }

    @Test
    fun `API 34 overlays use one priv-app directory per APK stem`() {
        val output = pack(
            sdkInt = 34,
            options = options(MonetBubbleStyle.CLASSIC, corners = true, MonetTabStyle.BLUR),
            overlayIds = listOf("base-api34", "classic-bubble", "multi-scene-corners", "blur-tab"),
        )

        ZipFile(output).use { zip ->
            assertEquals(
                setOf(
                    "system/priv-app/MonetWeChat/MonetWeChat.apk",
                    "system/priv-app/MonetWeChatClassicBubble/MonetWeChatClassicBubble.apk",
                    "system/priv-app/MonetWeChatMultiSceneCorners/MonetWeChatMultiSceneCorners.apk",
                    "system/priv-app/MonetWeChatBlurTab/MonetWeChatBlurTab.apk",
                ),
                zip.entries().asSequence().map(ZipEntry::getName)
                    .filter { it.startsWith("system/") }
                    .toSet(),
            )
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("system/product/") })
        }
    }

    @Test
    fun `module ZIP bytes order metadata and modes are reproducible`() {
        val options = options(MonetBubbleStyle.CLASSIC, corners = true, MonetTabStyle.BLUR)
        val overlays = listOf("base-api31", "classic-bubble", "multi-scene-corners", "blur-tab")
        val first = pack(33, options, overlays, outputName = "first.zip")
        val second = pack(33, options, overlays.reversed(), outputName = "second.zip")

        assertArrayEquals(first.readBytes(), second.readBytes())
        ZipFile(first).use { zip ->
            val names = zip.entries().asSequence().map(ZipEntry::getName).toList()
            assertEquals(names.sorted(), names)
            assertTrue(zip.entries().asSequence().all { it.method == ZipEntry.STORED })
        }
        val modes = readCentralDirectoryModes(first)
        assertEquals(0b1000000111101101, modes.getValue("service.sh"))
        assertEquals(0b1000000111101101, modes.getValue("boot-completed.sh"))
        assertEquals(0b1000000111101101, modes.getValue("common.sh"))
        assertEquals(0b1000000111101101, modes.getValue("customize.sh"))
        assertEquals(0b1000000111101101, modes.getValue("META-INF/com/google/android/update-binary"))
        assertEquals(0b1000000110100100, modes.getValue("config.conf"))
        assertEquals(0b1000000110100100, modes.getValue("system/product/overlay/MonetWeChat.apk"))
    }

    @Test
    fun `packager rejects extra overlays and invalid generated user`() {
        val options = options(MonetBubbleStyle.MODERN, corners = false, MonetTabStyle.SOLID)
        val extraOutput = tempDir.resolve("extra.zip")
        assertThrows(IllegalArgumentException::class.java) {
            packager(33).pack(
                signedOverlays = overlays("base-api31", "classic-bubble", "solid-tab"),
                options = options,
                generatedUserId = 10,
                diagnosticsFile = diagnostics(),
                outputZip = extraOutput,
            )
        }
        assertFalse(extraOutput.exists())

        val invalidUserOutput = tempDir.resolve("invalid-user.zip")
        assertThrows(IllegalArgumentException::class.java) {
            packager(33).pack(
                signedOverlays = overlays("base-api31", "solid-tab"),
                options = options,
                generatedUserId = -1,
                diagnosticsFile = diagnostics(),
                outputZip = invalidUserOutput,
            )
        }
        assertFalse(invalidUserOutput.exists())
    }

    private fun pack(
        sdkInt: Int,
        options: MonetGenerationOptions,
        overlayIds: List<String>,
        outputName: String = "module.zip",
    ): File = tempDir.resolve(outputName).also { output ->
        packager(sdkInt).pack(
            signedOverlays = overlays(*overlayIds.toTypedArray()),
            options = options,
            generatedUserId = 10,
            diagnosticsFile = diagnostics(),
            outputZip = output,
        )
    }

    private fun packager(sdkInt: Int) = MonetModulePackager(
        payloadDir = PAYLOAD_DIR,
        versionName = "8.0.72",
        versionCode = 3084,
        sdkInt = sdkInt,
    )

    private fun overlays(vararg ids: String): List<MonetBuiltOverlay> = ids.mapIndexed { index, id ->
        val identity = OVERLAYS.getValue(id)
        val apk = tempDir.resolve("$index-${identity.second}").apply {
            writeText("signed fixture for $id\n")
        }
        MonetBuiltOverlay(
            overlayId = id,
            packageName = identity.first,
            fileName = identity.second,
            file = apk,
            roleIds = emptySet(),
            skippedRoleIds = emptySet(),
            kept = 1,
            added = 0,
            rewritten = 1,
            skipped = 0,
            diagnostics = MonetOverlayBuildDiagnostics(),
        )
    }

    private fun diagnostics(): File = tempDir.resolve("diagnostics.json").apply {
        writeText("{\"fixture\":true}\n")
    }

    private fun options(
        bubbleStyle: MonetBubbleStyle,
        corners: Boolean,
        tabStyle: MonetTabStyle,
    ) = MonetGenerationOptions(bubbleStyle, corners, tabStyle, MonetUserScope.CURRENT)

    private fun ZipFile.readText(name: String): String =
        getInputStream(requireNotNull(getEntry(name))).bufferedReader().use { it.readText() }

    private fun readCentralDirectoryModes(zip: File): Map<String, Int> {
        val bytes = zip.readBytes()
        val eocd = (bytes.size - 22 downTo 0).first { bytes.intLe(it) == 0x06054b50 }
        val entryCount = bytes.shortLe(eocd + 10)
        var offset = bytes.intLe(eocd + 16)
        return buildMap {
            repeat(entryCount) {
                assertEquals(0x02014b50, bytes.intLe(offset))
                val nameLength = bytes.shortLe(offset + 28)
                val extraLength = bytes.shortLe(offset + 30)
                val commentLength = bytes.shortLe(offset + 32)
                val externalAttributes = bytes.intLe(offset + 38)
                val name = bytes.copyOfRange(offset + 46, offset + 46 + nameLength).toString(Charsets.UTF_8)
                put(name, externalAttributes ushr 16)
                offset += 46 + nameLength + extraLength + commentLength
            }
        }
    }

    private fun ByteArray.shortLe(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.intLe(offset: Int): Int =
        shortLe(offset) or (shortLe(offset + 2) shl 16)

    private companion object {
        val PAYLOAD_DIR = File("../../app/embedded/monet")
        val OVERLAYS = mapOf(
            "base-api31" to ("monet.com.tencent.mm" to "MonetWeChat.apk"),
            "base-api34" to ("monet.com.tencent.mm" to "MonetWeChat.apk"),
            "classic-bubble" to ("monet.classicbubble.com.tencent.mm" to "MonetWeChatClassicBubble.apk"),
            "pro-bubble" to ("monet.bubblepro.com.tencent.mm" to "MonetWeChatBubblePro.apk"),
            "multi-scene-corners" to (
                "monet.multiscenecorners.com.tencent.mm" to "MonetWeChatMultiSceneCorners.apk"
            ),
            "solid-tab" to ("monet.solidtab.com.tencent.mm" to "MonetWeChatSolidTab.apk"),
            "blur-tab" to ("monet.blurtab.com.tencent.mm" to "MonetWeChatBlurTab.apk"),
        )
    }
}
