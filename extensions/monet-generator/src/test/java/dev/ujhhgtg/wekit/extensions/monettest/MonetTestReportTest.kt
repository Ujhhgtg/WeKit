package dev.ujhhgtg.wekit.extensions.monettest

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
}
