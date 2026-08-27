package dev.ujhhgtg.wekit.extensions.monet

import android.annotation.SuppressLint
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEvent
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListener
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStage
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGeneratorApi
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import java.io.File

class MonetGeneratorEntrypoint : MonetGeneratorApi {
    override fun generate(request: MonetGenerationRequest, listener: MonetGenerationListener): MonetGenerationResult {
        fun progress(stage: MonetGenerationStage, detail: String, completed: Int? = null, total: Int? = null) {
            listener.onEvent(MonetGenerationEvent.Progress(stage, detail, completed, total))
        }

        progress(MonetGenerationStage.LOADING_APKS, "读取微信资源 APK", 0, request.sourceApkPaths.size)
        val graph = MonetApkResourceGraphLoader.load(
            request.sourceApkPaths.map(::File),
            request.packageName,
        ) { detail, _, _ ->
            progress(MonetGenerationStage.BUILDING_RESOURCE_GRAPH, detail)
        }
        progress(MonetGenerationStage.BUILDING_RESOURCE_GRAPH, "ARSC/XML 引用图构建完成")
        progress(MonetGenerationStage.RESOLVING_ROLES, "解析 ${MonetStructureMatcher.roleIds.size} 个语义角色", 0, MonetStructureMatcher.roleIds.size)
        val resolved = MonetStructureMatcher.resolveAll(graph, request.dexEvidenceProvider) { completed, total, role ->
            progress(MonetGenerationStage.RESOLVING_ROLES, "解析 $role", completed, total)
        }
        val colors = MONET_RULES.filter { it.type == "color" && it.id != "main.tab.background" }.mapNotNull { rule ->
            val node = resolved[rule.id] ?: return@mapNotNull null
            val target = paletteFor(rule.id, request)
            MonetOverlayApkWriter.ColorTarget(node.key.name, target.first, target.second)
        }
        val minSdk = if (request.sdkInt >= 34) 34 else 31
        val targetSdk = if (request.sdkInt >= 34) 36 else 33
        val palette = overlayPalette(request)
        val splashIconId = requireNotNull(graph.node(MonetResourceKey("drawable", "icon"))) {
            "drawable/icon"
        }.id
        val overlays = mutableListOf<MonetModulePackager.Overlay>()
        val overlayTotal = 3 +
            (if (request.options.bubbleStyle == MonetBubbleStyle.MODERN) 0 else 1) +
            if (request.options.multiSceneCorners) 1 else 0
        var overlayIndex = 0
        fun build(
            fileName: String,
            packageName: String,
            priority: Int,
            overlayColors: List<MonetOverlayApkWriter.ColorTarget> = emptyList(),
            drawables: List<MonetOverlayApkWriter.DrawableTarget> = emptyList(),
            literalColors: List<MonetOverlayApkWriter.LiteralColorTarget> = emptyList(),
            strings: List<MonetOverlayApkWriter.StringTarget> = emptyList(),
            installInitially: Boolean = true,
        ) {
            overlayIndex++
            progress(MonetGenerationStage.BUILDING_OVERLAY, "构建 $fileName", overlayIndex - 1, overlayTotal)
            val unsigned = File(request.workDir, ".$fileName.unsigned")
            val signed = File(request.workDir, fileName)
            MonetOverlayApkWriter.createReferenced(
                unsigned,
                packageName,
                minSdk,
                targetSdk,
                request.versionName,
                request.versionCode,
                priority,
                overlayColors,
                drawables,
                literalColors,
                strings,
            )
            progress(MonetGenerationStage.SIGNING, "签名 $fileName", overlayIndex - 1, overlayTotal)
            MonetApkSigner.sign(unsigned, signed, minSdk)
            unsigned.delete()
            overlays += MonetModulePackager.Overlay(signed, packageName, installInitially)
            progress(MonetGenerationStage.SIGNING, "已完成 $fileName", overlayIndex, overlayTotal)
        }
        val baseDrawables = buildList {
            addAll(MonetCustomOverlays.baseVisuals(resolved, palette, splashIconId))
            addAll(MonetCustomOverlays.modernBubbles(resolved, palette))
            if (request.sdkInt >= 33) addAll(MonetCustomOverlays.themedIcon(resolved, palette))
        }
        build(
            "MonetWeChat.apk",
            "monet.com.tencent.mm",
            1,
            colors,
            baseDrawables,
        )
        when (request.options.bubbleStyle) {
            MonetBubbleStyle.MODERN -> Unit
            MonetBubbleStyle.CLASSIC -> build(
                "MonetWeChatClassicBubble.apk",
                "monet.classicbubble.com.tencent.mm",
                10,
                drawables = MonetCustomOverlays.classicBubbles(resolved, palette),
            )
            MonetBubbleStyle.PRO -> build(
                "MonetWeChatBubblePro.apk",
                "monet.bubblepro.com.tencent.mm",
                20,
                drawables = MonetCustomOverlays.proBubbles(resolved, palette),
            )
        }
        if (request.options.multiSceneCorners) {
            build(
                "MonetWeChatMultiSceneCorners.apk",
                "monet.multiscenecorners.com.tencent.mm",
                30,
                drawables = MonetCustomOverlays.corners(resolved, palette),
            )
        }
        val tabName = requireNotNull(resolved["main.tab.background"]).key.name
        build(
            "MonetWeChatSolidTab.apk",
            "monet.solidtab.com.tencent.mm",
            10,
            overlayColors = listOf(
                MonetOverlayApkWriter.ColorTarget(
                    tabName,
                    MonetOverlayApkWriter.ColorValue.Reference(palette.surfaceContainerLight),
                    MonetOverlayApkWriter.ColorValue.Reference(palette.surfaceContainerDark),
                ),
            ),
            installInitially = request.options.tabStyle == MonetTabStyle.SOLID,
        )
        build(
            "MonetWeChatBlurTab.apk",
            "monet.blurtab.com.tencent.mm",
            10,
            literalColors = listOf(
                MonetOverlayApkWriter.LiteralColorTarget(
                    tabName,
                    request.options.blurLightArgb ?: request.resources.getColor(palette.surfaceContainerLight, null).withAlpha(0xb0),
                    request.options.blurNightArgb ?: request.resources.getColor(palette.surfaceContainerDark, null).withAlpha(0xc7),
                ),
            ),
            installInitially = request.options.tabStyle == MonetTabStyle.BLUR,
        )
        progress(MonetGenerationStage.PACKAGING, "打包 ${overlays.size} 个 Overlay")
        MonetModulePackager.pack(
            overlays,
            request.options,
            request.versionName,
            request.versionCode,
            request.sdkInt,
            request.outputZip,
        )
        progress(MonetGenerationStage.PACKAGING, "Root 模块打包完成")
        return MonetGenerationResult(request.outputZip, colors.size, 0, overlays.size)
    }

    private fun overlayPalette(request: MonetGenerationRequest) = MonetCustomOverlays.Palette(
        surfaceLight = frameworkColor(request, "system_surface_light"),
        surfaceDark = frameworkColor(request, "system_surface_dark"),
        surfaceContainerLight = frameworkColor(
            request, "system_surface_container_light", "system_neutral2_50", "system_surface_light",
        ),
        surfaceContainerDark = frameworkColor(
            request, "system_surface_container_dark", "system_neutral2_800", "system_surface_dark",
        ),
        surfaceContainerHighLight = frameworkColor(
            request, "system_surface_container_high_light", "system_surface_container_light", "system_surface_light",
        ),
        surfaceContainerHighDark = frameworkColor(
            request, "system_surface_container_high_dark", "system_surface_container_dark", "system_surface_dark",
        ),
        primaryLight = frameworkColor(request, "system_primary_light", "system_accent1_500"),
        primaryDark = frameworkColor(request, "system_primary_dark", "system_accent1_200"),
        primaryContainerLight = frameworkColor(request, "system_primary_container_light", "system_accent1_100"),
        primaryContainerDark = frameworkColor(request, "system_primary_container_dark", "system_accent1_800"),
        accent1_300 = frameworkColor(request, "system_accent1_300"),
        accent1_400 = frameworkColor(request, "system_accent1_400"),
        accent1_500 = frameworkColor(request, "system_accent1_500"),
        accent1_700 = frameworkColor(request, "system_accent1_700"),
        accent2_100 = frameworkColor(request, "system_accent2_100"),
        neutral2_700 = frameworkColor(request, "system_neutral2_700", "system_surface_dark"),
    )

    @SuppressLint("DiscouragedApi")
    private fun frameworkColor(request: MonetGenerationRequest, vararg names: String): Int =
        names.firstNotNullOfOrNull { name ->
            request.resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
        } ?: error("framework Monet color unavailable: ${names.joinToString()}")

    private fun Int.withAlpha(alpha: Int): Int = this and 0x00ffffff or (alpha shl 24)

    private fun paletteFor(
        id: String,
        request: MonetGenerationRequest,
    ): Pair<MonetOverlayApkWriter.ColorValue?, MonetOverlayApkWriter.ColorValue?> {
        val semantic = id.removePrefix("theme.color.").substringBefore(".slot-")
        val parts = semantic.split("--", limit = 2)
        fun resolve(token: String): MonetOverlayApkWriter.ColorValue? {
            if (token == "unknown") return null
            if (token.length == 8 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return MonetOverlayApkWriter.ColorValue.Literal(token.toUInt(16).toInt())
            }
            require(token.startsWith("system-")) { "unsupported S4 color token: $token" }
            val fallbacks = when (val normalized = token.replace('-', '_')) {
                "system_surface_container_light" -> listOf(normalized, "system_neutral2_50", "system_surface_light")
                "system_surface_container_dark" -> listOf(normalized, "system_neutral2_800", "system_surface_dark")
                else -> listOf(normalized)
            }
            return MonetOverlayApkWriter.ColorValue.Reference(frameworkColor(request, *fallbacks.toTypedArray()))
        }
        return resolve(parts.first()) to resolve(parts.getOrElse(1) { parts.first() })
    }
}
