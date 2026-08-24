package dev.ujhhgtg.wekit.extensions.monet.evidence

import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccess
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccessEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonetEvidenceNormalizationTest {

    @Test
    fun `method evidence is sorted and deduplicated`() {
        val readField = MonetFieldAccessEvidence("La/b;->d:I", MonetFieldAccess.READ)
        val writeField = MonetFieldAccessEvidence("La/b;->d:I", MonetFieldAccess.WRITE)

        val normalized = normalizeMethodEvidence(
            descriptor = "La/b;->c()V",
            strings = listOf("tag", "tag", "stable"),
            invokes = listOf(
                "android.view.View#setBackgroundResource(int):void",
                "android.view.View#setBackgroundResource(int):void",
            ),
            resourceIds = listOf(0x7f080002, 0x7f080001, 0x7f080002),
            fields = listOf(readField, readField, writeField),
        )

        assertEquals("La/b;->c()V", normalized.descriptor)
        assertEquals(listOf("stable", "tag"), normalized.stableStrings)
        assertEquals(
            listOf("android.view.View#setBackgroundResource(int):void"),
            normalized.invokedMethodShapes,
        )
        assertEquals(listOf(0x7f080001, 0x7f080002), normalized.neighboringResourceIds)
        assertEquals(listOf(readField, writeField), normalized.fieldAccesses)
    }
}
