package dev.ujhhgtg.wekit.extensions.monettest

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Properties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.writeText

internal const val MONET_TEST_SCHEMA_VERSION = 1

@Serializable
internal enum class MonetTestInputKind {
    APK,
    APKS,
    DECODED_RES,
}

internal data class MonetTestWorkerConfig(
    val inputKind: MonetTestInputKind,
    val inputPath: Path,
    val nativeLibrary: Path,
    val report: Path,
    val dexKitVersion: String,
    val dexKitRevision: String,
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
) {
    companion object {
        fun fromSystemProperties(properties: Properties): MonetTestWorkerConfig {
            fun required(key: String): String = properties.getProperty(key)
                ?.takeIf(String::isNotBlank)
                ?: error("missing required system property: $key")

            val rawKind = required("wekit.monetTest.inputKind")
            val inputKind = MonetTestInputKind.entries.singleOrNull { it.name == rawKind }
                ?: error("wekit.monetTest.inputKind is invalid: $rawKind")
            val rawChannel = required("wekit.monetTest.isGooglePlay")
            val isGooglePlay = rawChannel.toBooleanStrictOrNull()
                ?: error("wekit.monetTest.isGooglePlay must be true or false, was $rawChannel")
            return MonetTestWorkerConfig(
                inputKind = inputKind,
                inputPath = File(required("wekit.monetTest.inputPath")).toPath()
                    .toAbsolutePath()
                    .normalize(),
                nativeLibrary = File(required("wekit.monetTest.nativeLibrary")).toPath()
                    .toAbsolutePath()
                    .normalize(),
                report = File(required("wekit.monetTest.report")).toPath()
                    .toAbsolutePath()
                    .normalize(),
                dexKitVersion = required("wekit.monetTest.dexKitVersion"),
                dexKitRevision = required("wekit.monetTest.dexKitRevision"),
                versionCode = required("wekit.monetTest.versionCode").toLongOrNull()
                    ?: error("wekit.monetTest.versionCode must be a long"),
                versionName = required("wekit.monetTest.versionName"),
                isGooglePlay = isGooglePlay,
            )
        }
    }
}

@Serializable
internal enum class MonetTestOutcome {
    PASS,
    FAIL,
    INFRASTRUCTURE_FAILURE,
}

@Serializable
internal enum class MonetTestMetadataSource {
    APK_MANIFEST,
    PATH_INFERRED,
}

@Serializable
internal enum class MonetTestDexEvidenceStatus {
    COLLECTED,
    NOT_REQUESTED,
    UNAVAILABLE,
    FAILED,
}

@Serializable
internal enum class MonetTestEvidenceStatus {
    AVAILABLE,
    UNAVAILABLE,
}

@Serializable
internal data class MonetTestError(
    val message: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
)

@Serializable
internal data class MonetTestEnvironment(
    val dexKitVersion: String,
    val dexKitRevision: String,
    val architecture: String,
    val jvmVersion: String,
)

@Serializable
internal data class MonetTestInputMetadata(
    val kind: MonetTestInputKind,
    val path: String,
    val fileName: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val metadataSource: MonetTestMetadataSource,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val isGooglePlay: Boolean? = null,
    val channel: String,
    val nestedApkCount: Int = 0,
    val resourceApkCount: Int = 0,
    val dexCount: Int = 0,
)

@Serializable
internal data class MonetTestDigestEvidence(
    val status: MonetTestEvidenceStatus,
    val value: String? = null,
    val reason: String? = null,
)

@Serializable
internal data class MonetTestProfileMatch(
    val resourceDigest: String,
    val versionName: String,
    val channel: String,
    val roleCount: Int,
)

@Serializable
internal data class MonetTestStructuralProfileMatch(
    val versionName: String,
    val channel: String,
    val roleCount: Int,
    val sourceKind: String? = null,
    val sourceSnapshotSha256: String? = null,
)

@Serializable
internal data class MonetTestResourceSummary(
    val baseResourceDigest: MonetTestDigestEvidence,
    val fullResourceDigest: String,
    val exactProfiles: List<MonetTestProfileMatch>,
    val structuralProfiles: List<MonetTestStructuralProfileMatch> = emptyList(),
    val graphKind: String,
    val binaryXmlShapesComparable: Boolean,
    val limitations: List<String> = emptyList(),
)

@Serializable
internal data class MonetTestResourceTarget(
    val resourceId: Int,
    val type: String,
    val name: String,
)

@Serializable
internal data class MonetTestCandidateSet(
    val totalCount: Int,
    val sortedIdsSha256: String,
    val sampleResourceIds: List<Int>,
    val truncated: Boolean,
)

@Serializable
internal data class MonetTestCandidateStage(
    val stage: String,
    val beforeCandidates: MonetTestCandidateSet,
    val afterCandidates: MonetTestCandidateSet,
)

@Serializable
internal data class MonetTestXmlEvidence(
    val resourceId: Int,
    val type: String,
    val name: String,
    val shapeSha256: List<String>,
    val incomingResources: MonetTestCandidateSet,
    val outgoingResources: MonetTestCandidateSet,
)

@Serializable
internal data class MonetTestRoleReport(
    val roleId: String,
    val core: Boolean,
    val profileTarget: MonetTestResourceTarget? = null,
    val genericCandidates: MonetTestCandidateSet,
    val finalTarget: MonetTestResourceTarget? = null,
    val candidateStages: List<MonetTestCandidateStage>,
    val xmlEvidence: List<MonetTestXmlEvidence>,
    val failure: String? = null,
    val message: String? = null,
)

@Serializable
internal data class MonetTestDexCandidateReport(
    val resourceId: Int,
    val type: String,
    val name: String,
)

@Serializable
internal data class MonetTestFieldAccessReport(
    val descriptor: String,
    val access: String,
)

@Serializable
internal data class MonetTestMethodEvidenceReport(
    val descriptor: String,
    val stableStrings: List<String>,
    val invokedMethodShapes: List<String>,
    val neighboringResourceIds: List<Int>,
    val fieldAccesses: List<MonetTestFieldAccessReport>,
)

@Serializable
internal data class MonetTestResourceDexEvidenceReport(
    val resourceId: Int,
    val methods: List<MonetTestMethodEvidenceReport>,
)

@Serializable
internal data class MonetTestDexQueryReport(
    val candidates: List<MonetTestDexCandidateReport>,
    val evidence: List<MonetTestResourceDexEvidenceReport>,
)

@Serializable
internal data class MonetTestDexEvidenceReport(
    val status: MonetTestDexEvidenceStatus,
    val reason: String? = null,
    val queries: List<MonetTestDexQueryReport> = emptyList(),
)

@Serializable
internal data class MonetTestBlurPaletteReport(
    val lightArgb: Long,
    val nightArgb: Long,
    val lightSource: String,
    val nightSource: String,
)

@Serializable
internal data class MonetTestOverlayReport(
    val overlayId: String,
    val packageName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val roleIds: List<String>,
    val skippedRoleIds: List<String>,
    val kept: Int,
    val added: Int,
    val rewritten: Int,
    val skipped: Int,
    val targetPackage: String,
    val validatedRoleTargets: Map<String, String>,
    val parseable: Boolean,
    val signatureVerified: Boolean,
    val blurPalette: MonetTestBlurPaletteReport? = null,
)

@Serializable
internal data class MonetTestSampleReport(
    val schemaVersion: Int = MONET_TEST_SCHEMA_VERSION,
    val workerPid: Long = ProcessHandle.current().pid(),
    val startedAt: String,
    val finishedAt: String,
    val elapsedMillis: Long,
    val outcome: MonetTestOutcome,
    val environment: MonetTestEnvironment,
    val input: MonetTestInputMetadata,
    val resources: MonetTestResourceSummary? = null,
    val roles: List<MonetTestRoleReport> = emptyList(),
    val coreFailures: List<String> = emptyList(),
    val optionalSkips: List<String> = emptyList(),
    val dexEvidence: MonetTestDexEvidenceReport,
    val overlays: List<MonetTestOverlayReport> = emptyList(),
    val failureMessage: String? = null,
    val infrastructureError: MonetTestError? = null,
)

internal val MonetTestJson = Json {
    encodeDefaults = true
    prettyPrint = true
    ignoreUnknownKeys = false
}

internal fun MonetTestSampleReport.writeAtomically(path: Path) {
    Files.createDirectories(requireNotNull(path.parent) { "Monet test report has no parent: $path" })
    val temporary = path.resolveSibling(".${path.fileName}.tmp")
    temporary.writeText(MonetTestJson.encodeToString(this))
    try {
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, REPLACE_EXISTING)
    }
}

internal fun Throwable.toMonetTestError() = MonetTestError(
    message = message,
    exceptionType = javaClass.name,
    stackTrace = stackTraceToString(),
)

internal fun monetCandidateSet(resourceIds: Collection<Int>): MonetTestCandidateSet {
    val sorted = resourceIds.distinct().sortedBy(Int::toUInt)
    val canonical = sorted.joinToString(",") { resourceId -> resourceId.toUInt().toString() }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    val truncated = sorted.size > MAX_INLINE_CANDIDATE_IDS
    val sample = if (truncated) {
        sorted.take(MAX_INLINE_CANDIDATE_IDS / 2) + sorted.takeLast(MAX_INLINE_CANDIDATE_IDS / 2)
    } else {
        sorted
    }
    return MonetTestCandidateSet(
        totalCount = sorted.size,
        sortedIdsSha256 = digest,
        sampleResourceIds = sample,
        truncated = truncated,
    )
}

private const val MAX_INLINE_CANDIDATE_IDS = 64
