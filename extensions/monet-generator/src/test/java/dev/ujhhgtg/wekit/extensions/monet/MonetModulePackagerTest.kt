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
        val solid = File(dir, "MonetWeChatSolidTab.apk").apply { writeBytes(byteArrayOf(2)) }
        val blur = File(dir, "MonetWeChatBlurTab.apk").apply { writeBytes(byteArrayOf(3)) }
        val output = File(dir, "module.zip")
        MonetModulePackager.pack(
            listOf(
                MonetModulePackager.Overlay(first, "monet.base"),
                MonetModulePackager.Overlay(solid, "monet.solidtab.com.tencent.mm"),
                MonetModulePackager.Overlay(blur, "monet.blurtab.com.tencent.mm", installInitially = false),
            ),
            MonetGenerationOptions(userScope = MonetUserScope.ALL, currentUserId = 10),
            "8.0.77",
            3100,
            34,
            output,
        )
        ZipFile(output).use { zip ->
            assertEquals(
                setOf(
                    "module.prop", "customize.sh", "config.conf", "common.sh", "action.sh", "service.sh",
                    "boot-completed.sh", "META-INF/com/google/android/update-binary",
                    "META-INF/com/google/android/updater-script", "files/Base.apk",
                    "files/MonetWeChatSolidTab.apk", "files/MonetWeChatBlurTab.apk",
                ),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            val moduleProp = zip.getInputStream(zip.getEntry("module.prop")).bufferedReader().readText()
            assertTrue("name=微信莫奈引擎 (WeKit)" in moduleProp)
            assertTrue("version=8.0.77 (3100)" in moduleProp)
            assertTrue("versionCode=3100" in moduleProp)
            assertTrue("description=为微信 8.0.77 启用动态壁纸取色, 由 WeKit 在运行时生成" in moduleProp)
            val customize = zip.getInputStream(zip.getEntry("customize.sh")).bufferedReader().readText()
            assertTrue("WeChat, now with superpowers" in customize)
            assertTrue("App Profile" in customize)
            assertTrue("export MODULE_HOT_INSTALL_REQUEST=true" in customize)
            assertTrue("system/priv-app/\$name" in customize)
            assertTrue("system/product/overlay" in customize)
            val scripts = listOf("common.sh", "action.sh", "service.sh", "boot-completed.sh").joinToString { name ->
                zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
            }
            assertTrue("cmd overlay enable" in scripts)
            assertTrue("select_tab_overlay" in scripts)
            assertFalse("tinker" in scripts.lowercase())
        }
    }
}
