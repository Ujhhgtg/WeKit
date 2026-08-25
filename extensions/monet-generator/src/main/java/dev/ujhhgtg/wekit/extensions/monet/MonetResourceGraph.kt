package dev.ujhhgtg.wekit.extensions.monet

internal data class MonetResourceKey(val type: String, val name: String)

internal sealed interface MonetResourceValue {
    data class Literal(val valueType: String, val data: Long) : MonetResourceValue
    data class Reference(val resourceId: Int, val valueType: String = "REFERENCE") : MonetResourceValue
    data class File(val path: String) : MonetResourceValue
    data class Complex(val parentId: Int, val items: List<MonetComplexValue>) : MonetResourceValue
}

internal data class MonetComplexValue(val nameId: Int, val value: MonetResourceValue)
internal data class MonetConfiguredValue(val qualifiers: String, val value: MonetResourceValue)
internal data class MonetResourceNode(
    val id: Int,
    val key: MonetResourceKey,
    val values: List<MonetConfiguredValue>,
)

internal data class MonetXmlElement(
    val name: String,
    val namespace: String? = null,
    val attributes: List<MonetXmlAttribute>,
    val children: List<MonetXmlElement>,
)

internal data class MonetXmlAttribute(
    val namespace: String?,
    val name: String,
    val nameId: Int?,
    val valueType: String,
    val value: MonetResourceValue,
)

internal class MonetResourceGraph(
    nodes: List<MonetResourceNode>,
    private val xmlByOwner: Map<Int, List<MonetXmlElement>> = emptyMap(),
) {
    private val byId = nodes.associateBy(MonetResourceNode::id)
    private val byKey = nodes.associateBy(MonetResourceNode::key)

    init {
        require(byId.size == nodes.size) { "duplicate resource ID" }
        require(byKey.size == nodes.size) { "duplicate resource key" }
        require(xmlByOwner.keys.all(byId::containsKey)) { "XML owner is absent from resource table" }
    }

    fun node(id: Int): MonetResourceNode? = byId[id]
    fun node(key: MonetResourceKey): MonetResourceNode? = byKey[key]
    fun nodes(type: String): List<MonetResourceNode> = byId.values.filter { it.key.type == type }
    fun xmlTrees(ownerId: Int): List<MonetXmlElement> = xmlByOwner[ownerId].orEmpty()

    fun withXmlTree(ownerId: Int, tree: MonetXmlElement): MonetResourceGraph =
        MonetResourceGraph(byId.values.toList(), xmlByOwner + (ownerId to (xmlTrees(ownerId) + tree)))

    fun outgoing(id: Int): Set<Int> = buildSet {
        byId[id]?.values?.forEach { addAll(it.value.references()) }
        xmlTrees(id).forEach { addAll(it.references()) }
    }

    fun incoming(id: Int): Set<Int> = byId.keys.filterTo(linkedSetOf()) { id in outgoing(it) }
}

private fun MonetXmlElement.references(): Set<Int> = buildSet {
    attributes.forEach { addAll(it.value.references()) }
    children.forEach { addAll(it.references()) }
}

private fun MonetResourceValue.references(): Set<Int> = when (this) {
    is MonetResourceValue.Reference -> setOf(resourceId)
    is MonetResourceValue.Complex -> buildSet {
        if (parentId != 0) add(parentId)
        items.forEach { addAll(it.value.references()) }
    }
    is MonetResourceValue.File,
    is MonetResourceValue.Literal,
    -> emptySet()
}
