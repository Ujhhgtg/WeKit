package dev.ujhhgtg.wekit.extensions.monet

import java.security.MessageDigest
import java.util.Collections

internal data class MonetResourceKey(val type: String, val name: String)

internal sealed interface MonetResourceValue {
    data class Literal(val valueType: String, val data: Long) : MonetResourceValue
    data class Reference(
        val resourceId: Int,
        val valueType: String = "REFERENCE",
    ) : MonetResourceValue
    data class File(val path: String) : MonetResourceValue
    data class Complex(
        val parentId: Int,
        val items: List<MonetComplexValue>,
    ) : MonetResourceValue
}

internal data class MonetComplexValue(
    val nameId: Int,
    val value: MonetResourceValue,
)

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
    private val xmlDataBySource: Map<Int, MonetXmlData>,
) {
    constructor(nodes: List<MonetResourceNode>) : this(
        nodes.associate { node -> node.id to node.snapshot() },
        emptyMap(),
    )

    fun node(resourceId: Int): MonetResourceNode? = nodesById[resourceId]?.snapshot()

    fun node(key: MonetResourceKey): MonetResourceNode? =
        nodesById.values.firstOrNull { it.key == key }?.snapshot()

    fun nodes(type: String): List<MonetResourceNode> =
        nodesById.values.filter { it.key.type == type }.sortedBy(MonetResourceNode::id).map { it.snapshot() }

    fun xmlOwners(): Set<Int> = xmlDataBySource.keys.toSet()

    fun xmlShapes(sourceId: Int): Set<MonetXmlShape> =
        xmlDataBySource[sourceId]?.shapes.orEmpty().toSet()

    fun withXmlReferences(sourceId: Int, referenceIds: Set<Int>): MonetResourceGraph {
        val existing = xmlDataBySource[sourceId] ?: MonetXmlData()
        return MonetResourceGraph(
            nodesById,
            xmlDataBySource + (sourceId to existing.copy(referenceIds = referenceIds.toSet())),
        )
    }

    fun withXmlData(
        sourceId: Int,
        referenceIds: Set<Int>,
        shapes: Set<MonetXmlShape>,
    ): MonetResourceGraph = MonetResourceGraph(
        nodesById,
        xmlDataBySource + (sourceId to MonetXmlData(referenceIds.toSet(), shapes.toSet())),
    )

    fun incoming(resourceId: Int): Set<Int> = buildSet {
        nodesById.forEach { (sourceId, node) ->
            if (node.values.any { it.value.references(resourceId) }) add(sourceId)
        }
        xmlDataBySource.forEach { (sourceId, xmlData) ->
            if (resourceId in xmlData.referenceIds) add(sourceId)
        }
    }

    fun outgoing(resourceId: Int): Set<Int> = buildSet {
        nodesById[resourceId]?.values?.forEach { configured ->
            addAll(configured.value.referenceIds())
        }
        addAll(xmlDataBySource[resourceId]?.referenceIds.orEmpty())
    }

    fun referenceSignature(resourceId: Int): MonetReferenceSignature? =
        referenceSignature(resourceId, linkedSetOf(), normalizeFilePaths = false)

    fun referenceStructureSignature(resourceId: Int): MonetReferenceSignature? =
        referenceSignature(resourceId, linkedSetOf(), normalizeFilePaths = true)

    /**
     * Canonical digest for exact Monet profiles. The serialization is deliberately independent
     * of APK/split order and ARSC iteration order while retaining IDs, configured values, resource
     * edges, and normalized XML shapes. Any caller selecting an exact profile must use this digest.
     */
    fun resourceDigest(): String {
        val canonical = buildString {
            appendDigestToken("monet-resource-graph-v1")
            nodesById.values
                .sortedBy { it.id.toUInt() }
                .forEach { node ->
                    appendDigestToken("node")
                    appendDigestToken(node.id.toUInt().toString())
                    appendDigestToken(node.key.type)
                    appendDigestToken(node.key.name)
                    node.values
                        .sortedWith(compareBy({ it.qualifiers }, { it.value.digestToken() }))
                        .forEach { configured ->
                            appendDigestToken(configured.qualifiers)
                            appendDigestToken(configured.value.digestToken())
                        }
                    appendDigestToken("node-end")
                }
            xmlDataBySource.entries
                .sortedBy { it.key.toUInt() }
                .forEach { (sourceId, xmlData) ->
                    appendDigestToken("xml")
                    appendDigestToken(sourceId.toUInt().toString())
                    xmlData.referenceIds.sortedBy(Int::toUInt).forEach { referenceId ->
                        appendDigestToken(referenceId.toUInt().toString())
                    }
                    appendDigestToken("references-end")
                    xmlData.shapes.map(MonetXmlShape::sha256).sorted().forEach(::appendDigestToken)
                    appendDigestToken("xml-end")
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private fun referenceSignature(
        resourceId: Int,
        expanding: Set<Int>,
        normalizeFilePaths: Boolean,
    ): MonetReferenceSignature? {
        val node = nodesById[resourceId] ?: return null
        if (resourceId in expanding) {
            return MonetReferenceSignature(node.key.type, "cycle:${node.key.type}", null)
        }
        val nextExpanding = expanding + resourceId
        val defaultValue = node.values
            .filter { it.qualifiers.isEmpty() }
            .sortedBy(MonetConfiguredValue::qualifiers)
            .joinToString("|") { valueSignature(it.value, nextExpanding, normalizeFilePaths) }
            .ifEmpty { null }
        val nightValue = node.values
            .filter { it.qualifiers.split('-').any { qualifier -> qualifier == "night" } }
            .sortedBy(MonetConfiguredValue::qualifiers)
            .joinToString("|") { valueSignature(it.value, nextExpanding, normalizeFilePaths) }
            .ifEmpty { null }
        return MonetReferenceSignature(node.key.type, defaultValue, nightValue)
    }

    private fun valueSignature(
        value: MonetResourceValue,
        expanding: Set<Int>,
        normalizeFilePaths: Boolean,
    ): String = when (value) {
        is MonetResourceValue.Literal -> "literal:${value.valueType}:${value.data}"
        is MonetResourceValue.File -> if (normalizeFilePaths) "file" else "file:${value.path}"
        is MonetResourceValue.Reference -> referenceSignature(
            value.resourceId,
            expanding,
            normalizeFilePaths,
        )?.let {
            "reference:${value.valueType}:${it.type}:${it.defaultValue ?: "-"}:${it.nightValue ?: "-"}"
        } ?: "reference:${value.valueType}:${value.resourceId}"
        is MonetResourceValue.Complex -> buildString {
            append("complex:parent:")
            append(
                if (value.parentId == 0) {
                    "-"
                } else {
                    valueSignature(
                        MonetResourceValue.Reference(value.parentId),
                        expanding,
                        normalizeFilePaths,
                    )
                },
            )
            value.items.forEach { item ->
                append(":item:").append(item.nameId).append('=')
                append(valueSignature(item.value, expanding, normalizeFilePaths))
            }
        }
    }

    private fun MonetResourceValue.references(resourceId: Int): Boolean =
        resourceId in referenceIds()

    private fun MonetResourceValue.referenceIds(): Set<Int> = when (this) {
        is MonetResourceValue.Reference -> setOf(resourceId)
        is MonetResourceValue.Complex -> buildSet {
            if (parentId != 0) add(parentId)
            items.forEach { item -> addAll(item.value.referenceIds()) }
        }
        is MonetResourceValue.Literal,
        is MonetResourceValue.File,
        -> emptySet()
    }

    private data class MonetXmlData(
        val referenceIds: Set<Int> = emptySet(),
        val shapes: Set<MonetXmlShape> = emptySet(),
    )
}

private fun MonetResourceValue.digestToken(): String = when (this) {
    is MonetResourceValue.Literal -> "literal:$valueType:${data.toULong()}"
    is MonetResourceValue.Reference -> "reference:$valueType:${resourceId.toUInt()}"
    is MonetResourceValue.File -> "file:$path"
    is MonetResourceValue.Complex -> buildString {
        append("complex:").append(parentId.toUInt())
        items.sortedWith(compareBy({ it.nameId.toUInt() }, { it.value.digestToken() }))
            .forEach { item ->
                append(':').append(item.nameId.toUInt()).append('=')
                append(item.value.digestToken())
            }
    }
}

private fun StringBuilder.appendDigestToken(value: String) {
    append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
}

private fun MonetResourceNode.snapshot(): MonetResourceNode = copy(
    values = Collections.unmodifiableList(
        values.map { configured -> configured.copy(value = configured.value.snapshot()) },
    ),
)

private fun MonetResourceValue.snapshot(): MonetResourceValue = when (this) {
    is MonetResourceValue.Complex -> copy(
        items = Collections.unmodifiableList(
            items.map { item -> item.copy(value = item.value.snapshot()) },
        ),
    )
    is MonetResourceValue.File,
    is MonetResourceValue.Literal,
    is MonetResourceValue.Reference,
    -> this
}
