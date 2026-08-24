package dev.ujhhgtg.wekit.extensions.monet

internal data class MonetResourceKey(val type: String, val name: String)

internal sealed interface MonetResourceValue {
    data class Literal(val valueType: String, val data: Long) : MonetResourceValue
    data class Reference(val resourceId: Int) : MonetResourceValue
    data class File(val path: String) : MonetResourceValue
}

internal data class MonetConfiguredValue(
    val qualifiers: String,
    val value: MonetResourceValue,
)

internal data class MonetResourceNode(
    val id: Int,
    val key: MonetResourceKey,
    val values: List<MonetConfiguredValue>,
)

internal data class MonetReferenceSignature(
    val type: String,
    val defaultValue: String?,
    val nightValue: String?,
)

internal class MonetResourceGraph private constructor(
    private val nodesById: Map<Int, MonetResourceNode>,
    private val xmlReferencesBySource: Map<Int, Set<Int>>,
) {
    constructor(nodes: List<MonetResourceNode>) : this(
        nodes.associate { node -> node.id to node.copy(values = node.values.toList()) },
        emptyMap(),
    )

    fun node(resourceId: Int): MonetResourceNode? = nodesById[resourceId]?.snapshot()

    fun node(key: MonetResourceKey): MonetResourceNode? =
        nodesById.values.firstOrNull { it.key == key }?.snapshot()

    fun nodes(type: String): List<MonetResourceNode> =
        nodesById.values.filter { it.key.type == type }.sortedBy(MonetResourceNode::id).map { it.snapshot() }

    fun xmlOwners(): Set<Int> = xmlReferencesBySource.keys

    fun withXmlReferences(sourceId: Int, referenceIds: Set<Int>): MonetResourceGraph =
        MonetResourceGraph(nodesById, xmlReferencesBySource + (sourceId to referenceIds.toSet()))

    fun incoming(resourceId: Int): Set<Int> = buildSet {
        nodesById.forEach { (sourceId, node) ->
            if (node.values.any { it.value.references(resourceId) }) add(sourceId)
        }
        xmlReferencesBySource.forEach { (sourceId, references) ->
            if (resourceId in references) add(sourceId)
        }
    }

    fun outgoing(resourceId: Int): Set<Int> = buildSet {
        nodesById[resourceId]?.values?.forEach { configured ->
            configured.value.referenceId()?.let(::add)
        }
        addAll(xmlReferencesBySource[resourceId].orEmpty())
    }

    fun referenceSignature(resourceId: Int): MonetReferenceSignature? =
        referenceSignature(resourceId, linkedSetOf())

    private fun referenceSignature(
        resourceId: Int,
        expanding: Set<Int>,
    ): MonetReferenceSignature? {
        val node = nodesById[resourceId] ?: return null
        if (resourceId in expanding) {
            return MonetReferenceSignature(node.key.type, "cycle:${node.key.type}", null)
        }
        val nextExpanding = expanding + resourceId
        val defaultValue = node.values
            .filter { it.qualifiers.isEmpty() }
            .sortedBy(MonetConfiguredValue::qualifiers)
            .joinToString("|") { valueSignature(it.value, nextExpanding) }
            .ifEmpty { null }
        val nightValue = node.values
            .filter { it.qualifiers.split('-').any { qualifier -> qualifier == "night" } }
            .sortedBy(MonetConfiguredValue::qualifiers)
            .joinToString("|") { valueSignature(it.value, nextExpanding) }
            .ifEmpty { null }
        return MonetReferenceSignature(node.key.type, defaultValue, nightValue)
    }

    private fun valueSignature(value: MonetResourceValue, expanding: Set<Int>): String = when (value) {
        is MonetResourceValue.Literal -> "literal:${value.valueType}:${value.data}"
        is MonetResourceValue.File -> "file:${value.path}"
        is MonetResourceValue.Reference -> referenceSignature(value.resourceId, expanding)?.let {
            "reference:${it.type}:${it.defaultValue ?: "-"}:${it.nightValue ?: "-"}"
        } ?: "reference:${value.resourceId}"
    }

    private fun MonetResourceValue.references(resourceId: Int): Boolean =
        referenceId() == resourceId

    private fun MonetResourceValue.referenceId(): Int? = when (this) {
        is MonetResourceValue.Reference -> resourceId
        is MonetResourceValue.Literal,
        is MonetResourceValue.File,
        -> null
    }

    private fun MonetResourceNode.snapshot(): MonetResourceNode = copy(values = values.toList())
}
