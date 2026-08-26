package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEvent
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListener
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStage
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApiV1
import java.io.File

class MonetGeneratorEntrypointV1 : MonetGeneratorApiV1 {
    override fun generate(request: MonetGenerationRequest, listener: MonetGenerationListener): MonetGenerationResult {
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.PREPARING))
        val graph = MonetApkResourceGraphLoader.load(listOf(File(request.sourceApkPath)), request.packageName)
        val resolved = MonetStructureMatcher.resolveAll(graph)
        val colors = MONET_RULES.filter { it.type == "color" }.mapNotNull { rule ->
            val node = resolved[rule.id] ?: return@mapNotNull null
            val target = paletteFor(rule.id, request)
            MonetOverlayApkWriter.ColorTarget(node.key.name, target.first, target.second)
        }
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.BUILDING_OVERLAY))
        val unsigned = File(request.workDir, "MonetWeChat-unsigned.apk")
        val signed = File(request.workDir, "MonetWeChat.apk")
        val minSdk = if (request.sdkInt >= 34) 34 else 31
        val targetSdk = if (request.sdkInt >= 34) 36 else 33
        MonetOverlayApkWriter.createReferenced(unsigned, "monet.com.tencent.mm", minSdk, targetSdk, colors)
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.SIGNING))
        MonetApkSigner.sign(unsigned, signed, minSdk)
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.PACKAGING))
        MonetModulePackager.pack(signed, request.outputZip)
        return MonetGenerationResult(request.outputZip, colors.size, 0, 0)
    }

    private fun paletteFor(id: String, request: MonetGenerationRequest): Pair<Int, Int?> {
        val semantic = id.removePrefix("theme.color.").substringBefore(".slot-")
        val parts = semantic.split("--", limit = 2)
        fun resolve(token: String, night: Boolean): Int {
            val normalized = when {
                token.startsWith("system-") -> token.replace('-', '_')
                token == "10000000" || token.startsWith("unknown") -> if (night) "system_surface_dark" else "system_surface_light"
                token == "10ffffff" || token == "e6ffffff" -> if (night) "system_surface_dark" else "system_surface_light"
                else -> if (night) "system_surface_dark" else "system_surface_light"
            }
            val fallbacks = when (normalized) {
                "system_surface_container_light" -> listOf(normalized, "system_neutral2_50", "system_surface_light")
                "system_surface_container_dark" -> listOf(normalized, "system_neutral2_800", "system_surface_dark")
                else -> listOf(normalized)
            }
            return fallbacks.firstNotNullOfOrNull { name ->
                request.resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
            } ?: error("framework Monet color unavailable: $normalized")
        }
        return resolve(parts.first(), false) to resolve(parts.getOrElse(1) { parts.first() }, true)
    }
}
