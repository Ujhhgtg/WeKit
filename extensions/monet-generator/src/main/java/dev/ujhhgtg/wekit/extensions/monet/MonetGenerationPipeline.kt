package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEventV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListenerV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequestV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResultV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStageV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetOverlayGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetSkippedRoleResult
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal class MonetGenerationException(
    val stage: MonetGenerationStageV2,
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal interface MonetGenerationPipelineStages {
    fun loadBaseResourceDigest(baseApk: File, targetPackage: String): String

    fun loadGraph(apkPaths: List<File>, targetPackage: String): MonetResourceGraph

    fun loadRoleCatalog(payloadDir: File): MonetRoleCatalog

    fun loadProfileCatalog(payloadDir: File): MonetProfileCatalog

    fun resolve(
        graph: MonetResourceGraph,
        catalog: MonetRoleCatalog,
        profiles: List<MonetProfile>,
        sdkInt: Int,
        provider: MonetDexEvidenceProvider,
    ): MonetResolutionReport

    fun buildOverlays(
        request: MonetGenerationRequestV2,
        catalog: MonetRoleCatalog,
        resolution: MonetResolutionReport,
        graph: MonetResourceGraph,
        outputDir: File,
    ): List<MonetBuiltOverlay>

    fun verifySignedOverlay(overlay: MonetBuiltOverlay)

    fun packageModule(
        request: MonetGenerationRequestV2,
        overlays: List<MonetBuiltOverlay>,
        diagnosticsFile: File,
        outputZip: File,
    )
}

internal class MonetGenerationPipeline(
    private val stages: MonetGenerationPipelineStages = ProductionMonetGenerationPipelineStages,
) {
    fun generate(
        request: MonetGenerationRequestV2,
        listener: MonetGenerationListenerV2,
    ): MonetGenerationResultV2 {
        var currentStage = MonetGenerationStageV2.PREPARING
        var paths: RunPaths? = null
        var ownedRunDir: File? = null
        var temporaryOutputOwned = false
        try {
            emitProgress(listener, currentStage)
            paths = validateRequest(request)

            currentStage = MonetGenerationStageV2.SCANNING_RESOURCES
            emitProgress(listener, currentStage)
            val resourceApks = selectResourceBearingApks(request.sourceApkPaths)
            val resourceDigest = stages.loadBaseResourceDigest(resourceApks.first(), request.packageName)
            val graph = stages.loadGraph(resourceApks, request.packageName)
            createOwnedRunDirectory(paths.runDir)
            ownedRunDir = paths.runDir
            deleteTemporaryFile(paths.temporaryOutput)
            temporaryOutputOwned = true
            val catalog = stages.loadRoleCatalog(request.payloadDir)
            val matchingProfiles = stages.loadProfileCatalog(request.payloadDir)
                .verifiedProfiles
                .filter { profile -> profile.resourceDigest == resourceDigest }
                .map(MonetVerifiedProfile::toResolutionProfile)

            currentStage = MonetGenerationStageV2.RESOLVING_RESOURCES
            emitProgress(listener, currentStage)
            val resolution = try {
                stages.resolve(
                    graph = graph,
                    catalog = catalog,
                    profiles = matchingProfiles,
                    sdkInt = request.sdkInt,
                    provider = request.dexEvidenceProvider,
                )
            } catch (error: MonetResolutionException) {
                publishDiagnostics(error.report, paths.diagnosticsFile, paths.diagnosticsTemporary)
                throw error
            }
            publishDiagnostics(resolution, paths.diagnosticsFile, paths.diagnosticsTemporary)

            currentStage = MonetGenerationStageV2.BUILDING_OVERLAYS
            emitProgress(listener, currentStage)
            val overlays = stages.buildOverlays(
                request = request,
                catalog = catalog,
                resolution = resolution,
                graph = graph,
                outputDir = paths.runDir.resolve(OVERLAY_DIR_NAME),
            )
            require(overlays.isNotEmpty()) { "Monet generation selected no overlays" }
            require(overlays.map(MonetBuiltOverlay::fileName).toSet().size == overlays.size) {
                "Monet generation produced duplicate overlay file names"
            }

            currentStage = MonetGenerationStageV2.SIGNING
            emitProgress(listener, currentStage)
            overlays.forEach(stages::verifySignedOverlay)
            val verifiedOverlayDigests = overlays.associate { overlay ->
                overlay.fileName to sha256(overlay.file.inputStream().buffered())
            }

            currentStage = MonetGenerationStageV2.PACKAGING
            emitProgress(listener, currentStage)
            stages.packageModule(request, overlays, paths.diagnosticsFile, paths.temporaryOutput)
            verifyPackagedModule(
                paths.temporaryOutput,
                overlays,
                verifiedOverlayDigests,
                paths.diagnosticsFile,
                request.sdkInt,
            )
            Files.move(
                paths.temporaryOutput.toPath(),
                paths.outputZip.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )

            return MonetGenerationResultV2(
                outputZip = request.outputZip,
                overlays = overlays.map { overlay ->
                    MonetOverlayGenerationResult(
                        packageName = overlay.packageName,
                        fileName = overlay.fileName,
                        kept = overlay.kept,
                        added = overlay.added,
                        rewritten = overlay.rewritten,
                        skipped = overlay.skipped,
                    )
                },
                skippedRoles = resolution.skipped.map { diagnostic ->
                    MonetSkippedRoleResult(
                        roleId = diagnostic.roleId,
                        reason = diagnostic.message ?: diagnostic.failure?.name.orEmpty(),
                    )
                },
                diagnosticsFile = paths.diagnosticsFile,
            )
        } catch (error: MonetGenerationException) {
            throw error
        } catch (error: Exception) {
            throw MonetGenerationException(
                stage = currentStage,
                message = "Monet generation failed during $currentStage: " +
                    (error.message ?: error::class.java.name),
                cause = error,
            )
        } finally {
            if (temporaryOutputOwned) paths?.temporaryOutput?.delete()
            ownedRunDir?.deleteRecursively()
        }
    }

    private fun validateRequest(request: MonetGenerationRequestV2): RunPaths {
        require(request.sdkInt >= 31) { "Android 12 or newer is required" }
        require(request.packageName == TARGET_PACKAGE) {
            "Monet generation target package must be $TARGET_PACKAGE"
        }
        require(request.sourceApkPaths.isNotEmpty()) { "Monet generation requires a base APK path" }
        require(request.currentUserId >= 0) { "Android user ID must be non-negative" }
        val outputParent = requireNotNull(request.outputZip.parentFile) {
            "Monet output ZIP must have a parent directory"
        }.canonicalFile
        val outputZip = request.outputZip.canonicalFile
        val payloadDir = request.payloadDir.canonicalFile
        val workRoot = request.workDir.canonicalFile
        require(!workRoot.exists() || workRoot.isDirectory) {
            "Monet work root is not a directory: $workRoot"
        }
        val runDir = File(workRoot, ".monet-run-${UUID.randomUUID()}").canonicalFile
        require(runDir.parentFile == workRoot) { "Monet owned run directory escapes its work root" }
        val runPaths = RunPaths(
            payloadDir = payloadDir,
            workRoot = workRoot,
            runDir = runDir,
            outputZip = outputZip,
            temporaryOutput = File(outputParent, outputZip.name + ".tmp").canonicalFile,
            diagnosticsFile = File(outputParent, DIAGNOSTICS_NAME).canonicalFile,
            diagnosticsTemporary = File(outputParent, "$DIAGNOSTICS_NAME.tmp").canonicalFile,
        )
        validateDisjointPaths(runPaths)
        REQUIRED_PAYLOADS.forEach { name ->
            require(payloadDir.resolve(name).isFile) { "Missing Monet payload: $name" }
        }
        return runPaths
    }

    private fun validateDisjointPaths(paths: RunPaths) {
        val protected = listOf(
            "payload" to paths.payloadDir,
            "work root" to paths.workRoot,
            "output" to paths.outputZip,
            "temporary output" to paths.temporaryOutput,
            "diagnostics" to paths.diagnosticsFile,
            "diagnostics temporary" to paths.diagnosticsTemporary,
        )
        protected.forEachIndexed { index, (leftName, left) ->
            protected.drop(index + 1).forEach { (rightName, right) ->
                require(!pathsOverlap(left, right)) {
                    "Monet $leftName path overlaps $rightName path: $left and $right"
                }
            }
            if (left != paths.workRoot) {
                require(!pathsOverlap(left, paths.runDir)) {
                    "Monet $leftName path overlaps owned run directory: $left and ${paths.runDir}"
                }
            }
        }
    }

    private fun pathsOverlap(first: File, second: File): Boolean =
        first.toPath().startsWith(second.toPath()) || second.toPath().startsWith(first.toPath())

    private fun createOwnedRunDirectory(runDir: File) {
        val workRoot = requireNotNull(runDir.parentFile)
        require(workRoot.mkdirs() || workRoot.isDirectory) {
            "Could not create Monet work root: $workRoot"
        }
        require(!runDir.exists() && runDir.mkdir()) {
            "Could not create owned Monet run directory: $runDir"
        }
    }

    private fun deleteTemporaryFile(file: File) {
        if (file.exists()) require(file.delete()) { "Could not clear stale Monet temporary output: $file" }
    }

    private fun selectResourceBearingApks(sourceApkPaths: List<String>): List<File> {
        val apks = sourceApkPaths.map { path ->
            require(path.isNotBlank()) { "Monet source APK path must not be blank" }
            File(path).canonicalFile.also { apk ->
                require(apk.isFile) { "Monet source APK is missing: $apk" }
            }
        }
        require(apks.toSet().size == apks.size) { "Monet source APK paths contain duplicates" }
        val base = apks.first()
        require(base.hasResourceTable()) { "Monet base APK has no resources.arsc: $base" }
        return buildList {
            add(base)
            addAll(
                apks.drop(1)
                    .filter { apk -> apk.hasResourceTable() }
                    .sortedBy(File::getAbsolutePath),
            )
        }
    }

    private fun File.hasResourceTable(): Boolean = ZipFile(this).use { apk ->
        apk.getEntry(RESOURCE_TABLE_PATH)?.isDirectory == false
    }

    private fun publishDiagnostics(
        report: MonetResolutionReport,
        output: File,
        temporary: File,
    ) {
        try {
            output.parentFile?.let { parent ->
                require(parent.mkdirs() || parent.isDirectory) {
                    "Could not create Monet diagnostics directory: $parent"
                }
            }
            temporary.writeText(MonetResolutionDiagnostics.encode(report))
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun verifyPackagedModule(
        output: File,
        overlays: List<MonetBuiltOverlay>,
        verifiedOverlayDigests: Map<String, ByteArray>,
        diagnosticsFile: File,
        sdkInt: Int,
    ) {
        require(output.isFile) { "Monet module archive was not produced" }
        ZipFile(output).use { module ->
            val entries = module.entries().asSequence().toList()
            require(entries.none { it.isDirectory }) { "Monet module archive contains a directory entry" }
            require(entries.map { it.name }.toSet().size == entries.size) {
                "Monet module archive contains duplicate entries"
            }
            val expectedEntries = REQUIRED_MODULE_ENTRIES + overlays.map { overlay ->
                if (sdkInt >= 34) {
                    val stem = overlay.fileName.removeSuffix(APK_SUFFIX)
                    require(stem != overlay.fileName && stem.isNotEmpty()) {
                        "Invalid Monet overlay APK name: ${overlay.fileName}"
                    }
                    "system/priv-app/$stem/${overlay.fileName}"
                } else {
                    "system/product/overlay/${overlay.fileName}"
                }
            }
            require(entries.map { it.name }.toSet() == expectedEntries.toSet()) {
                "Monet module archive contents do not match the selected overlays"
            }
            overlays.forEach { overlay ->
                val path = expectedEntries.single { it.endsWith("/${overlay.fileName}") }
                val packagedDigest = sha256(module.getInputStream(module.getEntry(path)))
                require(packagedDigest.contentEquals(verifiedOverlayDigests.getValue(overlay.fileName))) {
                    "Packaged Monet overlay bytes do not match verified source: ${overlay.fileName}"
                }
            }
            val packagedDiagnostics = module.getInputStream(module.getEntry(DIAGNOSTICS_NAME)).use { it.readBytes() }
            require(packagedDiagnostics.contentEquals(diagnosticsFile.readBytes())) {
                "Packaged Monet diagnostics do not match the latest complete report"
            }
        }
    }

    private fun emitProgress(listener: MonetGenerationListenerV2, stage: MonetGenerationStageV2) {
        listener.onEvent(MonetGenerationEventV2.Progress(stage))
    }

    private fun sha256(input: InputStream): ByteArray = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest()
    }

    private data class RunPaths(
        val payloadDir: File,
        val workRoot: File,
        val runDir: File,
        val outputZip: File,
        val temporaryOutput: File,
        val diagnosticsFile: File,
        val diagnosticsTemporary: File,
    )

    private companion object {
        const val TARGET_PACKAGE = "com.tencent.mm"
        const val RESOURCE_TABLE_PATH = "resources.arsc"
        const val OVERLAY_DIR_NAME = "overlays"
        const val DIAGNOSTICS_NAME = "monet-resolution.json"
        const val APK_SUFFIX = ".apk"

        val REQUIRED_PAYLOADS = listOf(
            "templates/template_base_api31.apk",
            "templates/template_base_api34.apk",
            "templates/template_classic.apk",
            "templates/template_pro.apk",
            "templates/template_corners.apk",
            "templates/template_solid_tab.apk",
            "templates/template_blur_tab.apk",
            "monet_roles.json",
            "monet_profiles.json",
            "upstream.txt",
            "customize.sh",
            "common.sh",
            "service.sh",
            "boot-completed.sh",
            "update-binary",
            "updater-script",
        )
        val REQUIRED_MODULE_ENTRIES = setOf(
            "META-INF/com/google/android/update-binary",
            "META-INF/com/google/android/updater-script",
            "boot-completed.sh",
            "common.sh",
            "config.conf",
            "customize.sh",
            "module.prop",
            DIAGNOSTICS_NAME,
            "service.sh",
        )
    }
}

private object ProductionMonetGenerationPipelineStages : MonetGenerationPipelineStages {
    override fun loadBaseResourceDigest(baseApk: File, targetPackage: String): String =
        MonetApkResourceGraphLoader.load(listOf(baseApk), targetPackage).resourceDigest()

    override fun loadGraph(apkPaths: List<File>, targetPackage: String): MonetResourceGraph =
        MonetApkResourceGraphLoader.load(apkPaths, targetPackage)

    override fun loadRoleCatalog(payloadDir: File): MonetRoleCatalog = MonetRoleCatalog.load(payloadDir)

    override fun loadProfileCatalog(payloadDir: File): MonetProfileCatalog = MonetProfileCatalog.load(payloadDir)

    override fun resolve(
        graph: MonetResourceGraph,
        catalog: MonetRoleCatalog,
        profiles: List<MonetProfile>,
        sdkInt: Int,
        provider: MonetDexEvidenceProvider,
    ): MonetResolutionReport = MonetResourceResolver.resolve(graph, catalog, profiles, sdkInt, provider)

    override fun buildOverlays(
        request: MonetGenerationRequestV2,
        catalog: MonetRoleCatalog,
        resolution: MonetResolutionReport,
        graph: MonetResourceGraph,
        outputDir: File,
    ): List<MonetBuiltOverlay> = MonetOverlayBuilder(
        payloadDir = request.payloadDir,
        catalog = catalog,
        resolution = resolution,
        targetGraph = graph,
        options = request.options,
        sdkInt = request.sdkInt,
        blurPalette = request.blurPalette,
    ).buildAll(outputDir)

    override fun verifySignedOverlay(overlay: MonetBuiltOverlay) {
        require(overlay.file.isFile) { "Signed Monet overlay is missing: ${overlay.file}" }
        MonetApkSigner.verifySignedApk(overlay.file)
    }

    override fun packageModule(
        request: MonetGenerationRequestV2,
        overlays: List<MonetBuiltOverlay>,
        diagnosticsFile: File,
        outputZip: File,
    ) {
        MonetModulePackager(
            payloadDir = request.payloadDir,
            versionName = request.versionName,
            versionCode = request.versionCode,
            sdkInt = request.sdkInt,
        ).pack(
            signedOverlays = overlays,
            options = request.options,
            generatedUserId = request.currentUserId,
            diagnosticsFile = diagnosticsFile,
            outputZip = outputZip,
        )
    }
}
