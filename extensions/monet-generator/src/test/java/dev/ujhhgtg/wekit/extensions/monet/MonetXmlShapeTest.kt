package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonetXmlShapeTest {

    @Test
    fun `xml shape ignores obfuscated names and concrete resource ids`() {
        val first = rawShape(root = "shape", colorId = 0x7f060111, drawableId = 0x7f080222)
        val second = rawShape(root = "shape", colorId = 0x7f060aaa, drawableId = 0x7f080bbb)

        assertEquals(
            normalizeMonetXml(first, ::firstReferenceSignature),
            normalizeMonetXml(second, ::secondReferenceSignature),
        )
    }

    @Test
    fun `xml shape preserves framework reference ids`() {
        val first = MonetRawXmlElement(
            name = "shape",
            attributes = listOf(
                MonetRawXmlAttribute(
                    namespace = "android",
                    name = "tint",
                    nameId = 0x010101a5,
                    valueType = "reference",
                    value = MonetResourceValue.Reference(0x0106000d),
                ),
            ),
        )
        val second = first.copy(
            attributes = first.attributes.map {
                it.copy(value = MonetResourceValue.Reference(0x0106000e))
            },
        )

        assertEquals(false, normalizeMonetXml(first) == normalizeMonetXml(second))
    }

    private fun rawShape(root: String, colorId: Int, drawableId: Int) = MonetRawXmlElement(
        name = root,
        attributes = listOf(
            MonetRawXmlAttribute(
                namespace = null,
                name = "fill",
                nameId = null,
                valueType = "reference",
                value = MonetResourceValue.Reference(colorId),
            ),
            MonetRawXmlAttribute(
                namespace = "android",
                name = "drawable",
                nameId = 0x0101019d,
                valueType = "reference",
                value = MonetResourceValue.Reference(drawableId),
            ),
        ),
        children = listOf(MonetRawXmlChild.Text("opaque")),
    )

    private fun firstReferenceSignature(id: Int) = when (id) {
        0x7f060111 -> MonetReferenceSignature("color", "literal:28:4278190080", null)
        0x7f080222 -> MonetReferenceSignature("drawable", "file:res/drawable/a.xml", null)
        else -> error("Unexpected reference $id")
    }

    private fun secondReferenceSignature(id: Int) = when (id) {
        0x7f060aaa -> MonetReferenceSignature("color", "literal:28:4278190080", null)
        0x7f080bbb -> MonetReferenceSignature("drawable", "file:res/drawable/a.xml", null)
        else -> error("Unexpected reference $id")
    }
}
