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
    fun `verified profile disambiguates auxiliary role before incoming relationship filter`() {
        val report = resolveFixture(
            twoEqualDrawables = true,
            matchingLayoutIncoming = true,
            twoEqualLayouts = true,
            profileLayoutTarget = LAYOUT_ID,
        )

        assertEquals(LAYOUT_ID, report.resolved.getValue(LAYOUT_ROLE_ID).resourceId)
        assertEquals(AMBIGUOUS_ID, report.resolved.getValue(ROLE_ID).resourceId)
        assertEquals(
            listOf(AMBIGUOUS_ID),
            report.diagnostics.getValue(ROLE_ID)
                .stages.single { it.stage == MonetResolutionStage.INCOMING_RELATIONSHIPS }
                .afterCandidateIds,
        )
    }

    @Test
    fun `profile selected style disambiguates incoming drawable across obfuscated file paths`() {
        fun styleNode(id: Int, name: String, drawableId: Int) = MonetResourceNode(
            id = id,
            key = MonetResourceKey("style", name),
            values = listOf(
                MonetConfiguredValue(
                    "",
                    MonetResourceValue.Complex(
                        parentId = 0,
                        items = listOf(
                            MonetComplexValue(16842964, MonetResourceValue.Reference(drawableId)),
                        ),
                    ),
                ),
            ),
        )
        val graph = MonetResourceGraph(
            listOf(
                MonetResourceNode(
                    FIRST_ID,
                    MonetResourceKey("drawable", "pressed_a"),
                    listOf(MonetConfiguredValue("", MonetResourceValue.File("res/i/a.xml"))),
                ),
                MonetResourceNode(
                    AMBIGUOUS_ID,
                    MonetResourceKey("drawable", "pressed_b"),
                    listOf(MonetConfiguredValue("", MonetResourceValue.File("res/j/b.xml"))),
                ),
                styleNode(STYLE_ID, "key_style_a", FIRST_ID),
                styleNode(SECOND_STYLE_ID, "key_style_b", AMBIGUOUS_ID),
            ),
        ).withXmlData(FIRST_ID, emptySet(), setOf(MonetXmlShape(TARGET_SHAPE)))
            .withXmlData(AMBIGUOUS_ID, emptySet(), setOf(MonetXmlShape(TARGET_SHAPE)))
        val styleRole = MonetRoleDefinition(
            id = STYLE_ROLE_ID,
            type = "style",
            core = true,
            defaultValueStructure = "complex:parent:-:item:16842964=reference:REFERENCE:drawable:file:-",
        )
        val drawableRole = MonetRoleDefinition(
            id = ROLE_ID,
            type = "drawable",
            core = true,
            xmlShapeSha256 = TARGET_SHAPE,
            requiredIncomingRoleIds = listOf(STYLE_ROLE_ID),
        )
        val report = MonetResourceResolver.resolve(
            graph = graph,
            catalog = MonetRoleCatalog(1, listOf(styleRole, drawableRole), emptyList()),
            profiles = listOf(
                MonetProfile(
                    resourceDigest = "fixture",
                    versionName = "8.0.test",
                    channel = "google-play",
                    roles = mapOf(STYLE_ROLE_ID to requireNotNull(graph.node(STYLE_ID)).key),
                ),
            ),
            sdkInt = 31,
            provider = RecordingEvidenceProvider(),
        )

        assertEquals(FIRST_ID, report.resolved.getValue(ROLE_ID).resourceId)
        assertEquals(
            setOf(STYLE_ID, SECOND_STYLE_ID),
            report.diagnostics.getValue(STYLE_ROLE_ID).candidateIds.toSet(),
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
        twoEqualLayouts: Boolean = false,
        profileLayoutTarget: Int? = null,
        graphTarget: Int? = null,
    ): MonetResolutionReport {
        val firstValue = if (graphTarget == FIRST_ID) TARGET_VALUE else OTHER_VALUE
        val ambiguousValue = if (graphTarget == AMBIGUOUS_ID) TARGET_VALUE else OTHER_VALUE
        val targetShape = MonetXmlShape(TARGET_SHAPE)
        val otherShape = MonetXmlShape(OTHER_SHAPE)
        val nodes = mutableListOf(
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
        )
        if (twoEqualLayouts) {
            nodes += MonetResourceNode(
                id = SECOND_LAYOUT_ID,
                key = MonetResourceKey("layout", "chat_row_alternate"),
                values = emptyList(),
            )
        }
        var graph = MonetResourceGraph(nodes).withXmlData(
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
        if (twoEqualLayouts) {
            graph = graph.withXmlData(
                sourceId = SECOND_LAYOUT_ID,
                referenceIds = if (matchingLayoutIncoming) setOf(FIRST_ID) else emptySet(),
                shapes = setOf(MonetXmlShape(LAYOUT_SHAPE)),
            )
        }
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
        val profileRoles = buildMap {
            profileTarget?.let { target ->
                put(ROLE_ID, requireNotNull(graph.node(target)).key)
            }
            profileLayoutTarget?.let { target ->
                put(LAYOUT_ROLE_ID, requireNotNull(graph.node(target)).key)
            }
        }
        val profile = profileRoles.takeIf { it.isNotEmpty() }?.let { roles ->
            MonetProfile(
                resourceDigest = "fixture",
                versionName = "8.0.test",
                channel = "domestic",
                roles = roles,
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
        const val STYLE_ROLE_ID = "payment.keyboard.key.style"
        const val FIRST_ID = 0x7f080111
        const val AMBIGUOUS_ID = 0x7f080222
        const val LAYOUT_ID = 0x7f0d0333
        const val SECOND_LAYOUT_ID = 0x7f0d0444
        const val STYLE_ID = 0x7f130555
        const val SECOND_STYLE_ID = 0x7f130666
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
