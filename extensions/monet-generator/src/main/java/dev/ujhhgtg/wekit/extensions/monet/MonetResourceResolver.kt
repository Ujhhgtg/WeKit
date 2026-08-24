package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence

internal object MonetResourceResolver {
    fun resolve(
        graph: MonetResourceGraph,
        catalog: MonetRoleCatalog,
        profiles: List<MonetProfile>,
        sdkInt: Int,
        provider: MonetDexEvidenceProvider,
    ): MonetResolutionReport {
        val roleDefinitions = catalog.roles.associateBy(MonetRoleDefinition::id)
        val states = linkedMapOf<String, ResolutionState>()
        val skipped = mutableListOf<MonetRoleDiagnostic>()
        val diagnostics = linkedMapOf<String, MonetRoleDiagnostic>()

        catalog.roles.forEach { role ->
            if (sdkInt < role.minSdk || role.maxSdk?.let { sdkInt > it } == true) {
                val diagnostic = MonetRoleDiagnostic(
                    roleId = role.id,
                    core = role.core,
                    failure = MonetResolutionFailure.SDK_UNSUPPORTED,
                    candidateIds = emptyList(),
                    stages = listOf(
                        MonetCandidateStageDiagnostic(
                            MonetResolutionStage.SDK_AND_TYPE,
                            emptyList(),
                            emptyList(),
                        ),
                    ),
                    message = "SDK $sdkInt is outside ${role.minSdk}..${role.maxSdk ?: "unbounded"}",
                )
                skipped += diagnostic
                diagnostics[role.id] = diagnostic
                return@forEach
            }

            val state = ResolutionState(role)
            states[role.id] = state
            state.filter(MonetResolutionStage.SDK_AND_TYPE, graph.nodes(role.type).map(MonetResourceNode::id))
            state.filter(MonetResolutionStage.CONFIG_VALUES) { candidateId ->
                val signature = graph.referenceSignature(candidateId)
                (role.defaultValue == null || signature?.defaultValue == role.defaultValue) &&
                    (role.nightValue == null || signature?.nightValue == role.nightValue)
            }
            state.filter(MonetResolutionStage.XML_SHAPE_AND_REFERENCES) { candidateId ->
                role.xmlShapeSha256 == null || graph.xmlShapes(candidateId).any {
                    it.sha256 == role.xmlShapeSha256
                }
            }
        }

        val incomingApplied = mutableSetOf<String>()
        val incomingApplying = mutableSetOf<String>()
        lateinit var applyIncomingRelationships: (ResolutionState) -> Unit
        applyIncomingRelationships = apply@{ state ->
            if (state.role.id in incomingApplied) return@apply
            if (!incomingApplying.add(state.role.id)) {
                throw MonetResolutionException(
                    state.diagnostic(
                        failure = MonetResolutionFailure.DEPENDENCY_UNRESOLVED,
                        message = "incoming role dependency cycle includes ${state.role.id}",
                    ),
                )
            }
            val requiredIds = state.role.requiredIncomingRoleIds
            if (requiredIds.isEmpty()) {
                state.filter(MonetResolutionStage.INCOMING_RELATIONSHIPS) { true }
            } else {
                val requiredResourceIds = requiredIds.map { requiredRoleId ->
                    val requiredState = states[requiredRoleId]
                    if (requiredState == null) {
                        throw MonetResolutionException(
                            state.diagnostic(
                                failure = MonetResolutionFailure.DEPENDENCY_UNRESOLVED,
                                message = "incoming role $requiredRoleId is not available on SDK $sdkInt",
                            ),
                        )
                    }
                    applyIncomingRelationships(requiredState)
                    if (requiredState.candidateIds.size != 1) {
                        throw MonetResolutionException(
                            state.diagnostic(
                                failure = MonetResolutionFailure.DEPENDENCY_UNRESOLVED,
                                message = "incoming role $requiredRoleId is not uniquely resolved before Dex evidence",
                            ),
                        )
                    }
                    requiredState.candidateIds.single()
                }
                state.filter(MonetResolutionStage.INCOMING_RELATIONSHIPS) { candidateId ->
                    graph.incoming(candidateId).containsAll(requiredResourceIds)
                }
            }
            incomingApplying.remove(state.role.id)
            incomingApplied += state.role.id
        }
        states.values.forEach(applyIncomingRelationships)

        val dexStates = states.values.filter { state ->
            state.candidateIds.size > 1 && state.role.dexAnchors.isNotEmpty()
        }
        val requestedCandidates = dexStates
            .flatMap(ResolutionState::candidateIds)
            .distinct()
            .sorted()
            .map { resourceId ->
                val node = requireNotNull(graph.node(resourceId))
                MonetDexCandidate(resourceId, node.key.type, node.key.name)
            }
        val evidenceById = if (requestedCandidates.isEmpty()) {
            emptyMap()
        } else {
            val evidence = try {
                provider.query(requestedCandidates)
            } catch (error: Exception) {
                val failedState = dexStates.first()
                throw MonetResolutionException(
                    failedState.diagnostic(
                        failure = MonetResolutionFailure.DEX_EVIDENCE_FAILED,
                        message = error.message ?: error::class.java.name,
                    ),
                    error,
                )
            }
            val requestedIds = requestedCandidates.mapTo(hashSetOf(), MonetDexCandidate::resourceId)
            if (evidence.any { it.resourceId !in requestedIds } ||
                evidence.map(MonetResourceDexEvidence::resourceId).size !=
                evidence.map(MonetResourceDexEvidence::resourceId).toSet().size
            ) {
                val failedState = dexStates.first()
                throw MonetResolutionException(
                    failedState.diagnostic(
                        failure = MonetResolutionFailure.DEX_EVIDENCE_FAILED,
                        message = "provider returned duplicate or unrequested resource evidence",
                    ),
                )
            }
            evidence.associateBy(MonetResourceDexEvidence::resourceId)
        }

        states.values.forEach { state ->
            if (state in dexStates) {
                state.filter(MonetResolutionStage.DEX_ANCHORS) { candidateId ->
                    val evidence = evidenceById[candidateId] ?: return@filter false
                    state.role.dexAnchors.all { anchor ->
                        evidence.methods.any { method ->
                            method.matches(anchor, states)
                        }
                    }
                }
            } else {
                state.filter(MonetResolutionStage.DEX_ANCHORS) { true }
            }
        }

        val resolved = linkedMapOf<String, MonetResolvedRole>()
        catalog.roles.forEach { role ->
            val state = states[role.id] ?: return@forEach
            val profileCandidate = profileCandidate(role.id, graph, profiles, state)
            val failure = when (state.candidateIds.size) {
                0 -> MonetResolutionFailure.NOT_FOUND
                1 -> null
                else -> MonetResolutionFailure.AMBIGUOUS
            }
            if (profileCandidate != null &&
                (state.candidateIds.size != 1 || state.candidateIds.single() != profileCandidate)
            ) {
                throw MonetResolutionException(
                    state.diagnostic(
                        failure = MonetResolutionFailure.PROFILE_DRIFT,
                        profileCandidateId = profileCandidate,
                        message = "profile target disagrees with the unique generic result",
                    ),
                )
            }
            if (failure != null) {
                val diagnostic = state.diagnostic(failure = failure)
                diagnostics[role.id] = diagnostic
                if (role.core) throw MonetResolutionException(diagnostic)
                skipped += diagnostic
                return@forEach
            }

            val resourceId = state.candidateIds.single()
            resolved[role.id] = MonetResolvedRole(
                roleId = role.id,
                resourceId = resourceId,
                key = requireNotNull(graph.node(resourceId)).key,
                profileMatched = profileCandidate != null,
            )
            diagnostics[role.id] = state.diagnostic(
                failure = null,
                profileCandidateId = profileCandidate,
            )
        }
        roleDefinitions.keys.forEach { roleId ->
            check(roleId in diagnostics) { "missing diagnostic for $roleId" }
        }
        return MonetResolutionReport(
            resolved = resolved.toMap(),
            skipped = skipped.toList(),
            diagnostics = diagnostics.toMap(),
        )
    }

    private fun profileCandidate(
        roleId: String,
        graph: MonetResourceGraph,
        profiles: List<MonetProfile>,
        state: ResolutionState,
    ): Int? {
        val declaredKeys = profiles.mapNotNull { it.roles[roleId] }
        if (declaredKeys.isEmpty()) return null
        val candidateIds = declaredKeys.map { key -> graph.node(key)?.id }.distinct()
        if (candidateIds.size != 1 || candidateIds.single() == null) {
            throw MonetResolutionException(
                state.diagnostic(
                    failure = MonetResolutionFailure.PROFILE_DRIFT,
                    message = "matching profiles do not identify one live resource",
                ),
            )
        }
        return candidateIds.single()
    }

    private fun MonetMethodDexEvidence.matches(
        anchor: MonetDexAnchor,
        states: Map<String, ResolutionState>,
    ): Boolean {
        if (anchor.descriptor != null && descriptor != anchor.descriptor) return false
        if (!stableStrings.containsAll(anchor.stableStrings)) return false
        if (!invokedMethodShapes.containsAll(anchor.invokedMethodShapes)) return false
        val neighboringIds = anchor.neighboringRoleIds.map { roleId ->
            val state = states[roleId] ?: return false
            if (state.candidateIds.size != 1) return false
            state.candidateIds.single()
        }
        if (!neighboringResourceIds.containsAll(neighboringIds)) return false
        return anchor.fieldAccesses.all { required ->
            fieldAccesses.any { actual ->
                actual.descriptor == required.descriptor && actual.access.name == required.access
            }
        }
    }

    private class ResolutionState(val role: MonetRoleDefinition) {
        var candidateIds: List<Int> = emptyList()
            private set
        private val stages = mutableListOf<MonetCandidateStageDiagnostic>()

        fun filter(stage: MonetResolutionStage, candidates: List<Int>) {
            val before = candidateIds
            candidateIds = candidates.distinct().sorted()
            stages += MonetCandidateStageDiagnostic(stage, before, candidateIds)
        }

        fun filter(stage: MonetResolutionStage, keep: (Int) -> Boolean) {
            val before = candidateIds
            candidateIds = before.filter(keep)
            stages += MonetCandidateStageDiagnostic(stage, before, candidateIds)
        }

        fun diagnostic(
            failure: MonetResolutionFailure?,
            profileCandidateId: Int? = null,
            message: String? = null,
        ) = MonetRoleDiagnostic(
            roleId = role.id,
            core = role.core,
            failure = failure,
            candidateIds = candidateIds,
            profileCandidateId = profileCandidateId,
            stages = stages.toList(),
            message = message,
        )
    }
}
