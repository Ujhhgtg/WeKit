package dev.ujhhgtg.wekit.extensions.monet

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal enum class MonetResolutionStage {
    SDK_AND_TYPE,
    CONFIG_VALUES,
    XML_SHAPE_AND_REFERENCES,
    INCOMING_RELATIONSHIPS,
    DEX_ANCHORS,
}

@Serializable
internal enum class MonetResolutionFailure {
    NOT_FOUND,
    AMBIGUOUS,
    PROFILE_DRIFT,
    DEPENDENCY_UNRESOLVED,
    DEX_EVIDENCE_FAILED,
    SDK_UNSUPPORTED,
}

@Serializable
internal data class MonetCandidateStageDiagnostic(
    val stage: MonetResolutionStage,
    val beforeCandidateIds: List<Int>,
    val afterCandidateIds: List<Int>,
)

@Serializable
internal data class MonetRoleDiagnostic(
    val roleId: String,
    val core: Boolean,
    val failure: MonetResolutionFailure? = null,
    val candidateIds: List<Int>,
    val profileCandidateId: Int? = null,
    val stages: List<MonetCandidateStageDiagnostic>,
    val message: String? = null,
)

@Serializable
internal data class MonetResolvedRole(
    val roleId: String,
    val resourceId: Int,
    @Serializable(with = MonetResourceKeySerializer::class)
    val key: MonetResourceKey,
    val profileMatched: Boolean,
)

@Serializable
internal data class MonetResolutionReport(
    val resolved: Map<String, MonetResolvedRole>,
    val skipped: List<MonetRoleDiagnostic>,
    val diagnostics: Map<String, MonetRoleDiagnostic>,
)

internal class MonetResolutionException(
    val diagnostic: MonetRoleDiagnostic,
    cause: Throwable? = null,
    val report: MonetResolutionReport = MonetResolutionReport(
        resolved = emptyMap(),
        skipped = emptyList(),
        diagnostics = mapOf(diagnostic.roleId to diagnostic),
    ),
) : IllegalStateException(
    buildString {
        append("Monet role ").append(diagnostic.roleId)
        append(" failed: ").append(diagnostic.failure)
        diagnostic.message?.let { append(" (").append(it).append(')') }
    },
    cause,
)

internal object MonetResolutionDiagnostics {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(report: MonetResolutionReport): String = json.encodeToString(report)

    fun write(report: MonetResolutionReport, output: File) {
        output.parentFile?.mkdirs()
        output.writeText(encode(report))
    }
}
