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
        onProgress: (completed: Int, total: Int, role: String) -> Unit = { _, _, _ -> },
    ): Map<String, MonetResourceNode> {
        val audited = audit(graph, dexProvider)
        val resolved = MONET_RULES.mapIndexedNotNull { index, rule ->
            onProgress(index + 1, MONET_RULES.size, rule.id)
            val candidates = audited.getValue(rule.id)
            if (rule.optional && candidates.isEmpty()) null else {
                require(candidates.size == 1) { "${rule.id}: ${candidates.map { it.key }}" }
                rule.id to candidates.single()
            }
        }.toMap()
        val duplicateRoles = resolved.entries.groupBy { it.value.id }
            .filterValues { it.size > 1 }
            .values
            .flatMap { roles -> listOf(roles.map { it.key }, roles.map { it.value.key }) }
        require(duplicateRoles.isEmpty()) {
            "multiple Monet roles resolved to the same resource: $duplicateRoles"
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

    fun structuralAudit(graph: MonetResourceGraph): Map<String, List<MonetResourceNode>> {
        val resolution = structuralResolution(graph)
        val related = applyRoleRelations(resolution.candidates, graph)
        return disambiguate(assignPreferred(related, resolution.preferredScores), assignEquivalentGroups = true)
            .mapKeys { it.key.id }.mapValues { (_, ids) -> ids.mapNotNull(graph::node) }
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
            val baseline = COLOR_BASELINES[rule.id]
            val colorCandidates = baseline?.let { expected ->
                graph.nodes(rule.type).filterTo(linkedSetOf()) { node ->
                    node.values.associate { it.qualifiers to ((it.value as? MonetResourceValue.Literal)?.data ?: Long.MIN_VALUE) } == expected
                }.mapTo(linkedSetOf(), MonetResourceNode::id)
            }
            val selectorCandidates = COLOR_SELECTOR_BASELINES[rule.id]?.let { expected ->
                graph.nodes(rule.type).filterTo(linkedSetOf()) { node ->
                    graph.xmlTrees(node.id).any { it.containsLiteralColor(expected) }
                }.mapTo(linkedSetOf(), MonetResourceNode::id)
            }
            val structural = if (rule.requiredEvidence.isEmpty()) {
                graph.nodes(rule.type).mapTo(linkedSetOf(), MonetResourceNode::id)
            } else {
                rule.requiredEvidence.map { idsByToken[it].orEmpty() }
                    .reduce { result, ids -> result.intersect(ids) }
            }
            val semanticCandidates: Set<Int>? = when (rule.id) {
                SEARCH_BAR_BACKGROUND -> graph.actionBarSearchBackgroundColors()
                THREE_STATE_STROKE -> graph.threeStateSelectorDefaultStrokeColors()
                FINDER_LIVE_TAB -> colorCandidates?.filterNotTo(linkedSetOf()) { graph.isVipBadgeColor(it) }
                DELETE_ACTION_COLOR -> graph.sharedRawIconTextColors("icons_outlined_delete", 0xffedededL)
                APP_BRAND_PAGE_BACKGROUND -> graph.sandwichedColor(0xff333333L, 0xfff2f2f2L, 0xff191919L)
                SURFACE_CONTAINER_SLOT_59 -> graph.upSwipeCardTextColors().intersect(colorCandidates.orEmpty())
                SURFACE_CONTAINER_SLOT_57 -> graph.sharedArrowIconTextColors()
                else -> null
            }
            val staticNames = STATIC_ROLE_NAMES[rule.id].orEmpty()
            val orderedStaticNames = staticNames
            val referencedStatic = STATIC_ROLE_REFERENCES[rule.id]?.let { ownerKey ->
                graph.node(ownerKey)?.let { owner ->
                    graph.xmlTrees(owner.id)
                        .filter { rule.id.endsWith("slot-56") && it.name == "shape" }
                        .flatMap { it.referenceIds() }
                        .mapNotNull(graph::node)
                        .firstOrNull {
                            it.key.type == rule.type && it.key.name in orderedStaticNames &&
                                (it.id in structural || it.id in colorCandidates.orEmpty())
                        }?.id
                }
            }
            val staticAny = referencedStatic ?: orderedStaticNames.firstNotNullOfOrNull { name ->
                graph.node(MonetResourceKey(rule.type, name))?.takeIf { node ->
                    !rule.id.endsWith("received") || graph.xmlTrees(node.id).any { it.name == "selector" }
                }?.id
            }
            val static = referencedStatic?.let(::setOf)
                ?: orderedStaticNames.firstNotNullOfOrNull { name ->
                graph.node(MonetResourceKey(rule.type, name))?.takeIf { node ->
                    (node.id in structural || node.id in colorCandidates.orEmpty()) &&
                        (!rule.id.endsWith("received") || graph.xmlTrees(node.id).any { it.name == "selector" })
                }?.id
                }?.let(::setOf)
            if (semanticCandidates != null) {
                semanticCandidates
            } else if (rule.id in STATIC_FORCE_ROLES && (static?.singleOrNull() ?: staticAny) != null) {
                setOf(static?.singleOrNull() ?: staticAny!!)
            } else if (static != null && (structural.isEmpty() || static.any { it in structural })) {
                static.intersect(structural).takeIf { it.isNotEmpty() } ?: static
            } else if (rule.requiredEvidence.isEmpty()) {
                selectorCandidates?.takeIf { it.isNotEmpty() } ?: colorCandidates?.takeIf { it.isNotEmpty() } ?: graph.nodes(rule.type).mapTo(linkedSetOf(), MonetResourceNode::id)
            } else {
                selectorCandidates?.takeIf { it.isNotEmpty() }?.let { structural.intersect(it).takeIf { it.isNotEmpty() } ?: it }
                    ?: colorCandidates?.takeIf { it.isNotEmpty() }?.let { colors ->
                    structural.intersect(colors).takeIf { it.isNotEmpty() } ?: colors
                } ?: structural
            }
        }, assignEquivalentGroups = false)
        val scores = MONET_RULES.associateWith { rule ->
            candidates.getValue(rule).associateWith { id ->
                rule.preferredEvidence.count { id in idsByToken[it].orEmpty() }
            }
        }
        return StructuralResolution(candidates, scores)
    }

    /**
     * Cross-version source anchors confirmed in Play and domestic decompilations.
     * Names are aliases for one source use, not a reference-module mapping.
     */
    private val STATIC_ROLE_NAMES = mapOf(
        "chat.transfer.incoming.expired" to listOf("c2c_chatfrom_remittance_expired_bg"),
        "chat.transfer.outgoing.expired" to listOf("c2c_chatto_remittance_expired_bg"),
        "chat.transfer.incoming.received" to listOf("z1", "k6", "ym"),
        "chat.transfer.outgoing.received" to listOf("zc", "k9", "yy"),
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-27" to listOf("af6"),
        "theme.color.unknown--10ffffff.slot-06" to listOf("rh", "aa4"),
        "theme.color.unknown--system-surface-dark.slot-02" to listOf("e2", "ni"),
    )
    private val STATIC_ROLE_REFERENCES = emptyMap<String, MonetResourceKey>()
    private val STATIC_FORCE_ROLES = setOf(
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-56",
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-27",
    )

    private data class StructuralResolution(
        val candidates: Map<MonetSemanticRule, Set<Int>>,
        val preferredScores: Map<MonetSemanticRule, Map<Int, Int>>,
    )

    private val COLOR_BASELINES = mapOf(
        "theme.color.system-surface-container-light--10ffffff.slot-02" to mapOf("" to 4294111986L, "-night" to 4281348144L),
        "theme.color.system-surface-container-light--10ffffff.slot-03" to mapOf("" to 4294111986L, "-night" to 4281348144L),
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-56" to mapOf("" to 637534208L),
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-58" to mapOf("" to 4294440951L),
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-59" to mapOf("" to 2348810240L),
        "theme.color.system-surface-light--system-surface-dark.slot-04" to mapOf("" to 4291801463L),
    )
    private val COLOR_SELECTOR_BASELINES = mapOf(
        "theme.color.system-surface-dark--system-surface-dark.slot-02" to 4278627926L,
    )
    private const val SURFACE_CONTAINER_SLOT_57 =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-57"
    private const val SURFACE_CONTAINER_SLOT_59 =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-59"
    private const val SEARCH_BAR_BACKGROUND =
        "theme.color.system-surface-container-light--10ffffff.slot-02"
    private const val THREE_STATE_STROKE =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-50"
    private const val FINDER_LIVE_TAB =
        "theme.color.system-surface-light--system-surface-dark.slot-04"
    private const val DELETE_ACTION_COLOR =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-26"
    private const val APP_BRAND_PAGE_BACKGROUND =
        "theme.color.system-surface-container-light--system-surface-container-dark.slot-42"

    private fun MonetXmlElement.containsLiteralColor(expected: Long): Boolean =
        attributes.any { (it.value as? MonetResourceValue.Literal)?.data == expected } ||
            children.any { it.containsLiteralColor(expected) }

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
        graph.xmlTrees(node.id).forEach { it.collectSimpleEvidence("", graph, this) }
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
                tree.collectSimpleUsage(node.id, "", owner.key.type, this)
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
            data = data,
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
        val data: Long? = null,
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

private fun MonetXmlElement.collectSimpleUsage(
    targetId: Int,
    parent: String,
    ownerType: String,
    result: MutableSet<String>,
) {
    val simpleName = name.substringAfterLast('.')
    val path = if (parent.isEmpty()) simpleName else "$parent/$simpleName"
    attributes.filter { (it.value as? MonetResourceValue.Reference)?.resourceId == targetId }
        .forEach { result += "simple-usage:$ownerType:$path:${it.nameId}:${it.name}" }
    children.forEach { it.collectSimpleUsage(targetId, path, ownerType, result) }
}

private fun MonetXmlElement.referenceIds(): Set<Int> = buildSet {
    attributes.forEach { attribute ->
        (attribute.value as? MonetResourceValue.Reference)?.let { add(it.resourceId) }
    }
    children.forEach { addAll(it.referenceIds()) }
}

private fun MonetResourceGraph.sharedArrowIconTextColors(): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).forEach { it.collectSharedArrowIconTextColors(this@sharedArrowIconTextColors, this) }
    }
}

private fun MonetResourceGraph.sharedRawIconTextColors(rawName: String, expectedColor: Long): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).forEach { it.collectSharedRawIconTextColors(this@sharedRawIconTextColors, rawName, this) }
    }
    retainAll(nodes("color").filter { it.defaultLiteral() == expectedColor }.map(MonetResourceNode::id).toSet())
}

private fun MonetXmlElement.collectSharedRawIconTextColors(
    graph: MonetResourceGraph,
    rawName: String,
    result: MutableSet<Int>,
) {
    val icon = children.firstOrNull {
        it.reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", rawName)
    }
    val iconColor = icon?.reference("iconColor")
    if (iconColor != null && children.any { it.name.substringAfterLast('.') == "TextView" && it.reference("textColor") == iconColor }) {
        result += iconColor
    }
    children.forEach { it.collectSharedRawIconTextColors(graph, rawName, result) }
}

private fun MonetResourceGraph.sandwichedColor(before: Long, value: Long, after: Long): Set<Int> =
    nodes("color").filterTo(linkedSetOf()) { node ->
        node.defaultLiteral() == value && this@sandwichedColor.node(node.id - 1)?.defaultLiteral() == before &&
            this@sandwichedColor.node(node.id + 1)?.defaultLiteral() == after
    }.mapTo(linkedSetOf(), MonetResourceNode::id)

private fun MonetResourceNode.defaultLiteral(): Long? =
    (values.singleOrNull { it.qualifiers.isEmpty() }?.value as? MonetResourceValue.Literal)?.data

private fun MonetResourceGraph.actionBarSearchBackgroundColors(): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).filter { tree ->
            tree.containsRaw(this@actionBarSearchBackgroundColors, "arrow_left_regular") &&
                tree.containsRaw(this@actionBarSearchBackgroundColors, "icons_outlined_search")
        }.forEach { it.collectSearchBackgroundColors(this@actionBarSearchBackgroundColors, this) }
    }
}

private fun MonetResourceGraph.upSwipeCardTextColors(): Set<Int> = buildSet {
    nodes("layout").forEach { owner ->
        xmlTrees(owner.id).forEach { it.collectUpSwipeCardTextColors(this) }
    }
}

private fun MonetXmlElement.collectUpSwipeCardTextColors(result: MutableSet<Int>) {
    val image = children.firstOrNull { it.name.substringAfterLast('.') == "ImageView" }
    val text = children.firstOrNull { it.name.substringAfterLast('.') == "TextView" }
    val singleLine = text?.literal("singleLine")
    if (image?.reference("tint") != null && image.reference("src") != null && text != null &&
        text.literal("maxLines") == 1L && singleLine != null && singleLine != 0L
    ) text.reference("textColor")?.let(result::add)
    children.forEach { it.collectUpSwipeCardTextColors(result) }
}

private fun MonetXmlElement.collectSearchBackgroundColors(graph: MonetResourceGraph, result: MutableSet<Int>) {
    if (containsRaw(graph, "icons_outlined_search")) reference("backgroundTint")?.let(result::add)
    children.forEach { it.collectSearchBackgroundColors(graph, result) }
}

private fun MonetXmlElement.containsRaw(graph: MonetResourceGraph, name: String): Boolean =
    reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", name) ||
        children.any { it.containsRaw(graph, name) }

private fun MonetResourceGraph.threeStateSelectorDefaultStrokeColors(): Set<Int> = buildSet {
    nodes("drawable").forEach { owner ->
        xmlTrees(owner.id).filter { it.name == "selector" }.forEach { selector ->
            val items = selector.children.filter { it.name == "item" }
            val activated = items.getOrNull(1)?.literal("state_activated")
            if (items.size != 3 || items[0].literal("state_enabled") != 0L ||
                activated == null || activated == 0L || items[2].attributes.any { it.name.startsWith("state_") }
            ) return@forEach
            val strokes = items.map { it.descendant("stroke")?.reference("color") }
            val solids = items.map { it.descendant("solid")?.reference("color") }
            if (strokes[0] != null && strokes[0] == strokes[1] && strokes[2] != null &&
                strokes[2] != strokes[0] && solids.toSet().size == 1 && solids[0] != null
            ) add(strokes[2]!!)
        }
    }
}

private fun MonetResourceGraph.isVipBadgeColor(targetId: Int): Boolean = nodes("layout").any { owner ->
    xmlTrees(owner.id).any { it.hasVipBadgeColor(this, targetId) }
}

private fun MonetXmlElement.hasVipBadgeColor(graph: MonetResourceGraph, targetId: Int): Boolean {
    val hasVipIcon = children.any {
        it.reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", "vip_filled_new")
    }
    val usesTarget = children.any { child ->
        child.attributes.any { it.name in setOf("iconColor", "textColor") &&
            (it.value as? MonetResourceValue.Reference)?.resourceId == targetId }
    }
    return hasVipIcon && usesTarget || children.any { it.hasVipBadgeColor(graph, targetId) }
}

private fun MonetXmlElement.descendant(name: String): MonetXmlElement? =
    children.firstOrNull { it.name == name } ?: children.firstNotNullOfOrNull { it.descendant(name) }

private fun MonetXmlElement.literal(name: String): Long? =
    (attributes.firstOrNull { it.name == name }?.value as? MonetResourceValue.Literal)?.data

private fun MonetXmlElement.collectSharedArrowIconTextColors(
    graph: MonetResourceGraph,
    result: MutableSet<Int>,
) {
    val icon = children.firstOrNull { child ->
        child.name.substringAfterLast('.') == "WeImageView" &&
            child.reference("src")?.let(graph::node)?.key == MonetResourceKey("raw", "arrow_double_regular")
    }
    val text = children.firstOrNull { child ->
        child.name.substringAfterLast('.') == "TextView" && child.reference("textSize") != null &&
            (child.attributes.firstOrNull { it.name == "textFontWeight" }?.value as? MonetResourceValue.Literal)?.data == 500L
    }
    val iconColor = icon?.reference("iconColor")
    if (iconColor != null && iconColor == text?.reference("textColor")) result += iconColor
    children.forEach { it.collectSharedArrowIconTextColors(graph, result) }
}

private fun MonetXmlElement.reference(name: String): Int? =
    (attributes.firstOrNull { it.name == name }?.value as? MonetResourceValue.Reference)?.resourceId

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

private fun MonetXmlElement.collectSimpleEvidence(
    parent: String,
    graph: MonetResourceGraph,
    result: MutableSet<String>,
) {
    val simpleName = name.substringAfterLast('.')
    val path = if (parent.isEmpty()) simpleName else "$parent/$simpleName"
    attributes.forEach { attribute ->
        result += "simple-attribute:$path:${attribute.nameId}:${attribute.name}:${attribute.valueType}:" +
            attribute.value.evidence(graph)
    }
    children.forEach { it.collectSimpleEvidence(path, graph, result) }
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
