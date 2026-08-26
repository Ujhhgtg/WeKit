package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccess
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence

object MonetStructureMatcher {
    val roleIds: Set<String> = MONET_RULES.mapTo(linkedSetOf(), MonetSemanticRule::id)

    fun resolveAll(
        graph: MonetResourceGraph,
        dexProvider: MonetDexEvidenceProvider? = null,
    ): Map<String, MonetResourceNode> {
        val resolved = audit(graph, dexProvider).mapValues { (role, candidates) ->
            require(candidates.size == 1) { "$role: ${candidates.map { it.key }}" }
            candidates.single()
        }
        require(resolved.values.map(MonetResourceNode::id).distinct().size == resolved.size) {
            "multiple Monet roles resolved to the same resource"
        }
        return resolved
    }

    fun audit(
        graph: MonetResourceGraph,
        dexProvider: MonetDexEvidenceProvider? = null,
    ): Map<String, List<MonetResourceNode>> {
        val finalCandidates = resolveCandidateIds(graph, dexProvider)
        return MONET_RULES.associate { rule ->
            rule.id to finalCandidates.getValue(rule).mapNotNull(graph::node)
        }
    }

    private fun resolveCandidateIds(
        graph: MonetResourceGraph,
        dexProvider: MonetDexEvidenceProvider?,
    ): Map<MonetSemanticRule, Set<Int>> {
        val structural = structuralResolution(graph)
        val candidates = structural.candidates
        val anchored = candidates.filter { (rule, ids) -> rule.requiredDexEvidence.isNotEmpty() && ids.size > 1 }
        val dexFiltered = if (anchored.isEmpty()) emptyMap() else {
            val provider = requireNotNull(dexProvider) { "Dex evidence is required for ambiguous Monet roles" }
            val neighborIds = anchored.keys.flatMap { rule ->
                rule.requiredDexEvidence.mapNotNull { token ->
                    token.removePrefix("neighbor:").takeIf { token.startsWith("neighbor:") }
                }
            }.associateWith { role -> candidates.entries.single { it.key.id == role }.value.single() }
            val requestedIds = (anchored.values.flatten() + neighborIds.values).distinct().sorted()
            val evidence = provider.query(requestedIds.map { id ->
                val node = requireNotNull(graph.node(id))
                MonetDexCandidate(id, node.key.type, node.key.name)
            })
            require(evidence.map { it.resourceId }.distinct().size == evidence.size)
            val byId = evidence.associateBy { it.resourceId }
            anchored.mapValues { (rule, ids) ->
                ids.filterTo(linkedSetOf()) { id ->
                    byId[id]?.methods.orEmpty().any { method ->
                        val tokens = method.tokens(neighborIds)
                        tokens.containsAll(rule.requiredDexEvidence)
                    }
                }
            }
        }
        val combined = MONET_RULES.associateWith { rule ->
            dexFiltered[rule] ?: candidates.getValue(rule)
        }
        val related = applyRoleRelations(combined, graph)
        return disambiguate(assignPreferred(related, structural.preferredScores), assignEquivalentGroups = true)
    }

    private fun applyRoleRelations(
        input: Map<MonetSemanticRule, Set<Int>>,
        graph: MonetResourceGraph,
    ): Map<MonetSemanticRule, Set<Int>> {
        var result = disambiguate(input, assignEquivalentGroups = false)
        while (true) {
            var changed = false
            val byRole = result.entries.associate { it.key.id to it.value }
            val filtered = result.mapValues { (rule, ids) ->
                val related = rule.requiredAdjacentRoles.mapNotNull { (offset, role) ->
                    byRole[role]?.singleOrNull()?.let { offset to it }
                }
                if (related.size != rule.requiredAdjacentRoles.size) ids else ids.filterTo(linkedSetOf()) { id ->
                    related.all { (offset, expected) -> graph.node(id + offset)?.id == expected }
                }.also { if (it != ids) changed = true }
            }
            result = disambiguate(filtered, assignEquivalentGroups = false)
            if (!changed) return result
        }
    }

    internal fun structuralCandidates(graph: MonetResourceGraph): Map<MonetSemanticRule, Set<Int>> {
        return structuralResolution(graph).candidates
    }

    private fun structuralResolution(graph: MonetResourceGraph): StructuralResolution {
        val requiredByType = MONET_RULES.groupBy(MonetSemanticRule::type).mapValues { (_, rules) ->
            rules.flatMapTo(hashSetOf()) { it.requiredEvidence + it.preferredEvidence }
        }
        val idsByToken = HashMap<String, MutableSet<Int>>()
        requiredByType.forEach { (type, required) ->
            graph.nodes(type).forEach { node ->
                calculateEvidence(node, graph).forEach { token ->
                    if (token in required) idsByToken.getOrPut(token, ::linkedSetOf).add(node.id)
                }
            }
        }
        val candidates = disambiguate(MONET_RULES.associateWith { rule ->
            if (rule.requiredEvidence.isEmpty()) {
                graph.nodes(rule.type).mapTo(linkedSetOf(), MonetResourceNode::id)
            } else {
                rule.requiredEvidence.map { idsByToken[it].orEmpty() }
                    .reduce { result, ids -> result.intersect(ids) }
            }
        }, assignEquivalentGroups = false)
        val scores = MONET_RULES.associateWith { rule ->
            candidates.getValue(rule).associateWith { id ->
                rule.preferredEvidence.count { id in idsByToken[it].orEmpty() }
            }
        }
        return StructuralResolution(candidates, scores)
    }

    private data class StructuralResolution(
        val candidates: Map<MonetSemanticRule, Set<Int>>,
        val preferredScores: Map<MonetSemanticRule, Map<Int, Int>>,
    )

    private fun assignPreferred(
        input: Map<MonetSemanticRule, Set<Int>>,
        scores: Map<MonetSemanticRule, Map<Int, Int>>,
    ): Map<MonetSemanticRule, Set<Int>> {
        val result = input.mapValuesTo(linkedMapOf()) { it.value.toSet() }
        result.entries.filter { it.value.size > 1 && it.key.preferredEvidence.isNotEmpty() }
            .groupBy { it.key.equivalentOutputSemantic() }.filterKeys { it != null }
            .forEach { (_, entries) ->
                val roles = entries.map { it.key }.sortedBy(MonetSemanticRule::id)
                val candidates = entries.flatMap { it.value }.distinct().sorted()
                if (roles.size > candidates.size) return@forEach
                val assignment = minimumCostAssignment(roles, candidates) { role, candidate ->
                    if (candidate !in result.getValue(role)) 1_000_000 else -scores.getValue(role).getValue(candidate)
                }
                if (assignment.all { (roleIndex, candidateIndex) ->
                        val role = roles[roleIndex]
                        val candidate = candidates[candidateIndex]
                        candidate in result.getValue(role) && scores.getValue(role).getValue(candidate) > 0
                    }
                ) {
                    assignment.forEach { (roleIndex, candidateIndex) ->
                        result[roles[roleIndex]] = setOf(candidates[candidateIndex])
                    }
                }
            }
        return result
    }

    private fun minimumCostAssignment(
        rows: List<MonetSemanticRule>,
        columns: List<Int>,
        cost: (MonetSemanticRule, Int) -> Int,
    ): Map<Int, Int> {
        val rowPotential = IntArray(rows.size + 1)
        val columnPotential = IntArray(columns.size + 1)
        val matchedRow = IntArray(columns.size + 1)
        val way = IntArray(columns.size + 1)
        for (row in rows.indices) {
            matchedRow[0] = row + 1
            var column = 0
            val minimum = IntArray(columns.size + 1) { Int.MAX_VALUE }
            val used = BooleanArray(columns.size + 1)
            do {
                used[column] = true
                val currentRow = matchedRow[column]
                var delta = Int.MAX_VALUE
                var nextColumn = 0
                for (candidateColumn in 1..columns.size) if (!used[candidateColumn]) {
                    val current = cost(rows[currentRow - 1], columns[candidateColumn - 1]) -
                        rowPotential[currentRow] - columnPotential[candidateColumn]
                    if (current < minimum[candidateColumn]) {
                        minimum[candidateColumn] = current
                        way[candidateColumn] = column
                    }
                    if (minimum[candidateColumn] < delta) {
                        delta = minimum[candidateColumn]
                        nextColumn = candidateColumn
                    }
                }
                for (candidateColumn in 0..columns.size) if (used[candidateColumn]) {
                    rowPotential[matchedRow[candidateColumn]] += delta
                    columnPotential[candidateColumn] -= delta
                } else {
                    minimum[candidateColumn] -= delta
                }
                column = nextColumn
            } while (matchedRow[column] != 0)
            do {
                val previous = way[column]
                matchedRow[column] = matchedRow[previous]
                column = previous
            } while (column != 0)
        }
        return (1..columns.size).filter { matchedRow[it] != 0 }
            .associate { matchedRow[it] - 1 to it - 1 }
    }

    private fun disambiguate(
        input: Map<MonetSemanticRule, Set<Int>>,
        assignEquivalentGroups: Boolean,
    ): Map<MonetSemanticRule, Set<Int>> {
        val result = input.mapValuesTo(linkedMapOf()) { (_, ids) -> ids.toSet() }
        while (true) {
            var changed = false
            val singletons = result.filterValues { it.size == 1 }.entries.groupBy { it.value.single() }
            val claimed = singletons.keys
            result.entries.filter { it.value.size > 1 }.forEach { entry ->
                val filtered = entry.value - claimed
                if (filtered != entry.value) {
                    entry.setValue(filtered)
                    changed = true
                }
            }
            if (assignEquivalentGroups) {
                result.entries.filter { it.value.size > 1 }.groupBy { it.value }.forEach { (ids, entries) ->
                    val semantic = entries.map { it.key.equivalentOutputSemantic() }.distinct()
                    if (entries.size == ids.size && semantic.size == 1 && semantic.single() != null) {
                        entries.sortedBy { it.key.id }.zip(ids.sorted()).forEach { (entry, id) ->
                            entry.setValue(setOf(id))
                        }
                        changed = true
                    }
                }
            }
            if (!changed) return result
        }
    }

    private fun MonetSemanticRule.equivalentOutputSemantic(): String? = when {
        id.startsWith("theme.color.") -> id.substringBefore(".slot-")
        id.startsWith("chat.transfer.incoming.") || id.startsWith("chat.transfer.outgoing.") ->
            id.substringBeforeLast('.')
        else -> null
    }

    private fun MonetMethodDexEvidence.tokens(neighborIds: Map<String, Int>): Set<String> = buildSet {
        add("descriptor:$descriptor")
        add("owner-package:$ownerPackage")
        add("method-shape:$methodShape")
        stableStrings.forEach { add("string:$it") }
        invokedMethodShapes.forEach { add("invoke:$it") }
        neighborIds.forEach { (role, id) -> if (id in neighboringResourceIds) add("neighbor:$role") }
        fieldAccesses.forEach { field ->
            add("field:${if (field.access == MonetFieldAccess.READ) "read" else "write"}:${field.descriptor}")
        }
    }

    fun evidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> = calculateEvidence(node, graph)

    private fun calculateEvidence(node: MonetResourceNode, graph: MonetResourceGraph): Set<String> = HashSet<String>().apply {
        addAll(localEvidence(node, graph))
        addAll(usageEvidence(node, graph))
        graph.outgoing(node.id).mapNotNull(graph::node).forEach { child ->
            localEvidence(child, graph).forEach { add("child:${child.key.type}:$it") }
            graph.outgoing(child.id).mapNotNull(graph::node).forEach { grandchild ->
                localEvidence(grandchild, graph).forEach {
                    add("child:${child.key.type}:${grandchild.key.type}:$it")
                }
            }
        }
        (-2..2).filter { it != 0 }.forEach { offset ->
            graph.node(node.id + offset)?.takeIf { it.key.type == node.key.type }?.let { neighbor ->
                localEvidence(neighbor, graph).forEach { add("adjacent:$offset:$it") }
            }
        }
        graph.incoming(node.id).mapNotNull(graph::node).forEach { owner ->
            localEvidence(owner, graph).forEach { add("context:${owner.key.type}:$it") }
            usageEvidence(owner, graph).forEach { add("context:${owner.key.type}:$it") }
            graph.outgoing(owner.id).filter { it != node.id }.mapNotNull(graph::node).forEach { sibling ->
                localEvidence(sibling, graph).forEach { add("sibling:${owner.key.type}:${sibling.key.type}:$it") }
            }
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
        is MonetResourceValue.File -> ValueFeature("file", null, structure?.toString() ?: "FILE")
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
    is MonetResourceValue.File -> structure?.let {
        "file:${it.format}:${it.width}:${it.height}:${it.colorType}:${it.firstDataLength}:${it.ninePatchLength}:" +
            "${it.sampleSum}:${it.alphaSum}:${it.distinctSamples}:${it.pixelSha256}"
    } ?: "file"
    is MonetResourceValue.Complex -> "complex:" + items.joinToString(";") {
        "${it.nameId}=${it.value.evidence(graph)}"
    }
}
