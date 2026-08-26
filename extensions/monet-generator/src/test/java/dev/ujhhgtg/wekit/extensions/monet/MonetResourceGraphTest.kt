package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonetResourceGraphTest {
    @Test
    fun `graph retains actual XML structure and typed reference edges`() {
        val color = MonetResourceNode(0x7f060001, MonetResourceKey("color", "obfuscated"), emptyList())
        val drawable = MonetResourceNode(0x7f080001, MonetResourceKey("drawable", "also_obfuscated"), emptyList())
        val xml = MonetXmlElement(
            name = "shape",
            attributes = emptyList(),
            children = listOf(
                MonetXmlElement(
                    name = "solid",
                    attributes = listOf(
                        MonetXmlAttribute(
                            namespace = ANDROID_NAMESPACE,
                            name = "color",
                            nameId = 0x010101a5,
                            valueType = "REFERENCE",
                            value = MonetResourceValue.Reference(color.id),
                        ),
                    ),
                    children = emptyList(),
                ),
            ),
        )

        val graph = MonetResourceGraph(listOf(color, drawable)).withXmlTree(drawable.id, xml)

        assertEquals(listOf(xml), graph.xmlTrees(drawable.id))
        assertEquals(setOf(color.id), graph.outgoing(drawable.id))
        assertEquals(setOf(drawable.id), graph.incoming(color.id))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
