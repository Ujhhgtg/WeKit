package dev.ujhhgtg.wekit.utils.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonetEvidenceTest {
    private val color = resource(100, "color", MonetResourceValue.Literal("COLOR_ARGB8", 42))
    private val adjacent = resource(101, "color", MonetResourceValue.Literal("COLOR_ARGB8", 24))
    private val drawable = resource(200, "drawable")
    private val layout = resource(300, "layout")
    private val container = resource(302, "layout")
    private val text = resource(400, "string", MonetResourceValue.Text("label:with:colons"))
    private val style = resource(500, "style", MonetResourceValue.Complex(
        parentId = color.id,
        items = listOf(MonetComplexValue(7, MonetResourceValue.Complex(
            parentId = 0,
            items = listOf(MonetComplexValue(8, MonetResourceValue.Reference(color.id, "DYNAMIC_REFERENCE"))),
        ))),
    ))
    private val nodes = listOf(color, adjacent, drawable, layout, container, text, style)
    private val graph = MonetResourceGraph(nodes, mapOf(
        drawable.id to listOf(element("shape", children = listOf(
            element("solid", listOf(reference("color", color.id))),
            element("stroke", listOf(reference("color", adjacent.id))),
        ))),
        layout.id to listOf(element("example.Root", listOf(
            reference("background", drawable.id), reference("text", text.id), reference("theme", 0x01030000),
        ), listOf(element("example.Label", listOf(reference("textColor", color.id)))))),
        container.id to listOf(element("Container", listOf(reference("layout", layout.id)))),
    ))

    @Test
    fun `requested evidence preserves local usage and every relationship depth`() {
        val expected = setOf(
            "config::literal:COLOR_ARGB8:42",
            "incoming:drawable",
            "incoming:style",
            "usage:owner:style:item:7:item:8:reference",
            "usage:layout:example.Root/example.Label:null:textColor",
            "simple-usage:layout:Root/Label:null:textColor",
            "adjacent:1:config::literal:COLOR_ARGB8:24",
            "context:drawable:element:shape/solid",
            "context:layout:attribute:example.Root:null:theme:REFERENCE:reference:framework:REFERENCE",
            "context:layout:usage:layout:Container:null:layout",
            "context:layout:simple-usage:layout:Container:null:layout",
            "sibling:drawable:color:config::literal:COLOR_ARGB8:24",
            "sibling:layout:string:config::text:label:with:colons",
        )
        assertTrue(MonetStructureMatcher.evidence(color, graph).containsAll(expected))
        assertEquals(expected, MonetStructureMatcher.evidence(color, graph, expected + setOf(
            "adjacent:2:config::literal:COLOR_ARGB8:24",
            "sibling:drawable:color:config::literal:COLOR_ARGB8:42", // A resource is not its own sibling.
            "usage:owner:style:reference", // A complex parent contributes an edge, not an item use.
            "context:layout:usage:layout:Container:null:missing",
        )))

        val children = setOf(
            "child:drawable:element:shape/solid",
            "child:drawable:color:config::literal:COLOR_ARGB8:42",
            "child:drawable:color:config::literal:COLOR_ARGB8:24",
        )
        assertEquals(children, MonetStructureMatcher.evidence(layout, graph, children + setOf(
            "child:color:config::literal:COLOR_ARGB8:24",
            "child:drawable:color:config::literal:COLOR_ARGB8:99",
        )))
    }

    @Test
    fun `projected scan equals exhaustive evidence across XML and complex references`() {
        val vocabulary = nodes.flatMapTo(linkedSetOf()) { MonetStructureMatcher.evidence(it, graph) }
        val selections = listOf(vocabulary, vocabulary.filterIndexed { index, _ -> index % 3 == 0 }.toSet(), emptySet())
        nodes.forEach { node ->
            val exhaustive = MonetStructureMatcher.evidence(node, graph)
            selections.forEach { requested ->
                assertEquals(exhaustive.intersect(requested), MonetStructureMatcher.evidence(node, graph, requested), node.key.toString())
            }
        }
    }

    private fun resource(id: Int, type: String, value: MonetResourceValue? = null) = MonetResourceNode(
        id, MonetResourceKey(type, "resource$id"), value?.let { listOf(MonetConfiguredValue("", it)) }.orEmpty(),
    )

    private fun reference(name: String, id: Int) = MonetXmlAttribute(
        null, name, null, "REFERENCE", MonetResourceValue.Reference(id),
    )

    private fun element(
        name: String,
        attributes: List<MonetXmlAttribute> = emptyList(),
        children: List<MonetXmlElement> = emptyList(),
    ) = MonetXmlElement(name, attributes = attributes, children = children)
}
