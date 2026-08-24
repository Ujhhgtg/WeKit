package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
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
            MonetReferenceSignature("color", "reference:drawable:reference:color:cycle:color:-:-", null),
            graph.referenceSignature(colorId),
        )
    }
}
