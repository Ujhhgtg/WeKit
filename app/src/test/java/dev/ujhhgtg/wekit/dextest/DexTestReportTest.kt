package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DexTestReportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun schemaTwoReportRoundTripsGraphMetadataAndDiagnostics() {
        val dependencyA = "dev.example.Api#first"
        val dependencyB = "dev.example.Api#second"
        val report = sampleReport(
            delegate = DexTestDelegateReport(
                id = "dev.example.Fixture#method",
                status = DexResolutionStatus.UNEXPECTED_FAILURE,
                producerFingerprint = "producer-fingerprint",
                effectiveFingerprint = null,
                dependencies = listOf(dependencyA, dependencyB),
                message = "boom",
                exceptionType = "java.lang.IllegalStateException",
                stackTrace = "boom\n at test",
                blockedBy = dependencyA,
            )
        )
        val json = DexTestJson.encodeToString(report)

        assertEquals(2, report.schemaVersion)
        assertTrue(json.contains("\"id\": \"dev.example.Fixture#method\""))
        assertTrue(json.contains("\"producerFingerprint\": \"producer-fingerprint\""))
        assertTrue(json.contains("\"effectiveFingerprint\": null"))
        assertTrue(json.indexOf(dependencyA) < json.indexOf(dependencyB))
        assertFalse(json.contains("methodHash"))
        assertEquals(report, DexTestJson.decodeFromString<DexTestApkReport>(json))
    }

    @Test
    fun atomicWriterLeavesOnlyCompletedJson() {
        val path = tempDir.resolve("wechat_8069.json")
        sampleReport().writeAtomically(path)
        assertTrue(Files.exists(path))
        assertFalse(Files.exists(tempDir.resolve(".wechat_8069.json.tmp")))
    }

    private fun sampleReport(
        delegate: DexTestDelegateReport = DexTestDelegateReport(
            id = "dev.example.Fixture#method",
            status = DexResolutionStatus.SUCCESS,
            producerFingerprint = "producer-fingerprint",
            effectiveFingerprint = "effective-fingerprint",
        ),
    ) =
        DexTestApkReport(
            apkPath = "/tmp/wechat_8069.apk",
            fileName = "wechat_8069.apk",
            label = "wechat_8069",
            environment = DexTestEnvironment("2.2.0", "revision", "x86_64", "21"),
            startedAt = "2026-08-04T00:00:00Z",
            finishedAt = "2026-08-04T00:00:01Z",
            elapsedMillis = 1000,
            outcome = DexTestApkOutcome.PASS,
            features = listOf(
                DexTestFeatureReport(
                    className = "Fixture",
                    displayName = "测试/Fixture",
                    outcome = DexTestFeatureOutcome.PASS,
                    elapsedMillis = 1,
                    delegates = listOf(delegate),
                )
            ),
        )
}
