package dev.ujhhgtg.wekit.extensions.monettest

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccess
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccessEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonetTestReportTest {

    @Test
    fun `large candidate set keeps count digest and bounded deterministic sample`() {
        val report = monetCandidateSet((1..1_000).toList())

        assertEquals(1_000, report.totalCount)
        assertEquals(
            "72827ef050e6908b556c0b28e4a22355ea8099a101da5c5e035ed096e0576db6",
            report.sortedIdsSha256,
        )
        assertEquals((1..32).toList() + (969..1_000).toList(), report.sampleResourceIds)
        assertTrue(report.truncated)
    }

    @Test
    fun `small candidate set emits every sorted unique id`() {
        val report = monetCandidateSet(listOf(9, 3, 9, 5))

        assertEquals(3, report.totalCount)
        assertEquals(listOf(3, 5, 9), report.sampleResourceIds)
        assertFalse(report.truncated)
    }

    @Test
    fun `DEX query report bounds candidates evidence methods and nested collections`() {
        val candidates = (1..1_001).map { id -> MonetDexCandidate(id, "color", "c$id") }
        val strings = (1..1_001).map { "stable-$it" }
        val invokes = (1..1_001).map { "invoke-$it" }
        val fields = (1..1_001).map { index ->
            MonetFieldAccessEvidence("Lx;->field$index:I", MonetFieldAccess.READ)
        }
        val methods = (1..1_001).map { index ->
            MonetMethodDexEvidence(
                descriptor = "Lx;->method$index()V",
                stableStrings = strings,
                invokedMethodShapes = invokes,
                neighboringResourceIds = (1..1_001).toList(),
                fieldAccesses = fields,
            )
        }
        val evidence = candidates.map { candidate ->
            MonetResourceDexEvidence(
                resourceId = candidate.resourceId,
                methods = if (candidate.resourceId == 1) methods else emptyList(),
            )
        }

        val report = monetDexQueryReport(candidates, evidence)
        val firstEvidence = report.sampleEvidence.single { it.resourceId == 1 }

        assertEquals(1_001, report.requestedCandidates.totalCount)
        assertTrue(report.requestedCandidates.truncated)
        assertEquals(64, report.sampleCandidates.size)
        assertEquals(1_001, report.evidenceResources.totalCount)
        assertEquals(64, report.sampleEvidence.size)
        assertEquals(1_001, firstEvidence.methodDescriptors.totalCount)
        assertEquals(64, firstEvidence.sampleMethods.size)
        assertEquals(1_001, firstEvidence.sampleMethods.first().stableStrings.totalCount)
        assertEquals(1_001, firstEvidence.sampleMethods.first().invokedMethodShapes.totalCount)
        assertEquals(1_001, firstEvidence.sampleMethods.first().neighboringResourceIds.totalCount)
        assertEquals(1_001, firstEvidence.sampleMethods.first().fieldAccesses.totalCount)
        assertEquals(
            firstEvidence.sampleMethods.first().stableStrings,
            monetStringSet(strings.reversed()),
        )
        val encodedSize = MonetTestJson.encodeToString(report).toByteArray().size
        assertTrue(encodedSize < 2_000_000, "bounded DEX report was $encodedSize bytes")
    }
}
