package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MonetResourceGraphTest {

    @Test
    fun `graph indexes layout and drawable incoming edges`() {
        val colorNode = MonetResourceNode(
            id = 0x7f060111,
            key = MonetResourceKey("color", "a"),
            values = emptyList(),
        )
        val drawableNode = MonetResourceNode(
            id = 0x7f080222,
            key = MonetResourceKey("drawable", "b"),
            values = emptyList(),
        )
        val layoutNode = MonetResourceNode(
            id = 0x7f0d0333,
            key = MonetResourceKey("layout", "c"),
            values = emptyList(),
        )

        val graph = MonetResourceGraph(listOf(colorNode, drawableNode, layoutNode))
            .withXmlReferences(drawableNode.id, setOf(colorNode.id))
            .withXmlReferences(layoutNode.id, setOf(drawableNode.id, colorNode.id))

        assertEquals(setOf(drawableNode.id, layoutNode.id), graph.incoming(colorNode.id))
        assertEquals(setOf(layoutNode.id), graph.incoming(drawableNode.id))
        assertEquals(colorNode, graph.node(colorNode.key))
        assertEquals(listOf(drawableNode), graph.nodes("drawable"))
        assertEquals(setOf(drawableNode.id, layoutNode.id), graph.xmlOwners())
    }

    @Test
    fun `reference signature records a stable token for a cyclic reference`() {
        val colorId = 0x7f060111
        val drawableId = 0x7f080222
        val graph = MonetResourceGraph(
            listOf(
                MonetResourceNode(
                    id = colorId,
                    key = MonetResourceKey("color", "a"),
                    values = listOf(
                        MonetConfiguredValue("", MonetResourceValue.Reference(drawableId)),
                    ),
                ),
                MonetResourceNode(
                    id = drawableId,
                    key = MonetResourceKey("drawable", "b"),
                    values = listOf(
                        MonetConfiguredValue("", MonetResourceValue.Reference(colorId)),
                    ),
                ),
            ),
        )

        assertEquals(
            MonetReferenceSignature(
                "color",
                "reference:REFERENCE:drawable:reference:REFERENCE:color:cycle:color:-:-",
                null,
            ),
            graph.referenceSignature(colorId),
        )
    }

    @Test
    fun `reference structure signature ignores obfuscated file path`() {
        val firstDrawable = 0x7f080111
        val secondDrawable = 0x7f080222
        val firstStyle = 0x7f130333
        val secondStyle = 0x7f130444
        val graph = MonetResourceGraph(
            listOf(
                MonetResourceNode(
                    firstDrawable,
                    MonetResourceKey("drawable", "pressed_a"),
                    listOf(MonetConfiguredValue("", MonetResourceValue.File("res/i/a.xml"))),
                ),
                MonetResourceNode(
                    secondDrawable,
                    MonetResourceKey("drawable", "pressed_b"),
                    listOf(MonetConfiguredValue("", MonetResourceValue.File("res/j/b.xml"))),
                ),
                styleNode(firstStyle, "style_a", firstDrawable),
                styleNode(secondStyle, "style_b", secondDrawable),
            ),
        )

        assertNotEquals(graph.referenceSignature(firstStyle), graph.referenceSignature(secondStyle))
        assertEquals(
            graph.referenceStructureSignature(firstStyle),
            graph.referenceStructureSignature(secondStyle),
        )
        assertEquals(
            "complex:parent:-:item:16842964=reference:REFERENCE:drawable:file:-",
            graph.referenceStructureSignature(firstStyle)?.defaultValue,
        )
    }

    @Test
    fun `graph snapshots supplied nodes and exposed nodes`() {
        val values = mutableListOf(
            MonetConfiguredValue("", MonetResourceValue.Literal("color", 7)),
        )
        val source = mutableListOf(
            MonetResourceNode(0x7f060111, MonetResourceKey("color", "a"), values),
        )
        val graph = MonetResourceGraph(source)

        values.clear()
        source.clear()
        assertThrows(UnsupportedOperationException::class.java) {
            (graph.node(0x7f060111)!!.values as MutableList<MonetConfiguredValue>).clear()
        }

        assertEquals(
            MonetReferenceSignature("color", "literal:color:7", null),
            graph.referenceSignature(0x7f060111),
        )
    }

    @Test
    fun `xml reference update replaces edges without changing previous graph`() {
        val colorId = 0x7f060111
        val drawableId = 0x7f080222
        val layoutId = 0x7f0d0333
        val original = MonetResourceGraph(
            listOf(
                MonetResourceNode(colorId, MonetResourceKey("color", "a"), emptyList()),
                MonetResourceNode(drawableId, MonetResourceKey("drawable", "b"), emptyList()),
                MonetResourceNode(layoutId, MonetResourceKey("layout", "c"), emptyList()),
            ),
        ).withXmlReferences(drawableId, setOf(colorId))
        val updated = original.withXmlReferences(drawableId, setOf(layoutId))

        assertEquals(setOf(drawableId), original.incoming(colorId))
        assertEquals(setOf(colorId), original.outgoing(drawableId))
        assertEquals(emptySet<Int>(), updated.incoming(colorId))
        assertEquals(setOf(drawableId), updated.incoming(layoutId))
        assertEquals(setOf(layoutId), updated.outgoing(drawableId))
    }

    @Test
    fun `graph snapshots xml owners and persists normalized shapes`() {
        val drawableId = 0x7f080222
        val layoutId = 0x7f0d0333
        val drawableShape = MonetXmlShape("a".repeat(64))
        val layoutShape = MonetXmlShape("b".repeat(64))
        val graph = MonetResourceGraph(
            listOf(
                MonetResourceNode(drawableId, MonetResourceKey("drawable", "a"), emptyList()),
                MonetResourceNode(layoutId, MonetResourceKey("layout", "b"), emptyList()),
            ),
        ).withXmlData(drawableId, emptySet(), setOf(drawableShape))
            .withXmlData(layoutId, setOf(drawableId), setOf(layoutShape))

        val exposedOwners = graph.xmlOwners()
        runCatching { (exposedOwners as MutableSet<Int>).clear() }

        assertEquals(setOf(drawableId, layoutId), graph.xmlOwners())
        assertEquals(setOf(drawableShape), graph.xmlShapes(drawableId))
        assertEquals(setOf(layoutShape), graph.xmlShapes(layoutId))
    }

    @Test
    fun `complex value keeps parent map keys and reference edges`() {
        val complexId = 0x7f030001
        val parentId = 0x7f030002
        val referencedId = 0x7f060003
        val complex = MonetResourceValue.Complex(
            parentId = parentId,
            items = listOf(
                MonetComplexValue(0x01010000, MonetResourceValue.Literal("integer", 7)),
                MonetComplexValue(0x01010001, MonetResourceValue.Reference(referencedId)),
            ),
        )
        val graph = MonetResourceGraph(
            listOf(
                MonetResourceNode(
                    complexId,
                    MonetResourceKey("style", "complex"),
                    listOf(MonetConfiguredValue("", complex)),
                ),
            ),
        )

        assertEquals(complex, graph.node(complexId)!!.values.single().value)
        assertEquals(setOf(parentId, referencedId), graph.outgoing(complexId))
    }

    @Test
    fun `canonical graph digest is independent of input ordering`() {
        val first = MonetResourceNode(
            id = 0x7f060001,
            key = MonetResourceKey("color", "surface"),
            values = listOf(
                MonetConfiguredValue("night", MonetResourceValue.Literal("COLOR_ARGB8", 2)),
                MonetConfiguredValue("", MonetResourceValue.Literal("COLOR_ARGB8", 1)),
            ),
        )
        val second = MonetResourceNode(
            id = 0x7f080002,
            key = MonetResourceKey("drawable", "bubble"),
            values = listOf(
                MonetConfiguredValue("", MonetResourceValue.Reference(first.id)),
            ),
        )
        val shapeA = MonetXmlShape("a".repeat(64))
        val shapeB = MonetXmlShape("b".repeat(64))

        val forward = MonetResourceGraph(listOf(first, second))
            .withXmlData(second.id, setOf(first.id), linkedSetOf(shapeA, shapeB))
        val reversed = MonetResourceGraph(
            listOf(
                second,
                first.copy(values = first.values.reversed()),
            ),
        ).withXmlData(second.id, linkedSetOf(first.id), linkedSetOf(shapeB, shapeA))

        assertEquals(forward.resourceDigest(), reversed.resourceDigest())
        assertEquals(
            "dc0f6c8e3cb1eb7f77c859b92cafd9e0695f183779679fe68b2e99b66eeb3904",
            forward.resourceDigest(),
        )
    }

    private fun styleNode(id: Int, name: String, drawableId: Int) = MonetResourceNode(
        id = id,
        key = MonetResourceKey("style", name),
        values = listOf(
            MonetConfiguredValue(
                "",
                MonetResourceValue.Complex(
                    parentId = 0,
                    items = listOf(
                        MonetComplexValue(16842964, MonetResourceValue.Reference(drawableId)),
                    ),
                ),
            ),
        ),
    )
}
