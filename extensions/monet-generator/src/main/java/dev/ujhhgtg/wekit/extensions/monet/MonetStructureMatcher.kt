package dev.ujhhgtg.wekit.extensions.monet

internal object MonetStructureMatcher {
    fun resolveAll(graph: MonetResourceGraph): Map<String, MonetResourceNode> {
        val requiredByType = MONET_RULES.groupBy(MonetSemanticRule::type).mapValues { (_, rules) ->
            rules.flatMapTo(hashSetOf(), MonetSemanticRule::requiredEvidence)
        }
        val idsByToken = HashMap<String, MutableSet<Int>>()
        requiredByType.forEach { (type, required) ->
            graph.nodes(type).forEach { node ->
                calculateEvidence(node, graph).forEach { token ->
                    if (token in required) idsByToken.getOrPut(token, ::linkedSetOf).add(node.id)
                }
            }
        }
        return MONET_RULES.associate { rule ->
            val candidateIds = rule.requiredEvidence.map { idsByToken[it].orEmpty() }
                .reduce { result, ids -> result.intersect(ids) }
            require(candidateIds.size == 1) { "${rule.id}: ${candidateIds.mapNotNull(graph::node).map { it.key }}" }
            rule.id to requireNotNull(graph.node(candidateIds.single()))
        }
    }

    fun evidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> = calculateEvidence(node, graph)

    private fun calculateEvidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> = HashSet<String>().apply {
        addAll(localEvidence(node, graph))
        addAll(usageEvidence(node, graph))
        graph.incoming(node.id).mapNotNull(graph::node).forEach { owner ->
            localEvidence(owner, graph).forEach { add("context:${owner.key.type}:$it") }
            usageEvidence(owner, graph).forEach { add("context:${owner.key.type}:$it") }
        }
    }

    private fun localEvidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> = HashSet<String>().apply {
        node.values.forEach { configured ->
            add("config:${configured.qualifiers}:${configured.value.evidence(graph)}")
        }
        graph.xmlTrees(node.id).forEach { it.collectEvidence("", graph, this) }
        graph.outgoing(node.id).mapNotNull(graph::node).forEach { add("outgoing:${it.key.type}") }
    }

    private fun usageEvidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> = HashSet<String>().apply {
        graph.incoming(node.id).mapNotNull(graph::node).forEach { owner ->
            add("incoming:${owner.key.type}")
            owner.values.forEach { configured ->
                configured.value.collectUsage(node.id, "owner:${owner.key.type}", graph, this)
            }
            graph.xmlTrees(owner.id).forEach { tree ->
                tree.collectUsage(node.id, "", owner.key.type, graph, this)
            }
        }
    }

    fun candidates(
        reference: MonetResourceNode,
        referenceGraph: MonetResourceGraph,
        targetGraph: MonetResourceGraph,
    ): List<MonetResourceNode> {
        val expected = feature(reference, referenceGraph)
        return targetGraph.nodes(reference.key.type).filter { feature(it, targetGraph) == expected }
    }

    private fun feature(node: MonetResourceNode, graph: MonetResourceGraph) = ResourceFeature(
        values = node.values.map { ConfigFeature(it.qualifiers, it.value.feature(graph)) }.sortedBy { it.qualifiers },
        xml = graph.xmlTrees(node.id).map { it.feature(graph) },
    )

    private fun MonetXmlElement.feature(graph: MonetResourceGraph): XmlFeature = XmlFeature(
        name = name,
        attributes = attributes.map { attribute ->
            AttributeFeature(
                nameId = attribute.nameId,
                name = attribute.name,
                valueType = attribute.valueType,
                value = attribute.value.feature(graph),
            )
        }.sortedWith(compareBy({ it.nameId }, { it.name }, { it.valueType }, { it.value.toString() })),
        children = children.map { it.feature(graph) },
    )

    private fun MonetResourceValue.feature(graph: MonetResourceGraph): ValueFeature = when (this) {
        is MonetResourceValue.Reference -> ValueFeature(
            kind = "reference",
            type = graph.node(resourceId)?.key?.type ?: "framework",
            valueType = valueType,
        )
        is MonetResourceValue.Literal -> ValueFeature(
            kind = "literal",
            type = null,
            valueType = valueType,
        )
        is MonetResourceValue.File -> ValueFeature("file", null, "FILE")
        is MonetResourceValue.Text -> ValueFeature("text", null, "STRING", text = value)
        is MonetResourceValue.Complex -> ValueFeature(
            kind = "complex",
            type = graph.node(parentId)?.key?.type,
            valueType = "COMPLEX",
            items = items.map { it.nameId to it.value.feature(graph) },
        )
    }

    private data class ResourceFeature(val values: List<ConfigFeature>, val xml: List<XmlFeature>)
    private data class ConfigFeature(val qualifiers: String, val value: ValueFeature)
    private data class XmlFeature(
        val name: String,
        val attributes: List<AttributeFeature>,
        val children: List<XmlFeature>,
    )
    private data class AttributeFeature(
        val nameId: Int?,
        val name: String,
        val valueType: String,
        val value: ValueFeature,
    )
    private data class ValueFeature(
        val kind: String,
        val type: String?,
        val valueType: String,
        val text: String? = null,
        val items: List<Pair<Int, ValueFeature>> = emptyList(),
    )
}

private fun MonetXmlElement.collectUsage(
    targetId: Int,
    parent: String,
    ownerType: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    val path = if (parent.isEmpty()) name else "$parent/$name"
    attributes.filter { (it.value as? MonetResourceValue.Reference)?.resourceId == targetId }
        .forEach { result += "usage:$ownerType:$path:${it.nameId}:${it.name}" }
    children.forEach { it.collectUsage(targetId, path, ownerType, graph, result) }
}

private fun MonetResourceValue.collectUsage(
    targetId: Int,
    path: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    when (this) {
        is MonetResourceValue.Reference -> if (resourceId == targetId) result += "usage:$path:reference"
        is MonetResourceValue.Complex -> items.forEach { item ->
            item.value.collectUsage(targetId, "$path:item:${item.nameId}", graph, result)
        }
        else -> Unit
    }
}

private fun MonetXmlElement.collectEvidence(
    parent: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    val path = if (parent.isEmpty()) name else "$parent/$name"
    result += "element:$path"
    attributes.forEach { attribute ->
        result += "attribute:$path:${attribute.nameId}:${attribute.name}:${attribute.valueType}:" +
            attribute.value.evidence(graph)
    }
    children.forEach { it.collectEvidence(path, graph, result) }
}

private fun MonetResourceValue.evidence(graph: MonetResourceGraph): String = when (this) {
    is MonetResourceValue.Reference -> "reference:${graph.node(resourceId)?.key?.type ?: "framework"}:$valueType"
    is MonetResourceValue.Literal -> "literal:$valueType:$data"
    is MonetResourceValue.Text -> "text:$value"
    is MonetResourceValue.File -> "file"
    is MonetResourceValue.Complex -> "complex:" + items.joinToString(";") {
        "${it.nameId}=${it.value.evidence(graph)}"
    }
}
