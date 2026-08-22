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
    val payloadDir: File,
    val workDir: File,
    val outputZip: File,
)

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
