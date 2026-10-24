package dev.ujhhgtg.wekit.loader.environment

import java.util.Properties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NormsgRootCodecTest {
    private fun resource(name: String) =
        javaClass.getResourceAsStream("/environment/$name")!!.use { it.readBytes() }

    private val report by lazy { NormsgReportCodec(resource("normsg-tables.bin")) }
    private val policy =
        EnvironmentReportPolicy(
            "dev.ujhhgtg.wekit",
            "/data/app/module/base.apk",
            "/data/user/0/com.tencent.mm/files/wekit",
        )

    @Test
    fun nativeOracleProvesEncodingAndPreservationOfUnknownBits() {
        val cases = (0..13).map { "inner-oracle-$it" } + "inner-root-microzero"
        for (name in cases) {
            val input =
                Properties().apply {
                    resource("normsg/$name.properties").inputStream().use { load(it) }
                }
            val original = resource("normsg/$name.return")
            val message = ReportWire.bytes(report.decode(original), 3)
            val codec = NormsgRootCodec(message)
            val root = java.lang.Long.parseUnsignedLong(input.getProperty("root"))
            assertEquals(root, codec.decode(message), name)
            assertArrayEquals(message, codec.encode(root, message), name)
            val changedReport = report.rewrite(original, policy)
            val decoded = report.decode(changedReport)
            val changed = ReportWire.bytes(decoded, 3)
            assertEquals(root and 7L.inv(), NormsgRootCodec(changed).decode(changed), name)
            // The user's narrowed scope explicitly leaves method attributes unchanged.
            assertArrayEquals(ReportWire.bytes(message, 206), ReportWire.bytes(changed, 206), name)
            assertArrayEquals(changedReport, report.encode(decoded, changedReport), name)
            assertSame(changedReport, report.rewrite(changedReport, policy), name)
        }
    }

    @Test
    fun rootRewriteMatchesActualNativeAbsentPackagesReport() {
        val clean = resource("normsg/fields-packages-base.return")
        for (name in listOf("root-ksu", "root-magisk", "root-apatch", "all-roots")) {
            val changed = report.rewrite(resource("normsg/fields-$name.return"), policy)
            assertArrayEquals(clean, changed, name)
        }
    }

    @Test
    fun checksumAndHeaderCorruptionAreRejected() {
        val message = ReportWire.bytes(report.decode(resource("normsg/fields-all-roots.return")), 3)
        val corrupt = ReportWire.replace(message, 232, ReportWire.encode(232, 0L))
        assertThrows(IllegalArgumentException::class.java) { policy.rewriteFields(corrupt) }
        for (tag in listOf(88, 89)) {
            val bad = ReportWire.replace(message, tag, ReportWire.encode(tag, -1L))
            assertThrows(IllegalArgumentException::class.java) { policy.rewriteFields(bad) }
        }
    }
}
