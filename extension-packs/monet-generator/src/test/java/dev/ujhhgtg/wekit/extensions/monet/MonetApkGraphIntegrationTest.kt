package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
        assertEquals(setOf("layout/t8"), graph.incoming(drawable.id).mapTo(linkedSetOf()) {
            val owner = requireNotNull(graph.node(it)).key
            "${owner.type}/${owner.name}"
        })
    }
}
