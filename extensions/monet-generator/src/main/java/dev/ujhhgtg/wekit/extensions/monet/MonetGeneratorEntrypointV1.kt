package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEvent
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListener
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStage
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApiV1
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import java.io.File

class MonetGeneratorEntrypointV1 : MonetGeneratorApiV1 {
    override fun generate(request: MonetGenerationRequest, listener: MonetGenerationListener): MonetGenerationResult {
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.PREPARING))
        val graph = MonetApkResourceGraphLoader.load(listOf(File(request.sourceApkPath)), request.packageName)
        val resolved = MonetStructureMatcher.resolveAll(graph, request.dexEvidenceProvider)
        val colors = MONET_RULES.filter { it.type == "color" }.mapNotNull { rule ->
            val node = resolved[rule.id] ?: return@mapNotNull null
            val target = paletteFor(rule.id, request)
            MonetOverlayApkWriter.ColorTarget(node.key.name, target.first, target.second)
        }
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.BUILDING_OVERLAY))
        val minSdk = if (request.sdkInt >= 34) 34 else 31
        val targetSdk = if (request.sdkInt >= 34) 36 else 33
        val palette = overlayPalette(request)
        val overlays = mutableListOf<MonetModulePackager.Overlay>()
        fun build(
            fileName: String,
            packageName: String,
            overlayColors: List<MonetOverlayApkWriter.ColorTarget> = emptyList(),
            drawables: List<MonetOverlayApkWriter.DrawableTarget> = emptyList(),
            literalColors: List<MonetOverlayApkWriter.LiteralColorTarget> = emptyList(),
        ) {
            val unsigned = File(request.workDir, ".$fileName.unsigned")
            val signed = File(request.workDir, fileName)
            MonetOverlayApkWriter.createReferenced(
                unsigned,
                packageName,
                minSdk,
                targetSdk,
                overlayColors,
                drawables,
                literalColors,
            )
            MonetApkSigner.sign(unsigned, signed, minSdk)
            unsigned.delete()
            overlays += MonetModulePackager.Overlay(signed, packageName)
        }
        build(
            "MonetWeChat.apk",
            "monet.com.tencent.mm",
            colors,
            MonetS4Overlays.baseVisuals(resolved, palette),
        )
        MonetS4Overlays.bubbles(resolved, request.options.bubbleStyle, palette).takeIf { it.isNotEmpty() }?.let {
            val style = if (request.options.bubbleStyle == MonetBubbleStyle.PRO) "bubblepro" else "modernbubble"
            build("MonetWeChatBubble.apk", "monet.$style.com.tencent.mm", drawables = it)
        }
        if (request.options.multiSceneCorners) {
            build(
                "MonetWeChatMultiSceneCorners.apk",
                "monet.multiscenecorners.com.tencent.mm",
                drawables = MonetS4Overlays.corners(resolved, palette),
            )
        }
        if (request.sdkInt >= 33) {
            build(
                "MonetWeChatThemedIcon.apk",
                "monet.themedicon.com.tencent.mm",
                drawables = MonetS4Overlays.themedIcon(resolved, palette),
            )
        }
        val tabName = requireNotNull(resolved["main.tab.background"]).key.name
        if (request.options.tabStyle == MonetTabStyle.BLUR) {
            build(
                "MonetWeChatTab.apk",
                "monet.blurtab.com.tencent.mm",
                literalColors = listOf(
                    MonetOverlayApkWriter.LiteralColorTarget(
                        tabName,
                        request.options.blurLightArgb ?: 0xb0f4fbf5.toInt(),
                        request.options.blurNightArgb ?: 0xb0191919.toInt(),
                    ),
                ),
            )
        } else {
            build(
                "MonetWeChatTab.apk",
                "monet.solidtab.com.tencent.mm",
                overlayColors = listOf(
                    MonetOverlayApkWriter.ColorTarget(tabName, palette.surfaceLight, palette.surfaceNight),
                ),
            )
        }
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.SIGNING))
        listener.onEvent(MonetGenerationEvent.Progress(MonetGenerationStage.PACKAGING))
        MonetModulePackager.pack(overlays, request.options, request.outputZip)
        return MonetGenerationResult(request.outputZip, colors.size, 0, overlays.size)
    }

    private fun overlayPalette(request: MonetGenerationRequest) = MonetS4Overlays.Palette(
        incomingLight = frameworkColor(request, "system_surface_container_light", "system_neutral2_50", "system_surface_light"),
        incomingNight = frameworkColor(request, "system_surface_container_dark", "system_neutral2_800", "system_surface_dark"),
        outgoingLight = frameworkColor(request, "system_accent1_100", "system_accent1_200"),
        outgoingNight = frameworkColor(request, "system_accent1_800", "system_accent1_700"),
        surfaceLight = frameworkColor(request, "system_surface_container_light", "system_neutral2_50", "system_surface_light"),
        surfaceNight = frameworkColor(request, "system_surface_container_dark", "system_neutral2_800", "system_surface_dark"),
        primaryLight = frameworkColor(request, "system_accent1_100", "system_accent1_200"),
        primaryNight = frameworkColor(request, "system_accent1_800", "system_accent1_700"),
    )

    private fun frameworkColor(request: MonetGenerationRequest, vararg names: String): Int =
        names.firstNotNullOfOrNull { name ->
            request.resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
        } ?: error("framework Monet color unavailable: ${names.joinToString()}")

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
            return frameworkColor(request, *fallbacks.toTypedArray())
        }
        return resolve(parts.first(), false) to resolve(parts.getOrElse(1) { parts.first() }, true)
    }
}
