package dev.ujhhgtg.wekit.extensions.monet.api

import android.content.res.Resources
import java.io.File

const val MONET_GENERATOR_API_VERSION_V2 = 2
const val MONET_GENERATOR_ENTRYPOINT_V2 =
    "dev.ujhhgtg.wekit.extensions.monet.MonetGeneratorEntrypointV2"

interface MonetGeneratorApiV2 {
    fun generate(
        request: MonetGenerationRequestV2,
        listener: MonetGenerationListenerV2,
    ): MonetGenerationResultV2
}

data class MonetGenerationOptions(
    val bubbleStyle: MonetBubbleStyle,
    val multiSceneCornersEnabled: Boolean,
    val tabStyle: MonetTabStyle,
    val userScope: MonetUserScope,
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
    val stableStrings: List<String>,
    val invokedMethodShapes: List<String>,
    val neighboringResourceIds: List<Int>,
    val fieldAccesses: List<MonetFieldAccessEvidence>,
)
data class MonetFieldAccessEvidence(
    val descriptor: String,
    val access: MonetFieldAccess,
)
enum class MonetFieldAccess { READ, WRITE }

data class MonetGenerationRequestV2(
    val resources: Resources,
    val packageName: String,
    val sourceApkPaths: List<String>,
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
    val sdkInt: Int,
    val currentUserId: Int,
    val options: MonetGenerationOptions,
    val blurPalette: MonetBlurPalette?,
    val dexEvidenceProvider: MonetDexEvidenceProvider,
    val payloadDir: File,
    val workDir: File,
    val outputZip: File,
)

data class MonetBlurPalette(
    val lightRgb: Int,
    val nightRgb: Int,
    val lightSource: String,
    val nightSource: String,
)

fun interface MonetGenerationListenerV2 {
    fun onEvent(event: MonetGenerationEventV2)
}
sealed interface MonetGenerationEventV2 {
    data class Progress(val stage: MonetGenerationStageV2) : MonetGenerationEventV2
    data class Log(
        val level: MonetLogLevelV2,
        val message: String,
        val error: Throwable? = null,
    ) : MonetGenerationEventV2
}
enum class MonetGenerationStageV2 {
    PREPARING,
    SCANNING_RESOURCES,
    RESOLVING_RESOURCES,
    BUILDING_OVERLAYS,
    SIGNING,
    PACKAGING,
}
enum class MonetLogLevelV2 { DEBUG, INFO, WARN, ERROR }

data class MonetOverlayGenerationResult(
    val packageName: String,
    val fileName: String,
    val kept: Int,
    val added: Int,
    val rewritten: Int,
    val skipped: Int,
)
data class MonetSkippedRoleResult(val roleId: String, val reason: String)
data class MonetGenerationResultV2(
    val outputZip: File,
    val overlays: List<MonetOverlayGenerationResult>,
    val skippedRoles: List<MonetSkippedRoleResult>,
    val diagnosticsFile: File,
)
