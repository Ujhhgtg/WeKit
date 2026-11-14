package dev.ujhhgtg.wekit.loader.environment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NormsgMethodCodecTest {
    private fun resource(name: String) =
        javaClass.getResourceAsStream("/environment/$name")!!.use { it.readBytes() }

    private val report by lazy { NormsgReportCodec(resource("normsg-tables.bin")) }
    private val tags = listOf(7, 8, 9, 12, 14, 15, 16)
    private val observed =
        listOf(
            NormsgMethodCodec.Flags(9, 265),
            NormsgMethodCodec.Flags(265, 9),
            NormsgMethodCodec.Flags(-2147483639, 0x12345679),
            NormsgMethodCodec.Flags(-1, 265),
            NormsgMethodCodec.Flags(0, -1),
            NormsgMethodCodec.Flags(0x12345678, -2147483639),
            NormsgMethodCodec.Flags(-2147483648, 0),
        )

    private fun fixture(clock: String, kind: String) =
        resource("normsg/method-flags-$clock-$kind.return")

    private fun data(bytes: ByteArray) = ReportWire.bytes(report.decode(bytes), 3)

    private fun rawField(bytes: ByteArray, number: Int): ByteArray {
        val field = ReportWire.fields(bytes).single { it.number == number }
        return bytes.copyOfRange(field.start, field.end)
    }

    private fun policy(flags: Map<Int, NormsgMethodCodec.Flags> = emptyMap()) =
        EnvironmentReportPolicy(
            "dev.ujhhgtg.wekit",
            "/module.apk",
            "/files/wekit",
            methodFlags = { flags },
        )

    @Test
    fun decodesIndependentNativeGetterAndFieldInputsIncludingSignedJints() {
        for (clock in listOf("normal", "zero-bytes")) {
            val message = data(fixture(clock, "mixed"))
            val codec = NormsgMethodCodec(message)
            val methods = ReportWire.bytes(message, 206)
            tags.zip(observed).forEach { (tag, expected) ->
                assertEquals(expected, codec.decode(ReportWire.bytes(methods, tag)), "$clock/$tag")
            }
        }
    }

    @Test
    fun restoresFlagsToNativeCleanPairsAndPreservesDetailsFailureSentinelsAndUnknownFields() {
        val baseline = tags.associateWith { NormsgMethodCodec.Flags(9, 9) }
        for (clock in listOf("normal", "zero-bytes")) {
            val original = fixture(clock, "mixed")
            val message = data(original)
            val before = ReportWire.bytes(message, 206)
            val clean = ReportWire.bytes(data(fixture(clock, "clean")), 206)
            val extended = message + ReportWire.encode(350, byteArrayOf(1, 2, 3))
            val changed = policy(baseline).rewriteFields(extended)
            assertArrayEquals(rawField(extended, 350), rawField(changed, 350))
            val after = ReportWire.bytes(changed, 206)
            tags.zip(observed).forEach { (tag, input) ->
                val old = ReportWire.bytes(before, tag)
                val restored = ReportWire.bytes(after, tag)
                val reference = ReportWire.bytes(clean, tag)
                for ((value, pair) in listOf(input.getter to (1 to 2), input.field to (3 to 4))) {
                    val expected = if (value == -1L) old else reference
                    assertArrayEquals(
                        rawField(expected, pair.first),
                        rawField(restored, pair.first),
                        "$clock/$tag flags",
                    )
                    assertArrayEquals(
                        rawField(expected, pair.second),
                        rawField(restored, pair.second),
                        "$clock/$tag CRC",
                    )
                }
                for (field in ReportWire.fields(old).filter { it.number !in 1..4 }) {
                    assertArrayEquals(
                        rawField(old, field.number),
                        rawField(restored, field.number),
                        "$clock/$tag detail ${field.number}",
                    )
                }
            }
            val rewritten = report.rewrite(original, policy(baseline))
            val decoded = report.decode(rewritten)
            assertArrayEquals(rewritten, report.encode(decoded, rewritten))
            assertSame(rewritten, report.rewrite(rewritten, policy(baseline)))
        }
    }

    @Test
    fun leavesUncapturedMethodsAndUncapturedReadUntouched() {
        val message = data(fixture("normal", "mixed"))
        val methods = ReportWire.bytes(message, 206)
        assertArrayEquals(methods, ReportWire.bytes(policy().rewriteFields(message), 206))
        val changed = policy(mapOf(7 to NormsgMethodCodec.Flags(null, 9))).rewriteFields(message)
        val after = ReportWire.bytes(changed, 206)
        tags
            .filter { it != 7 }
            .forEach { tag ->
                assertArrayEquals(rawField(methods, tag), rawField(after, tag))
            }
        val beforeMethod = ReportWire.bytes(methods, 7)
        val afterMethod = ReportWire.bytes(after, 7)
        for (tag in listOf(1, 2, 5, 6, 7, 8, 9, 10)) {
            assertArrayEquals(rawField(beforeMethod, tag), rawField(afterMethod, tag))
        }
    }

    @Test
    fun rejectsCorruptedInnerChecksumsBeforeRewritingReport() {
        val message = data(fixture("normal", "mixed"))
        val codec = NormsgMethodCodec(message)
        val method = ReportWire.bytes(ReportWire.bytes(message, 206), 7)
        for (tag in listOf(2, 4)) {
            val bad = ReportWire.replace(method, tag, ReportWire.encode(tag, 0L))
            assertThrows(IllegalArgumentException::class.java) {
                codec.restore(bad, NormsgMethodCodec.Flags(9, 9))
            }
        }
    }

    @Test
    fun restoresFreshOldAndNewNativeReportsWithoutRequiringAllSevenMethods() {
        val originals = tags.associateWith { NormsgMethodCodec.Flags(9, 9) }
        for (version in listOf("8065", "8067", "8080")) {
            val clean = data(resource("normsg/$version-base.return"))
            val original = resource("normsg/$version-method-3.return")
            val before = data(original)
            val restored = report.rewrite(original, policy(originals))
            val after = data(restored)
            assertArrayEquals(ReportWire.bytes(clean, 206), ReportWire.bytes(after, 206), version)
            if (version == "8080") {
                // The fourth package bit added in 8.0.80 is outside the confirmed root-manager
                // mask.
                assertEquals(8L, NormsgRootCodec(after).decode(after))
            }
            for (field in
                ReportWire.fields(before).filter {
                    it.number !in setOf(2, 3, 36, 52, 53, 54, 86, 128, 206, 231, 232)
                }) {
                assertArrayEquals(
                    rawField(before, field.number),
                    rawField(after, field.number),
                    "$version/${field.number}",
                )
            }
            assertSame(restored, report.rewrite(restored, policy(originals)))
        }
    }
}
