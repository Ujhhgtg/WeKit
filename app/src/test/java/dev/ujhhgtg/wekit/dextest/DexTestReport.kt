package dev.ujhhgtg.wekit.dextest

import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val DEX_TEST_SCHEMA_VERSION = 2

internal val DexTestJson = Json {
    encodeDefaults = true
    prettyPrint = true
    ignoreUnknownKeys = true
}

@Serializable
internal data class DexTestError(
    val message: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
)

@Serializable
internal data class DexTestDelegateReport(
    val key: String,
    val status: DexResolutionStatus,
    val descriptor: String? = null,
    val isPlaceholder: Boolean = false,
    val message: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
    val blockedBy: String? = null,
)

@Serializable
internal data class DexTestFeatureReport(
    val className: String,
    val displayName: String,
    /**
     * 设备端云缓存按此字段匹配 feature；仅在类加载失败时为空字符串（该类 outcome 必然 FAIL，不会进入发布资产）。
     */
    val technicalId: String = "",
    val methodHash: String = "",
    val outcome: DexTestFeatureOutcome,
    val elapsedMillis: Long,
    val delegates: List<DexTestDelegateReport> = emptyList(),
    val featureError: DexTestError? = null,
)

@Serializable
internal enum class DexTestFeatureOutcome {
    PASS,
    PASS_WITH_EXPECTED_FAILURES,
    FAIL,
    INITIALIZATION_FAILURE,
}

@Serializable
internal enum class DexTestApkOutcome {
    PASS,
    FAIL,
    INFRASTRUCTURE_FAILURE,
}

@Serializable
internal data class DexTestEnvironment(
    val dexKitVersion: String,
    val dexKitRevision: String,
    val architecture: String,
    val jvmVersion: String,
)

@Serializable
internal data class DexTestCounts(
    val success: Int = 0,
    val expectedFailure: Int = 0,
    val unexpectedFailure: Int = 0,
    val blocked: Int = 0,
    val incomplete: Int = 0,
)

@Serializable
internal data class DexTestApkReport(
    val schemaVersion: Int = DEX_TEST_SCHEMA_VERSION,
    val workerPid: Long = ProcessHandle.current().pid(),
    val apkPath: String,
    val fileName: String,
    val label: String,
    val apkSize: Long = 0,
    val apkSha256: String = "",
    val versionCode: Long = 0,
    val versionName: String = "",
    val buildTag: String = "",
    val isGooglePlay: Boolean = false,
    val dexCount: Int = 0,
    val environment: DexTestEnvironment,
    val startedAt: String,
    val finishedAt: String,
    val elapsedMillis: Long,
    val outcome: DexTestApkOutcome,
    val counts: DexTestCounts = DexTestCounts(),
    val features: List<DexTestFeatureReport> = emptyList(),
    val infrastructureError: DexTestError? = null,
)

internal fun DexTestApkReport.writeAtomically(path: Path) {
    Files.createDirectories(path.parent)
    val temp = path.resolveSibling(".${path.fileName}.tmp")
    temp.writeText(DexTestJson.encodeToString(this))
    try {
        Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp, path, REPLACE_EXISTING)
    }
}

internal fun DexResolutionStatus.toDexTestReport(key: String): DexTestDelegateReport =
    DexTestDelegateReport(key = key, status = this)
