package dev.ujhhgtg.wekit.utils.monet

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonetApkGraphIntegrationTest {
    @Test
    fun `domestic APK exposes actual drawable tree and references`() {
        val apk = File("/home/ujhhgtg/coding/wechat_8065.apk")
        require(apk.isFile) { "missing local WeChat 8.0.65 APK" }

        val graph = MonetApkResourceGraphLoader.load(listOf(apk), "com.tencent.mm")
        val drawable = requireNotNull(graph.node(MonetResourceKey("drawable", "ahj")))
        val tree = graph.xmlTrees(drawable.id).single()

        assertEquals("shape", tree.name)
        assertEquals(listOf("corners", "solid"), tree.children.map(MonetXmlElement::name))
        assertTrue(graph.outgoing(drawable.id).any { graph.node(it)?.key?.type == "color" })
        assertEquals(
            setOf("layout/t8"),
            graph.incoming(drawable.id).mapTo(linkedSetOf()) {
                val owner = requireNotNull(graph.node(it)).key
                "${owner.type}/${owner.name}"
            },
        )
    }

    @Test
    fun `Play splits with placeholder type names merge into the base type pool`() {
        val apks = File("/home/ujhhgtg/coding/wechat_8072_3085_play.apks")
        require(apks.isFile) { "missing local WeChat 8.0.72 (3085) Play APKS" }
        val dir = Files.createTempDirectory("monet-splits").toFile()
        try {
            // bundletool names the types a split doesn't use "?<id>".
            val names = listOf("base.apk", "split_config.xxhdpi.apk", "split_config.en.apk")
            val files =
                ZipFile(apks).use { zip ->
                    names.map { name ->
                        File(dir, name).also { file ->
                            zip.getInputStream(
                                    requireNotNull(zip.getEntry(name)) { "missing $name" },
                                )
                                .use { input ->
                                    file.outputStream().use(input::copyTo)
                                }
                        }
                    }
                }

            val base = MonetApkResourceGraphLoader.load(files.take(1), "com.tencent.mm")
            val graph = MonetApkResourceGraphLoader.load(files, "com.tencent.mm")

            assertEquals(base.typeNames, graph.typeNames)
            assertTrue(graph.typeNames.none { it.startsWith("?") && it != "?18" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
