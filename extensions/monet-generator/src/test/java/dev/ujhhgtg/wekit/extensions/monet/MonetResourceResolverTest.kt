package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MonetResourceResolverTest {

    @Test
    fun `catalog rejects dex anchor neighboring unknown role`() {
        assertThrows(IllegalArgumentException::class.java) {
            MonetRoleCatalog(
                schemaVersion = 1,
                roles = listOf(
                    MonetRoleDefinition(
                        id = ROLE_ID,
                        type = "drawable",
                        core = true,
                        dexAnchors = listOf(
                            MonetDexAnchor(neighboringRoleIds = listOf("unknown.role")),
                        ),
                    ),
                ),
                overlays = emptyList(),
            )
        }
    }

    @Test
    fun `resolver rejects profile role absent from catalog`() {
        val catalog = MonetRoleCatalog(
            schemaVersion = 1,
            roles = listOf(
                MonetRoleDefinition(
                    id = ROLE_ID,
                    type = "drawable",
                    core = false,
                ),
            ),
            overlays = emptyList(),
        )
        val profile = MonetProfile(
            resourceDigest = "fixture",
            versionName = "8.0.test",
            channel = "domestic",
            roles = mapOf(
                "unknown.role" to MonetResourceKey("drawable", "unknown"),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MonetResourceResolver.resolve(
                graph = MonetResourceGraph(emptyList()),
                catalog = catalog,
                profiles = listOf(profile),
                sdkInt = 31,
                provider = RecordingEvidenceProvider(),
            )
        }
    }

    @Test
    fun `layout incoming edge disambiguates equal drawable shapes`() {
        val report = resolveFixture(
            twoEqualDrawables = true,
            matchingLayoutIncoming = true,
        )

        assertEquals(
            "bubble_in",
            report.resolved.getValue(ROLE_ID).key.name,
        )
        assertEquals(
            listOf(FIRST_ID, AMBIGUOUS_ID),
            report.diagnostics.getValue(ROLE_ID)
                .stages.single { it.stage == MonetResolutionStage.INCOMING_RELATIONSHIPS }
                .beforeCandidateIds,
        )
        assertEquals(
            listOf(AMBIGUOUS_ID),
            report.diagnostics.getValue(ROLE_ID)
                .stages.single { it.stage == MonetResolutionStage.INCOMING_RELATIONSHIPS }
                .afterCandidateIds,
        )
    }

    @Test
    fun `dex evidence is requested only after resource constraints remain ambiguous`() {
        val provider = RecordingEvidenceProvider(
            evidenceFor = mapOf(AMBIGUOUS_ID to matchingDexEvidence),
        )

        val report = resolveFixture(
            twoEqualDrawables = true,
            matchingLayoutIncoming = false,
            provider = provider,
            dexAnchored = true,
        )

        assertEquals(listOf(setOf(FIRST_ID, AMBIGUOUS_ID)), provider.requestedIdSets)
        assertEquals(AMBIGUOUS_ID, report.resolved.getValue(ROLE_ID).resourceId)
    }

    @Test
    fun `dex request includes uniquely resolved roles referenced as neighbors`() {
        val provider = RecordingEvidenceProvider(
            evidenceFor = mapOf(AMBIGUOUS_ID to matchingNeighborDexEvidence),
        )

        val report = resolveFixture(
            twoEqualDrawables = true,
            provider = provider,
            dexAnchored = true,
            neighboringDexAnchor = true,
        )

        assertEquals(
            listOf(setOf(FIRST_ID, AMBIGUOUS_ID, LAYOUT_ID)),
            provider.requestedIdSets,
        )
        assertEquals(AMBIGUOUS_ID, report.resolved.getValue(ROLE_ID).resourceId)
    }

    @Test
    fun `unique resource result never requests dex evidence`() {
        val provider = RecordingEvidenceProvider(
            evidenceFor = mapOf(AMBIGUOUS_ID to matchingDexEvidence),
        )

        val report = resolveFixture(
            graphTarget = AMBIGUOUS_ID,
            provider = provider,
            dexAnchored = true,
        )

        assertEquals(emptyList<Set<Int>>(), provider.requestedIdSets)
        assertEquals(AMBIGUOUS_ID, report.resolved.getValue(ROLE_ID).resourceId)
    }

    @Test
    fun `core role ambiguity fails instead of selecting highest score`() {
        val error = assertThrows(MonetResolutionException::class.java) {
            resolveFixture(twoEqualDrawables = true)
        }

        assertEquals(MonetResolutionFailure.AMBIGUOUS, error.diagnostic.failure)
        assertEquals(setOf(FIRST_ID, AMBIGUOUS_ID), error.diagnostic.candidateIds.toSet())
    }

    @Test
    fun `verified exact profile disambiguates an ambiguous live-valid candidate set`() {
        val report = resolveFixture(
            twoEqualDrawables = true,
            profileTarget = AMBIGUOUS_ID,
        )

        assertEquals(AMBIGUOUS_ID, report.resolved.getValue(ROLE_ID).resourceId)
        assertTrue(report.resolved.getValue(ROLE_ID).profileMatched)
        assertEquals(
            setOf(FIRST_ID, AMBIGUOUS_ID),
            report.diagnostics.getValue(ROLE_ID).candidateIds.toSet(),
        )
    }

    @Test
    fun `profile target rejected by a live constraint is profile drift`() {
        val error = assertThrows(MonetResolutionException::class.java) {
            resolveFixture(profileTarget = FIRST_ID)
        }

        assertEquals(MonetResolutionFailure.PROFILE_DRIFT, error.diagnostic.failure)
        assertEquals(FIRST_ID, error.diagnostic.profileCandidateId)
        assertEquals(listOf(AMBIGUOUS_ID), error.diagnostic.candidateIds)
    }

    @Test
    fun `profile disagreement with live graph is fatal`() {
        val error = assertThrows(MonetResolutionException::class.java) {
            resolveFixture(profileTarget = FIRST_ID, graphTarget = AMBIGUOUS_ID)
        }

        assertEquals(MonetResolutionFailure.PROFILE_DRIFT, error.diagnostic.failure)
        assertEquals(FIRST_ID, error.diagnostic.profileCandidateId)
        assertEquals(listOf(AMBIGUOUS_ID), error.diagnostic.candidateIds)
    }

    @Test
    fun `dex evidence cannot restore candidate rejected by xml shape`() {
        val provider = RecordingEvidenceProvider(
            evidenceFor = mapOf(FIRST_ID to matchingDexEvidence),
        )

        val report = resolveFixture(
            provider = provider,
            dexAnchored = true,
            graphTarget = AMBIGUOUS_ID,
        )

        assertEquals(emptyList<Set<Int>>(), provider.requestedIdSets)
        assertEquals(AMBIGUOUS_ID, report.resolved.getValue(ROLE_ID).resourceId)
    }

    @Test
    fun `optional role with no candidate is reported as skipped`() {
        val report = MonetResourceResolver.resolve(
            graph = MonetResourceGraph(emptyList()),
            catalog = MonetRoleCatalog(
                schemaVersion = 1,
                roles = listOf(
                    MonetRoleDefinition(
                        id = "optional.enhancement",
                        type = "drawable",
                        core = false,
                    ),
                ),
                overlays = emptyList(),
            ),
            profiles = emptyList(),
            sdkInt = 31,
            provider = RecordingEvidenceProvider(),
        )

        assertTrue(report.resolved.isEmpty())
        assertEquals(
            MonetResolutionFailure.NOT_FOUND,
            report.skipped.single().failure,
        )
    }

    @Test
    fun `dex provider failure has a structured diagnostic`() {
        val error = assertThrows(MonetResolutionException::class.java) {
            resolveFixture(
                twoEqualDrawables = true,
                dexAnchored = true,
                provider = MonetDexEvidenceProvider { error("dex unavailable") },
            )
        }

        assertEquals(MonetResolutionFailure.DEX_EVIDENCE_FAILED, error.diagnostic.failure)
        assertEquals(setOf(FIRST_ID, AMBIGUOUS_ID), error.diagnostic.candidateIds.toSet())
    }

    private fun resolveFixture(
        twoEqualDrawables: Boolean = false,
        matchingLayoutIncoming: Boolean = false,
        provider: MonetDexEvidenceProvider = RecordingEvidenceProvider(),
        dexAnchored: Boolean = false,
        neighboringDexAnchor: Boolean = false,
        profileTarget: Int? = null,
        graphTarget: Int? = null,
    ): MonetResolutionReport {
        val firstValue = if (graphTarget == FIRST_ID) TARGET_VALUE else OTHER_VALUE
        val ambiguousValue = if (graphTarget == AMBIGUOUS_ID) TARGET_VALUE else OTHER_VALUE
        val targetShape = MonetXmlShape(TARGET_SHAPE)
        val otherShape = MonetXmlShape(OTHER_SHAPE)
        val graph = MonetResourceGraph(
            listOf(
                MonetResourceNode(
                    id = LAYOUT_ID,
                    key = MonetResourceKey("layout", "chat_row"),
                    values = emptyList(),
                ),
                MonetResourceNode(
                    id = FIRST_ID,
                    key = MonetResourceKey("drawable", "a53"),
                    values = listOf(
                        MonetConfiguredValue("", MonetResourceValue.Literal("INT_COLOR_ARGB8", firstValue)),
                    ),
                ),
                MonetResourceNode(
                    id = AMBIGUOUS_ID,
                    key = MonetResourceKey("drawable", "bubble_in"),
                    values = listOf(
                        MonetConfiguredValue("", MonetResourceValue.Literal("INT_COLOR_ARGB8", ambiguousValue)),
                    ),
                ),
            ),
        ).withXmlData(
            sourceId = FIRST_ID,
            referenceIds = emptySet(),
            shapes = setOf(if (twoEqualDrawables) targetShape else otherShape),
        ).withXmlData(
            sourceId = AMBIGUOUS_ID,
            referenceIds = emptySet(),
            shapes = setOf(targetShape),
        ).withXmlData(
            sourceId = LAYOUT_ID,
            referenceIds = if (matchingLayoutIncoming) setOf(AMBIGUOUS_ID) else emptySet(),
            shapes = setOf(MonetXmlShape(LAYOUT_SHAPE)),
        )
        val role = MonetRoleDefinition(
            id = ROLE_ID,
            type = "drawable",
            core = true,
            defaultValue = if (graphTarget == null) null else "literal:INT_COLOR_ARGB8:$TARGET_VALUE",
            xmlShapeSha256 = TARGET_SHAPE,
            requiredIncomingRoleIds = if (matchingLayoutIncoming) listOf(LAYOUT_ROLE_ID) else emptyList(),
            dexAnchors = if (dexAnchored) {
                listOf(
                    MonetDexAnchor(
                        stableStrings = listOf("ChattingUI"),
                        neighboringRoleIds = if (neighboringDexAnchor) {
                            listOf(LAYOUT_ROLE_ID)
                        } else {
                            emptyList()
                        },
                    ),
                )
            } else {
                emptyList()
            },
        )
        val layoutRole = MonetRoleDefinition(
            id = LAYOUT_ROLE_ID,
            type = "layout",
            core = true,
            xmlShapeSha256 = LAYOUT_SHAPE,
        )
        val profile = profileTarget?.let { target ->
            val key = requireNotNull(graph.node(target)).key
            MonetProfile(
                resourceDigest = "fixture",
                versionName = "8.0.test",
                channel = "domestic",
                roles = mapOf(ROLE_ID to key),
            )
        }
        return MonetResourceResolver.resolve(
            graph = graph,
            catalog = MonetRoleCatalog(
                schemaVersion = 1,
                roles = listOf(layoutRole, role),
                overlays = emptyList(),
            ),
            profiles = listOfNotNull(profile),
            sdkInt = 31,
            provider = provider,
        )
    }

    private class RecordingEvidenceProvider(
        private val evidenceFor: Map<Int, MonetResourceDexEvidence> = emptyMap(),
    ) : MonetDexEvidenceProvider {
        val requestedIdSets = mutableListOf<Set<Int>>()

        override fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> {
            requestedIdSets += candidates.mapTo(linkedSetOf(), MonetDexCandidate::resourceId)
            return candidates.mapNotNull { evidenceFor[it.resourceId] }
        }
    }

    private companion object {
        const val ROLE_ID = "chat.bubble.incoming.normal"
        const val LAYOUT_ROLE_ID = "chat.message.row"
        const val FIRST_ID = 0x7f080111
        const val AMBIGUOUS_ID = 0x7f080222
        const val LAYOUT_ID = 0x7f0d0333
        const val TARGET_VALUE = 0x11223344L
        const val OTHER_VALUE = 0x55667788L
        val TARGET_SHAPE = "a".repeat(64)
        val OTHER_SHAPE = "b".repeat(64)
        val LAYOUT_SHAPE = "c".repeat(64)
        val matchingDexEvidence = MonetResourceDexEvidence(
            resourceId = AMBIGUOUS_ID,
            methods = listOf(
                MonetMethodDexEvidence(
                    descriptor = "Lcom/tencent/mm/ui/chatting/ChattingUI;->onCreate()V",
                    stableStrings = listOf("ChattingUI"),
                    invokedMethodShapes = emptyList(),
                    neighboringResourceIds = emptyList(),
                    fieldAccesses = emptyList(),
                ),
            ),
        )
        val matchingNeighborDexEvidence = matchingDexEvidence.copy(
            methods = matchingDexEvidence.methods.map { method ->
                method.copy(neighboringResourceIds = listOf(LAYOUT_ID))
            },
        )
    }
}
