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

    @Test
    fun `batch associations expose resolved resource neighbors to candidate methods`() {
        val sharedMethod = "La/b;->c()V"

        val neighboringIds = neighboringResourceIdsForMethod(
            candidateResourceId = 0x7f080001,
            methodDescriptor = sharedMethod,
            methodDescriptorsByResourceId = mapOf(
                0x7f080001 to listOf(sharedMethod),
                0x7f080003 to listOf("La/b;->other()V"),
                0x7f0d0002 to listOf(sharedMethod),
            ),
        )

        assertEquals(listOf(0x7f0d0002), neighboringIds)
    }

    @Test
    fun `platform and open sdk public methods retain stable owner and name`() {
        assertEquals(
            "android.view.View#setBackgroundResource(int):void",
            normalizeInvokedMethodShape(
                owner = "android.view.View",
                name = "setBackgroundResource",
                paramTypeNames = listOf("int"),
                returnTypeName = "void",
            ),
        )
        assertEquals(
            "com.tencent.mm.opensdk.openapi.IWXAPI#sendReq(com.tencent.mm.opensdk.modelbase.BaseReq):boolean",
            normalizeInvokedMethodShape(
                owner = "com.tencent.mm.opensdk.openapi.IWXAPI",
                name = "sendReq",
                paramTypeNames = listOf("com.tencent.mm.opensdk.modelbase.BaseReq"),
                returnTypeName = "boolean",
            ),
        )
    }

    @Test
    fun `bundled and obfuscated owners produce owner independent method shapes`() {
        assertEquals(
            "(object):void",
            normalizeInvokedMethodShape(
                owner = "androidx.appcompat.app.g0",
                name = "a",
                paramTypeNames = listOf("androidx.appcompat.app.g0"),
                returnTypeName = "void",
            ),
        )
        assertEquals(
            "(java.lang.String,java.lang.String,java.lang.Object[]):void",
            normalizeInvokedMethodShape(
                owner = "com.tencent.mm.sdk.platformtools.Log",
                name = "a",
                paramTypeNames = listOf(
                    "java.lang.String",
                    "java.lang.String",
                    "java.lang.Object[]",
                ),
                returnTypeName = "void",
            ),
        )
        assertEquals(
            "():void",
            normalizeInvokedMethodShape(
                owner = "com.tencent.mm.SomeCapitalizedOwner",
                name = "descriptiveName",
                paramTypeNames = emptyList(),
                returnTypeName = "void",
            ),
        )
    }
}
