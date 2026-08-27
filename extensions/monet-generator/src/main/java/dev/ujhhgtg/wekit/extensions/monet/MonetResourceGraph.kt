package dev.ujhhgtg.wekit.extensions.monet

import java.io.Serializable

data class MonetResourceKey(val type: String, val name: String) : Serializable

sealed interface MonetResourceValue : Serializable {
    data class Literal(val valueType: String, val data: Long) : MonetResourceValue
    data class Reference(val resourceId: Int, val valueType: String = "REFERENCE") : MonetResourceValue
    data class File(val path: String, val structure: MonetFileStructure?) : MonetResourceValue
    data class Text(val value: String) : MonetResourceValue
    data class Complex(val parentId: Int, val items: List<MonetComplexValue>) : MonetResourceValue
}

data class MonetFileStructure(
    val format: String,
    val width: Int? = null,
    val height: Int? = null,
    val colorType: Int? = null,
    val firstDataLength: Int? = null,
    val ninePatchLength: Int? = null,
    val sampleSum: Long? = null,
    val alphaSum: Long? = null,
    val distinctSamples: Int? = null,
    val pixelSha256: String? = null,
) : Serializable

data class MonetComplexValue(val nameId: Int, val value: MonetResourceValue) : Serializable
data class MonetConfiguredValue(val qualifiers: String, val value: MonetResourceValue) : Serializable
data class MonetResourceNode(
    val id: Int,
    val key: MonetResourceKey,
    val values: List<MonetConfiguredValue>,
) : Serializable

data class MonetXmlElement(
    val name: String,
    val namespace: String? = null,
    val attributes: List<MonetXmlAttribute>,
    val children: List<MonetXmlElement>,
) : Serializable

data class MonetXmlAttribute(
    val namespace: String?,
    val name: String,
    val nameId: Int?,
    val valueType: String,
    val value: MonetResourceValue,
) : Serializable

class MonetResourceGraph(
    nodes: List<MonetResourceNode>,
    private val xmlByOwner: Map<Int, List<MonetXmlElement>> = emptyMap(),
) : Serializable {
    private val byId = nodes.associateBy(MonetResourceNode::id)
    private val byKey = nodes.associateBy(MonetResourceNode::key)
    private val outgoingById: Map<Int, Set<Int>> = byId.mapValues { (id, node) ->
        HashSet<Int>().also { references ->
            node.values.forEach { it.value.collectReferences(references) }
            xmlByOwner[id].orEmpty().forEach { it.collectReferences(references) }
        }
    }
    private val incomingById: Map<Int, Set<Int>> = HashMap<Int, MutableSet<Int>>().also { incoming ->
        outgoingById.forEach { (sourceId, targets) ->
            targets.forEach { targetId ->
                incoming.getOrPut(targetId, ::linkedSetOf).add(sourceId)
            }
        }
    }

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
        MonetResourceGraph(byId.values.toList(), xmlByOwner + (ownerId to xmlTrees(ownerId) + tree))

    fun outgoing(id: Int): Set<Int> = outgoingById[id].orEmpty()
    fun incoming(id: Int): Set<Int> = incomingById[id].orEmpty()
}

private fun MonetXmlElement.collectReferences(result: MutableSet<Int>) {
    attributes.forEach { it.value.collectReferences(result) }
    children.forEach { it.collectReferences(result) }
}

private fun MonetResourceValue.collectReferences(result: MutableSet<Int>) {
    when (this) {
        is MonetResourceValue.Reference -> result += resourceId
        is MonetResourceValue.Complex -> {
            if (parentId != 0) result += parentId
            items.forEach { it.value.collectReferences(result) }
        }
        is MonetResourceValue.File,
        is MonetResourceValue.Text,
        is MonetResourceValue.Literal,
        -> Unit
    }
}
