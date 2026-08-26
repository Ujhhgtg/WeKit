package dev.ujhhgtg.wekit.extensions.monet.api

import android.content.res.Resources
import java.io.File

const val MONET_GENERATOR_API_VERSION = 1
const val MONET_GENERATOR_ENTRYPOINT_V1 =
    "dev.ujhhgtg.wekit.extensions.monet.MonetGeneratorEntrypointV1"

interface MonetGeneratorApiV1 {
    fun generate(request: MonetGenerationRequest, listener: MonetGenerationListener): MonetGenerationResult
}

data class MonetGenerationRequest(
    val resources: Resources,
    val packageName: String,
    val sourceApkPath: String,
    val versionCode: Long,
    val versionName: String,
    val sdkInt: Int,
    val dexEvidenceProvider: MonetDexEvidenceProvider,
    val payloadDir: File,
    val workDir: File,
    val outputZip: File,
)

fun interface MonetDexEvidenceProvider {
    fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence>
}

data class MonetDexCandidate(val resourceId: Int, val type: String, val name: String)

data class MonetResourceDexEvidence(
    val resourceId: Int,
    val methods: List<MonetMethodDexEvidence>,
)

data class MonetMethodDexEvidence(
    val descriptor: String,
    val stableStrings: List<String>,
    val invokedMethodShapes: List<String>,
    val neighboringResourceIds: List<Int>,
    val fieldAccesses: List<MonetFieldAccessEvidence>,
)

data class MonetFieldAccessEvidence(val descriptor: String, val access: MonetFieldAccess)

enum class MonetFieldAccess { READ, WRITE }

fun interface MonetGenerationListener {
    fun onEvent(event: MonetGenerationEvent)
}

sealed interface MonetGenerationEvent {
    data class Progress(val stage: MonetGenerationStage) : MonetGenerationEvent
    data class Log(
        val level: MonetLogLevel,
        val message: String,
        val error: Throwable? = null,
    ) : MonetGenerationEvent
}

enum class MonetGenerationStage { PREPARING, BUILDING_OVERLAY, SIGNING, PACKAGING }

enum class MonetLogLevel { DEBUG, INFO, WARN, ERROR }

data class MonetGenerationResult(
    val outputZip: File,
    val kept: Int,
    val pruned: Int,
    val added: Int,
)
