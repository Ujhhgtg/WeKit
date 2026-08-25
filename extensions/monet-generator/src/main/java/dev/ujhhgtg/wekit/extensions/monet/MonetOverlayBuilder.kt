package dev.ujhhgtg.wekit.extensions.monet

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ValueType
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBlurPalette
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import java.io.File

internal fun loadMonetTemplate(templateApk: File): ApkModule =
    ApkModule.loadApkFile(templateApk).apply {
        // The extension publishes only code and Monet payloads, not ARSCLib's bundled framework
        // APK resources. This builder writes already-resolved framework IDs and does not need them.
        setLoadDefaultFramework(false)
    }

internal data class MonetBlurPaletteDiagnostic(
    val lightArgb: Long,
    val nightArgb: Long,
    val lightSource: String,
    val nightSource: String,
)

internal data class MonetOverlayBuildDiagnostics(
    val blurPalette: MonetBlurPaletteDiagnostic? = null,
)

internal data class MonetBuiltOverlay(
    val overlayId: String,
    val packageName: String,
    val fileName: String,
    val file: File,
    val roleIds: Set<String>,
    val skippedRoleIds: Set<String>,
    val kept: Int,
    val added: Int,
    val rewritten: Int,
    val skipped: Int,
    val diagnostics: MonetOverlayBuildDiagnostics,
)

internal class MonetOverlayBuilder private constructor(
    private val s4: S4MonetOverlayBuilder,
) {
    constructor(
        payloadDir: File,
        catalog: MonetRoleCatalog,
        resolution: MonetResolutionReport,
        targetGraph: MonetResourceGraph,
        options: MonetGenerationOptions,
        sdkInt: Int,
        blurPalette: MonetBlurPalette?,
    ) : this(
        s4 = S4MonetOverlayBuilder(
            payloadDir,
            catalog,
            resolution,
            targetGraph,
            options,
            sdkInt,
            blurPalette,
        ),
    )

    fun buildAll(outputDir: File): List<MonetBuiltOverlay> = s4.buildAll(outputDir)

    fun build(definition: MonetOverlayDefinition, outputApk: File): MonetBuiltOverlay =
        s4.build(definition, outputApk)
}

private class S4MonetOverlayBuilder(
    private val payloadDir: File,
    private val catalog: MonetRoleCatalog,
    private val resolution: MonetResolutionReport,
    private val targetGraph: MonetResourceGraph,
    private val options: MonetGenerationOptions,
    private val sdkInt: Int,
    private val blurPalette: MonetBlurPalette?,
) {
    private val rolesById = catalog.roles.associateBy(MonetRoleDefinition::id)
    private val overlaysById = catalog.overlays.associateBy(MonetOverlayDefinition::id)

    fun buildAll(outputDir: File): List<MonetBuiltOverlay> {
        val selected = selectedDefinitions()
        preflight(selected)
        require(selected.none { it.id == BLUR_TAB_ID } || blurPalette != null) {
            "BlurTab requires a generation-time Monet palette"
        }
        require(outputDir.mkdirs() || outputDir.isDirectory) {
            "Could not create Monet overlay output directory: $outputDir"
        }
        return selected.map { definition ->
            buildVerified(definition, outputDir.resolve(definition.fileName))
        }
    }

    fun build(definition: MonetOverlayDefinition, outputApk: File): MonetBuiltOverlay {
        val selected = selectedDefinitions()
        preflight(selected)
        val declared = overlaysById[definition.id]
        require(declared == definition) { "Unknown or drifted Monet overlay definition: ${definition.id}" }
        require(definition in selected) { "Monet overlay ${definition.id} is not selected by current options" }
        require(definition.id != BLUR_TAB_ID || blurPalette != null) {
            "BlurTab requires a generation-time Monet palette"
        }
        return buildVerified(definition, outputApk)
    }

    private fun selectedDefinitions(): List<MonetOverlayDefinition> {
        require(sdkInt >= SDK_31) { "Android 12 or newer is required" }
        require(overlaysById.size == catalog.overlays.size) {
            "Monet role catalog contains duplicate overlay IDs"
        }
        catalog.overlays.forEach { overlay ->
            val condition = overlay.selectionCondition
            require(condition.bubbleStyle == null || condition.bubbleStyle in BUBBLE_STYLES) {
                "Monet overlay ${overlay.id} has unknown bubble selection ${condition.bubbleStyle}"
            }
            require(condition.tabStyle == null || condition.tabStyle in TAB_STYLES) {
                "Monet overlay ${overlay.id} has unknown tab selection ${condition.tabStyle}"
            }
            if (overlay.id == BASE_API_31_ID || overlay.id == BASE_API_34_ID) {
                require(
                    condition.bubbleStyle == null &&
                        condition.multiSceneCornersEnabled == null &&
                        condition.tabStyle == null,
                ) { "Monet base overlay ${overlay.id} must be unconditional" }
            }
        }
        val baseId = if (sdkInt >= SDK_34) BASE_API_34_ID else BASE_API_31_ID
        val base = requireNotNull(overlaysById[baseId]) { "Missing Monet overlay definition: $baseId" }
        requireNotNull(overlaysById[if (sdkInt >= SDK_34) BASE_API_31_ID else BASE_API_34_ID]) {
            "Monet role catalog is missing one SDK base definition"
        }
        return buildList {
            add(base)
            catalog.overlays.forEach { overlay ->
                if (overlay.id != BASE_API_31_ID && overlay.id != BASE_API_34_ID &&
                    overlay.selectionCondition.matches(options)
                ) {
                    add(overlay)
                }
            }
        }.also { selected ->
            require(selected.map(MonetOverlayDefinition::fileName).toSet().size == selected.size) {
                "Selected Monet overlays contain duplicate output file names"
            }
        }
    }

    private fun preflight(selected: List<MonetOverlayDefinition>) {
        val skippedById = resolution.skipped.associateBy(MonetRoleDiagnostic::roleId)
        require(skippedById.size == resolution.skipped.size) {
            "Monet resolution report contains duplicate skipped roles"
        }
        require(resolution.resolved.keys.intersect(skippedById.keys).isEmpty()) {
            "Monet resolution report marks a role both resolved and skipped"
        }
        catalog.overlays.forEach { overlay ->
            overlay.templateResources.forEach { (roleId, templateKey) ->
                val role = requireNotNull(rolesById[roleId]) {
                    "Monet overlay ${overlay.id} binds missing role $roleId"
                }
                require(templateKey.type == role.type) {
                    "Monet overlay ${overlay.id} binds $roleId as ${templateKey.type}, expected ${role.type}"
                }
                val resolved = resolution.resolved[roleId]
                if (resolved == null) {
                    val skipped = requireNotNull(skippedById[roleId]) {
                        "Monet overlay ${overlay.id} has no resolution for bound role $roleId"
                    }
                    require(!role.core && skipped.failure != null) {
                        "Monet overlay ${overlay.id} cannot skip bound core role $roleId"
                    }
                    require(resolution.diagnostics[roleId] == skipped) {
                        "Monet resolution diagnostics disagree for skipped role $roleId"
                    }
                } else {
                    require(resolved.roleId == roleId) {
                        "Monet resolution map key disagrees with resolved role $roleId"
                    }
                    require(resolved.key.type == role.type) {
                        "Monet role $roleId resolved as ${resolved.key.type}, expected ${role.type}"
                    }
                    val liveNode = requireNotNull(targetGraph.node(resolved.resourceId)) {
                        "Monet role $roleId resolved to missing resource ID ${resolved.resourceId}"
                    }
                    require(liveNode.key == resolved.key) {
                        "Monet role $roleId resolution key ${resolved.key} disagrees with target graph ${liveNode.key}"
                    }
                    val diagnostic = requireNotNull(resolution.diagnostics[roleId]) {
                        "Monet resolution report has no diagnostic for resolved role $roleId"
                    }
                    require(diagnostic.roleId == roleId && diagnostic.failure == null) {
                        "Monet resolution diagnostics report a failure for resolved role $roleId"
                    }
                }
            }
        }
        selected.forEach { overlay ->
            val targetKeys = overlay.templateResources.keys.mapNotNull { roleId ->
                resolution.resolved[roleId]?.key
            }
            require(targetKeys.toSet().size == targetKeys.size) {
                "Monet overlay ${overlay.id} resolves multiple bindings to one target resource"
            }
        }
    }

    private fun buildVerified(
        definition: MonetOverlayDefinition,
        outputApk: File,
    ): MonetBuiltOverlay {
        val manifestSdk = manifestSdk()
        val unsignedApk = outputApk.resolveSibling(".${outputApk.name}.unsigned")
        val rewrite = try {
            writeUnsigned(definition, unsignedApk, manifestSdk)
                .also { verifyUnsigned(definition, unsignedApk, manifestSdk, it.bindings) }
                .also { MonetApkSigner.sign(unsignedApk, outputApk, manifestSdk.first) }
        } finally {
            unsignedApk.delete()
        }
        return MonetBuiltOverlay(
            overlayId = definition.id,
            packageName = definition.packageName,
            fileName = definition.fileName,
            file = outputApk,
            roleIds = rewrite.bindings.filterNot(BindingExpectation::skipped)
                .mapTo(linkedSetOf(), BindingExpectation::roleId),
            skippedRoleIds = rewrite.bindings.filter(BindingExpectation::skipped)
                .mapTo(linkedSetOf(), BindingExpectation::roleId),
            kept = rewrite.kept,
            added = 0,
            rewritten = rewrite.rewritten,
            skipped = rewrite.skipped,
            diagnostics = MonetOverlayBuildDiagnostics(rewrite.blurDiagnostic),
        )
    }

    private fun writeUnsigned(
        definition: MonetOverlayDefinition,
        unsignedApk: File,
        manifestSdk: Pair<Int, Int>,
    ): RewriteResult = loadMonetTemplate(payloadDir.resolve(definition.templateFile)).use { apk ->
        val manifest = requireNotNull(apk.androidManifest) {
            "Monet template ${definition.templateFile} has no manifest"
        }
        val pkg = requireNotNull(apk.tableBlock.pickOne()) {
            "Monet template ${definition.templateFile} has no resource package"
        }
        require(manifest.packageName == definition.packageName) {
            "Monet template package ${manifest.packageName} disagrees with ${definition.packageName}"
        }
        require(pkg.name == definition.packageName) {
            "Monet template table package ${pkg.name} disagrees with ${definition.packageName}"
        }
        require(manifest.overlayTargetPackage() == TARGET_PACKAGE) {
            "Monet template ${definition.id} targets ${manifest.overlayTargetPackage()}"
        }
        manifest.setMinSdkVersion(manifestSdk.first)
        manifest.setTargetSdkVersion(manifestSdk.second)

        val templateEntries = definition.templateResources.entries.associate { (roleId, key) ->
            val resource = requireNotNull(pkg.getResource(key.type, key.name)) {
                "Monet template ${definition.id} is missing $roleId -> ${key.type}/${key.name}"
            }
            require(resource.type == key.type) {
                "Monet template ${definition.id} has wrong type for $roleId"
            }
            roleId to TemplateBinding(resource, resource.resourceId)
        }
        require(templateEntries.values.map(TemplateBinding::originalId).toSet().size == templateEntries.size) {
            "Monet template ${definition.id} binds multiple roles to one resource ID"
        }
        val boundIds = templateEntries.values.mapTo(hashSetOf(), TemplateBinding::originalId)
        definition.templateResources.keys.forEach { roleId ->
            val resolved = resolution.resolved[roleId] ?: return@forEach
            val binding = templateEntries.getValue(roleId)
            val existing = pkg.getResource(resolved.key.type, resolved.key.name)
            require(
                existing == null ||
                    existing.resourceId == binding.originalId ||
                    existing.resourceId in boundIds,
            ) {
                "Monet role $roleId target ${resolved.key.type}/${resolved.key.name} " +
                    "collides with an internal template helper"
            }
        }

        var kept = 0
        var rewritten = 0
        var skipped = 0
        val expectations = mutableListOf<BindingExpectation>()
        definition.templateResources.toSortedMap().forEach { (roleId, templateKey) ->
            val binding = templateEntries.getValue(roleId)
            val resource = binding.resource
            val resolved = resolution.resolved[roleId]
            if (resolved == null) {
                clearAllConfigs(resource)
                skipped++
                expectations += BindingExpectation(
                    roleId,
                    templateKey.type,
                    templateKey.name,
                    resource.resourceId,
                    skipped = true,
                )
            } else {
                if (templateKey.name == resolved.key.name) {
                    kept++
                } else {
                    resource.setName(resolved.key.name)
                    rewritten++
                }
                require(resource.resourceId == binding.originalId) {
                    "Monet template resource ID changed while renaming $roleId"
                }
                expectations += BindingExpectation(
                    roleId,
                    resolved.key.type,
                    resolved.key.name,
                    resource.resourceId,
                    skipped = false,
                )
            }
        }

        val blurDiagnostic = if (definition.id == BLUR_TAB_ID) {
            val palette = requireNotNull(blurPalette) {
                "BlurTab requires a generation-time Monet palette"
            }
            val lightArgb = withAlpha(palette.lightRgb, LIGHT_BLUR_PERCENT)
            val nightArgb = withAlpha(palette.nightRgb, NIGHT_BLUR_PERCENT)
            rewriteBlurColors(
                requireNotNull(templateEntries[TAB_BACKGROUND_ROLE]) {
                    "BlurTab template does not bind $TAB_BACKGROUND_ROLE"
                }.resource,
                lightArgb,
                nightArgb,
            )
            MonetBlurPaletteDiagnostic(
                lightArgb = lightArgb.toLong() and 0xffffffffL,
                nightArgb = nightArgb.toLong() and 0xffffffffL,
                lightSource = palette.lightSource,
                nightSource = palette.nightSource,
            )
        } else {
            null
        }

        apk.apkSignatureBlock = null
        unsignedApk.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) {
                "Could not create Monet overlay output directory: $parent"
            }
        }
        apk.writeApk(unsignedApk)
        RewriteResult(expectations, kept, rewritten, skipped, blurDiagnostic)
    }

    private fun verifyUnsigned(
        definition: MonetOverlayDefinition,
        unsignedApk: File,
        manifestSdk: Pair<Int, Int>,
        bindings: List<BindingExpectation>,
    ) = loadMonetTemplate(unsignedApk).use { apk ->
        require(!apk.hasSignatureBlock()) { "Unsigned Monet overlay still contains a signature block" }
        val manifest = requireNotNull(apk.androidManifest)
        val pkg = requireNotNull(apk.tableBlock.pickOne())
        require(manifest.packageName == definition.packageName)
        require(pkg.name == definition.packageName)
        require(manifest.overlayTargetPackage() == TARGET_PACKAGE)
        require(manifest.minSdkVersion == manifestSdk.first)
        require(manifest.targetSdkVersion == manifestSdk.second)
        bindings.forEach { expected ->
            val resource = requireNotNull(pkg.getResource(expected.resourceId)) {
                "Written Monet overlay lost ${expected.roleId} resource ID ${expected.resourceId}"
            }
            require(resource.type == expected.type) {
                "Written Monet overlay changed ${expected.roleId} type to ${resource.type}"
            }
            if (expected.skipped) {
                require(resource.isEmpty) {
                    "Written Monet overlay retained configs for skipped role ${expected.roleId}"
                }
            } else {
                require(resource.name == expected.name) {
                    "Written Monet overlay named ${expected.roleId} ${resource.name}, expected ${expected.name}"
                }
                require(!resource.isEmpty) {
                    "Written Monet overlay lost configs for ${expected.roleId}"
                }
                require(pkg.getResource(expected.type, expected.name)?.resourceId == expected.resourceId) {
                    "Written Monet overlay cannot resolve rewritten role ${expected.roleId} by name"
                }
            }
        }
    }

    private fun manifestSdk(): Pair<Int, Int> =
        if (sdkInt >= SDK_34) SDK_34 to TARGET_SDK_34_PLUS else SDK_31 to TARGET_SDK_31_33

    private fun clearAllConfigs(resource: ResourceEntry) {
        val entries = resource.iterator(false)
        while (entries.hasNext()) {
            val entry = entries.next()
            if (!entry.isNull) entry.setNull(true)
        }
        require(resource.isEmpty) { "Could not clear configs for ${resource.type}/${resource.name}" }
    }

    private fun rewriteBlurColors(resource: ResourceEntry, lightArgb: Int, nightArgb: Int) {
        var rewritten = 0
        val entries = resource.iterator(false)
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.isNull) continue
            val isNight = entry.resConfig.qualifiers.orEmpty().split('-').contains("night")
            entry.setValueAsRaw(ValueType.COLOR_ARGB8, if (isNight) nightArgb else lightArgb)
            rewritten++
        }
        require(rewritten > 0) { "BlurTab color ${resource.name} has no configured values" }
    }

    private fun withAlpha(rgb: Int, percent: Int): Int {
        val alpha = (percent * 255 + 50) / 100
        return (alpha shl 24) or (rgb and 0x00ffffff)
    }

    private fun MonetOverlaySelectionCondition.matches(options: MonetGenerationOptions): Boolean {
        val bubbleMatches = bubbleStyle == null || bubbleStyle == options.bubbleStyle.name
        val cornersMatch = multiSceneCornersEnabled == null ||
            multiSceneCornersEnabled == options.multiSceneCornersEnabled
        val tabMatches = tabStyle == null || tabStyle == options.tabStyle.name
        return bubbleMatches && cornersMatch && tabMatches
    }

    private data class BindingExpectation(
        val roleId: String,
        val type: String,
        val name: String,
        val resourceId: Int,
        val skipped: Boolean,
    )

    private data class TemplateBinding(
        val resource: ResourceEntry,
        val originalId: Int,
    )

    private data class RewriteResult(
        val bindings: List<BindingExpectation>,
        val kept: Int,
        val rewritten: Int,
        val skipped: Int,
        val blurDiagnostic: MonetBlurPaletteDiagnostic?,
    )

    private companion object {
        const val TARGET_PACKAGE = "com.tencent.mm"
        const val BASE_API_31_ID = "base-api31"
        const val BASE_API_34_ID = "base-api34"
        const val BLUR_TAB_ID = "blur-tab"
        const val TAB_BACKGROUND_ROLE = "main.tab.background"
        const val SDK_31 = 31
        const val SDK_34 = 34
        const val TARGET_SDK_31_33 = 33
        const val TARGET_SDK_34_PLUS = 36
        const val LIGHT_BLUR_PERCENT = 69
        const val NIGHT_BLUR_PERCENT = 78
        val BUBBLE_STYLES = setOf("MODERN", "CLASSIC", "PRO")
        val TAB_STYLES = setOf("SOLID", "BLUR")
    }
}

private fun File.resolveSibling(name: String): File = File(parentFile, name)

private fun AndroidManifestBlock.overlayTargetPackage(): String? =
    manifestElement
        ?.getElement("overlay")
        ?.searchAttributeByName("targetPackage")
        ?.valueAsString
