package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class MonetModulePackagerTest {
    @Test
    fun `module contains selected overlays and overlay-only boot restore`() {
        val dir = createTempDirectory("monet-module").toFile()
        val first = File(dir, "Base.apk").apply { writeBytes(byteArrayOf(1)) }
        val second = File(dir, "Bubble.apk").apply { writeBytes(byteArrayOf(2)) }
        val output = File(dir, "module.zip")
        MonetModulePackager.pack(
            listOf(
                MonetModulePackager.Overlay(first, "monet.base"),
                MonetModulePackager.Overlay(second, "monet.bubble"),
            ),
            MonetGenerationOptions(userScope = MonetUserScope.ALL, currentUserId = 10),
            output,
        )
        ZipFile(output).use { zip ->
            assertEquals(
                setOf(
                    "module.prop", "customize.sh", "config.conf", "common.sh", "service.sh",
                    "boot-completed.sh", "system/product/overlay/Base.apk", "system/product/overlay/Bubble.apk",
                ),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val scripts = listOf("common.sh", "service.sh", "boot-completed.sh").joinToString { name ->
                zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
            }
            assertTrue("cmd overlay enable" in scripts)
            assertFalse("tinker" in scripts.lowercase())
        }
    }
}
