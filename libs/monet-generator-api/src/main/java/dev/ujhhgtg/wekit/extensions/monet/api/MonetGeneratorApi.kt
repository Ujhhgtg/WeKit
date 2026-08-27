package dev.ujhhgtg.wekit.extensions.monet.api

import android.content.res.Resources
import java.io.File

const val MONET_GENERATOR_API_VERSION = 1
const val MONET_GENERATOR_ENTRYPOINT =
    "dev.ujhhgtg.wekit.extensions.monet.MonetGeneratorEntrypoint"

interface MonetGeneratorApi {
    fun generate(request: MonetGenerationRequest, listener: MonetGenerationListener): MonetGenerationResult
}

data class MonetGenerationRequest(
    val resources: Resources,
    val packageName: String,
    val sourceApkPath: String,
    val sourceApkPaths: List<String> = listOf(sourceApkPath),
    val versionCode: Long,
    val versionName: String,
    val sdkInt: Int,
    val dexEvidenceProvider: MonetDexEvidenceProvider,
    val options: MonetGenerationOptions = MonetGenerationOptions(),
    val payloadDir: File,
    val workDir: File,
    val outputZip: File,
)

data class MonetGenerationOptions(
    val bubbleStyle: MonetBubbleStyle = MonetBubbleStyle.MODERN,
    val multiSceneCorners: Boolean = true,
    val tabStyle: MonetTabStyle = MonetTabStyle.SOLID,
    val userScope: MonetUserScope = MonetUserScope.CURRENT,
    val currentUserId: Int = 0,
    val blurLightArgb: Int? = null,
    val blurNightArgb: Int? = null,
)

enum class MonetBubbleStyle { MODERN, CLASSIC, PRO }
enum class MonetTabStyle { SOLID, BLUR }
enum class MonetUserScope { CURRENT, ALL }

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
    val ownerPackage: String,
    val methodShape: String,
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
    data class Progress(
        val stage: MonetGenerationStage,
        val detail: String,
        val completed: Int?,
        val total: Int?,
    ) : MonetGenerationEvent {
        init {
            require(detail.isNotBlank())
            require((completed == null) == (total == null))
            if (completed != null && total != null) require(total > 0 && completed in 0..total)
        }
    }
    data class Log(
        val level: MonetLogLevel,
        val message: String,
        val error: Throwable? = null,
    ) : MonetGenerationEvent
}

enum class MonetGenerationStage {
    LOADING_APKS,
    BUILDING_RESOURCE_GRAPH,
    RESOLVING_ROLES,
    BUILDING_OVERLAY,
    SIGNING,
    PACKAGING,
}

enum class MonetLogLevel { DEBUG, INFO, WARN, ERROR }

data class MonetGenerationResult(
    val outputZip: File,
    val kept: Int,
    val pruned: Int,
    val added: Int,
)
