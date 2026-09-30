package dev.ujhhgtg.wekit.loader.environment

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.zip.CRC32

class NormsgReportCodecTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/environment/$name")!!.use { it.readBytes() }
    private val codec by lazy { NormsgReportCodec(resource("normsg-tables.bin")) }
    private val policy = EnvironmentReportPolicy("dev.ujhhgtg.wekit", "/data/app/module/base.apk", "/data/user/0/com.tencent.mm/files/wekit")

    @Test
    fun nativeVectorsDecodeAndReencodeByteExactly() {
        for (name in listOf("codec-tables", "codec-adb", "baseline-repeat", "adb", "xposed-one", "mode0", "mode32", "mode1008", "sdk34", "native-repack-adb", "native-repack-xposed")) {
            val original = resource("normsg/$name.return")
            val expected = resource("normsg/$name.clear")
            assertArrayEquals(expected, codec.decode(original), name)
            assertArrayEquals(original, codec.encode(expected, original), name)
        }
    }

    @Test
    fun olderNativeLibrariesHaveTheSameCodecAndConfirmedFieldSemantics() {
        for (version in listOf("8065", "8067")) for (profile in listOf("baseline", "su", "debugger", "adb", "xposed-one", "maps")) {
            val name = "compat-$version" + if (profile == "baseline") "" else "-$profile"
            val original = resource("normsg/$name.return")
            val expected = resource("normsg/$name.clear")
            assertArrayEquals(expected, codec.decode(original), name)
            assertArrayEquals(original, codec.encode(expected, original), name)
            val before = ReportWire.bytes(expected, 3)
            val after = ReportWire.bytes(codec.decode(codec.rewrite(original, policy)), 3)
            val booleanField = when (profile) { "su" -> 2; "debugger" -> 3; "adb" -> 53; else -> null }
            if (booleanField != null) {
                assertEquals(1L, ReportWire.number(before, booleanField), name)
                assertEquals(0L, ReportWire.number(after, booleanField), name)
            }
            if (profile == "xposed-one") {
                assertTrue(ReportWire.fields(before).any { it.number == 52 }, name)
                assertFalse(ReportWire.fields(after).any { it.number == 52 }, name)
            }
            if (profile == "maps") {
                assertTrue(String(before).contains("/data/app/dev.ujhhgtg.wekit/base.apk"), name)
                assertFalse(String(after).contains("/data/app/dev.ujhhgtg.wekit/base.apk"), name)
            }
        }
    }

    @Test
    fun variableLengthRewritePreservesUnknownAndUntargetedRawFields() {
        val original = resource("normsg/xposed-one.return")
        val oldClear = codec.decode(original)
        val oldFields = ReportWire.bytes(oldClear, 3)
        assertTrue(ReportWire.fields(oldFields).any { it.number == 52 })
        val rewritten = codec.rewrite(original, policy)
        val clear = codec.decode(rewritten)
        val fields = ReportWire.bytes(clear, 3)
        assertFalse(ReportWire.fields(fields).any { it.number == 52 })
        val expected = policy.rewriteFields(oldFields)
        assertArrayEquals(expected, fields)
        assertEquals(CRC32().apply { update(fields) }.value, ReportWire.number(clear, 1))
        for (tag in listOf(4, 8)) assertEquals(ReportWire.number(original, tag), ReportWire.number(rewritten, tag))
        val untouched = { bytes: ByteArray -> ReportWire.rewrite(bytes) {
            if (it.number in setOf(2, 3, 36, 52, 53, 54, 86, 128, 231, 232)) ByteArray(0) else null
        } }
        assertArrayEquals(untouched(oldFields), untouched(fields))
        assertSame(rewritten, codec.rewrite(rewritten, policy))
    }

    @Test
    fun adbScalarRewriteSurvivesFullEnvelopeRoundtrip() {
        val report = resource("normsg/codec-adb.return")
        assertEquals(1L, ReportWire.number(ReportWire.bytes(codec.decode(report), 3), 53))
        val changed = codec.rewrite(report, policy)
        assertEquals(0L, ReportWire.number(ReportWire.bytes(codec.decode(changed), 3), 53))
        assertArrayEquals(changed, codec.encode(codec.decode(changed), changed))
    }

    @Test
    fun unexpectedTypesAndUnknownFieldsRetainTheirExactBytes() {
        val message = ReportWire.encode(2, 7L) + ReportWire.encode(3, byteArrayOf(1)) +
            ReportWire.encode(52, 99L) + ReportWire.encode(36, "/system/lib64/libc.so".toByteArray()) +
            ReportWire.encode(36, "/data/app/dev.ujhhgtg.wekit-abc/base.apk".toByteArray()) +
            ReportWire.encode(901, byteArrayOf(0, -1, -128)) + ReportWire.encode(901, 99123L)
        val rewritten = policy.rewriteFields(message)
        val expected = ReportWire.rewrite(message) {
            if (it.number == 36 && String(message, it.data, it.end - it.data).contains("wekit")) ByteArray(0) else null
        }
        assertArrayEquals(expected, rewritten)
    }

    @Test
    fun expandedPolicyRewritesNativeReportsAcrossAllThreeLibraries() {
        for (version in listOf("8065", "8067", "8079")) {
            for (probe in listOf("base", "tracer17", "tracing1", "app-debuggable", "monkey", "fd-linjector", "thread-gum", "fd-gum-usec2", "usec2-all-debug")) {
                val name = "policy-$version-$probe"
                val original = resource("normsg/$name.return")
                val before = ReportWire.bytes(codec.decode(original), 3)
                val changed = codec.rewrite(original, policy)
                val decoded = codec.decode(changed) // Independently checks padding, zlib and CRC32.
                val after = ReportWire.bytes(decoded, 3)
                assertEquals(0L, ReportWire.number(after, 54), name)
                val before86 = ReportWire.number(before, 86)
                val before128 = ReportWire.number(before, 128)
                val after86 = ReportWire.number(after, 86)
                val after128 = ReportWire.number(after, 128)
                assertEquals(0L, after86 and 0x54, name)
                assertEquals(0L, after128 and 0x50, name)
                // Carrier data and every unconfirmed bit must survive, including high uint32 bits.
                assertEquals(before86 and 0x54L.inv(), after86 and 0x54L.inv(), name)
                assertEquals(before128 and 0x50L.inv(), after128 and 0x50L.inv(), name)
                fun uneditedFields(bytes: ByteArray) = ReportWire.rewrite(bytes) {
                    if (it.number in setOf(2, 3, 36, 52, 53, 54, 86, 128, 231, 232)) ByteArray(0) else null
                }
                assertArrayEquals(uneditedFields(before), uneditedFields(after), name)
                fun envelopeFields(bytes: ByteArray) = ReportWire.rewrite(bytes) {
                    if (it.number == 3) ByteArray(0) else null
                }
                assertArrayEquals(envelopeFields(original), envelopeFields(changed), name)
                assertArrayEquals(changed, codec.encode(decoded, changed), name)
                assertSame(changed, codec.rewrite(changed, policy), name)
            }
        }
    }

    @Test
    fun alreadyClearCarriersAndUnknownRepresentationsRetainTheirOriginalEncoding() {
        // Noncanonical but valid encodings of zero: unchanged fields must not be normalized.
        val zero86 = byteArrayOf(0xb0.toByte(), 0x05, 0x80.toByte(), 0x00)
        val zero128 = byteArrayOf(0x80.toByte(), 0x08, 0x80.toByte(), 0x00)
        val unknown = ReportWire.encode(86, byteArrayOf(0x54)) +
            ReportWire.encode(128, byteArrayOf(0x50)) +
            ReportWire.encode(86, (1L shl 40) or 0x54) +
            ReportWire.encode(128, (1L shl 40) or 0x50) + ReportWire.encode(54, 3L)
        val message = zero86 + zero128 + unknown
        assertArrayEquals(message, policy.rewriteFields(message))
    }

    @Test
    fun rejectsBrokenEnvelopeAndWireInsteadOfReturningPartialMessage() {
        val report = resource("normsg/codec-adb.return")
        val clear = codec.decode(report)
        val badCrc = ReportWire.replace(clear, 1, ReportWire.encode(1, 0L))
        assertThrows(Exception::class.java) { codec.encode(badCrc, report) }
        assertThrows(Exception::class.java) { codec.decode(report.copyOf(report.size / 2)) }
        assertThrows(Exception::class.java) { codec.decode(ReportWire.replace(report, 3, ReportWire.encode(3, ByteArray(16)))) }
        for (bad in listOf(byteArrayOf(0), byteArrayOf(10, -1), byteArrayOf(9, 1), ByteArray(11) { -128 })) {
            assertThrows(Exception::class.java) { ReportWire.fields(bad) }
        }
    }

    @Test
    fun parsesPointerSeparatedClientCheckHookWithoutChangingUnrelatedMappings() {
        val paths = listOf("/data/app/dev.ujhhgtg.wekit-abc/base.apk", "/memfd:wk (deleted)", "/memfd:jit-cache (deleted)", "/system/lib64/libc.so", "[anon:dalvik-jit-code-cache]")
        for (delimiter in listOf("0x6c7a164b", ",")) {
            val actual = policy.rewriteClientCheckHook(paths.joinToString(delimiter), ",")
            assertEquals(paths.drop(2).joinToString(delimiter), actual)
        }
        val ambiguous = "/data/app/module/base.apk0x11/system/a.so0x22/system/b.so"
        assertEquals(ambiguous, policy.rewriteClientCheckHook(ambiguous, ","))
    }
}
