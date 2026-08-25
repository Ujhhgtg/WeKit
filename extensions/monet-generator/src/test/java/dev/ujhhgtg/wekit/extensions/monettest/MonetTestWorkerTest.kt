package dev.ujhhgtg.wekit.extensions.monettest

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import dev.ujhhgtg.wekit.extensions.monet.MonetApkResourceGraphLoader
import dev.ujhhgtg.wekit.extensions.monet.MonetApkSigner
import dev.ujhhgtg.wekit.extensions.monet.MonetBuiltOverlay
import dev.ujhhgtg.wekit.extensions.monet.MonetCandidateStageDiagnostic
import dev.ujhhgtg.wekit.extensions.monet.MonetProfile
import dev.ujhhgtg.wekit.extensions.monet.MonetProfileCatalog
import dev.ujhhgtg.wekit.extensions.monet.MonetResolutionException
import dev.ujhhgtg.wekit.extensions.monet.MonetResolutionFailure
import dev.ujhhgtg.wekit.extensions.monet.MonetResolutionReport
import dev.ujhhgtg.wekit.extensions.monet.MonetResolutionStage
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceGraph
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceKey
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceResolver
import dev.ujhhgtg.wekit.extensions.monet.MonetRoleCatalog
import dev.ujhhgtg.wekit.extensions.monet.MonetRoleDefinition
import dev.ujhhgtg.wekit.extensions.monet.MonetRoleDiagnostic
import dev.ujhhgtg.wekit.extensions.monet.MonetStructuralProfile
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayBuilder
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBlurPalette
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import dev.ujhhgtg.wekit.extensions.monet.evidence.DexKitMonetEvidenceCollector
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test
import org.luckypray.dexkit.DexKitBridge

class MonetTestWorkerTest {

    @Test
    fun runMonetSampleWorker() {
        val config = MonetTestWorkerConfig.fromSystemProperties(System.getProperties())
        val started = Instant.now()
        val startedNanos = System.nanoTime()
        val environment = MonetTestEnvironment(
            dexKitVersion = config.dexKitVersion,
            dexKitRevision = config.dexKitRevision,
            architecture = System.getProperty("os.arch").orEmpty(),
            jvmVersion = System.getProperty("java.version").orEmpty(),
        )
        val report = try {
            runMonetSample(config, environment, started, startedNanos)
        } catch (error: Throwable) {
            infrastructureReport(config, environment, started, startedNanos, error)
        }
        report.writeAtomically(config.report)
    }
}

private fun runMonetSample(
    config: MonetTestWorkerConfig,
    environment: MonetTestEnvironment,
    started: Instant,
    startedNanos: Long,
): MonetTestSampleReport {
    val input = config.inputPath.toFile()
    val payload = findPayloadDirectory()
    val catalog = MonetRoleCatalog.load(payload)
    val profiles = MonetProfileCatalog.load(payload)
    val reportParent = requireNotNull(config.report.parent).toFile()
    require(reportParent.mkdirs() || reportParent.isDirectory) {
        "could not create Monet report directory: $reportParent"
    }
    val workerRoot = Files.createTempDirectory(reportParent.toPath(), ".monet-worker-").toFile()
    return try {
        when (config.inputKind) {
            MonetTestInputKind.APK,
            MonetTestInputKind.APKS,
            -> runApkSample(
                config,
                environment,
                started,
                startedNanos,
                input,
                payload,
                catalog,
                profiles,
                workerRoot,
            )
            MonetTestInputKind.DECODED_RES -> runDecodedSample(
                config,
                environment,
                started,
                startedNanos,
                input,
                payload,
                catalog,
                profiles,
                workerRoot,
            )
        }
    } finally {
        check(workerRoot.deleteRecursively()) { "failed to delete Monet worker directory: $workerRoot" }
    }
}

private fun runApkSample(
    config: MonetTestWorkerConfig,
    environment: MonetTestEnvironment,
    started: Instant,
    startedNanos: Long,
    input: File,
    payload: File,
    catalog: MonetRoleCatalog,
    profileCatalog: MonetProfileCatalog,
    workerRoot: File,
): MonetTestSampleReport = prepareApkInput(
    config.inputKind,
    input,
    workerRoot.resolve("apks"),
).use { prepared ->
    val baseGraph = MonetApkResourceGraphLoader.load(listOf(prepared.baseApk), TARGET_PACKAGE)
    val baseDigest = baseGraph.resourceDigest()
    val graph = MonetApkResourceGraphLoader.load(
        prepared.resourceApks.map(MonetPreparedResourceApk::file),
        TARGET_PACKAGE,
    )
    val profiles = profileCatalog.verifiedProfiles
        .filter { profile -> profile.resourceDigest == baseDigest }
        .map { it.toResolutionProfile() }
    val resourceSummary = MonetTestResourceSummary(
        baseResourceDigest = MonetTestDigestEvidence(MonetTestEvidenceStatus.AVAILABLE, baseDigest),
        fullResourceDigest = graph.resourceDigest(),
        exactProfiles = profiles.map { profile -> profile.toReport() },
        graphKind = "COMPILED_APK_GRAPH",
        binaryXmlShapesComparable = true,
    )
    require(Files.isRegularFile(config.nativeLibrary)) {
        "DexKit native library is not a regular file: ${config.nativeLibrary}"
    }
    MonetNativeLibraryLoader.load(config.nativeLibrary)
    DexKitBridge.create(prepared.dexBytes.toTypedArray()).use { bridge ->
        require(bridge.isValid) { "DexKit bridge is invalid after creation" }
        val dexCount = bridge.getDexNum()
        require(dexCount == prepared.dexBytes.size) {
            "DexKit loaded $dexCount DEX files, expected ${prepared.dexBytes.size}"
        }
        val provider = RecordingDexEvidenceProvider(bridge)
        executeResolutionAndSmoke(
            environment = environment,
            started = started,
            startedNanos = startedNanos,
            inputMetadata = MonetTestInputMetadata(
                kind = config.inputKind,
                path = input.canonicalPath,
                fileName = input.name,
                sizeBytes = input.length(),
                sha256 = sha256(input),
                metadataSource = MonetTestMetadataSource.APK_MANIFEST,
                versionCode = config.versionCode,
                versionName = config.versionName,
                isGooglePlay = config.isGooglePlay,
                channel = if (config.isGooglePlay) "google-play" else "domestic",
                nestedApkCount = prepared.nestedApkCount,
                resourceApkCount = prepared.resourceApks.size,
                dexCount = dexCount,
            ),
            resources = resourceSummary,
            graph = graph,
            catalog = catalog,
            profiles = profiles,
            provider = provider,
            payload = payload,
            workerRoot = workerRoot,
        )
    }
}

private fun runDecodedSample(
    config: MonetTestWorkerConfig,
    environment: MonetTestEnvironment,
    started: Instant,
    startedNanos: Long,
    input: File,
    payload: File,
    catalog: MonetRoleCatalog,
    profileCatalog: MonetProfileCatalog,
    workerRoot: File,
): MonetTestSampleReport {
    val decoded = MonetDecodedResourceGraphLoader.load(input)
    val snapshot = decodedResourceSnapshot(input)
    val structuralProfiles = profileCatalog.structuralOnlyProfiles.filter { profile ->
        profile.channel == "domestic" &&
            profile.versionName == config.versionName &&
            profile.sourceEvidence?.resourceSnapshotSha256 == snapshot
    }
    val provider = UnavailableDexEvidenceProvider()
    return executeResolutionAndSmoke(
        environment = environment,
        started = started,
        startedNanos = startedNanos,
        inputMetadata = MonetTestInputMetadata(
            kind = config.inputKind,
            path = input.canonicalPath,
            fileName = decodedSampleName(input),
            sha256 = snapshot,
            metadataSource = MonetTestMetadataSource.PATH_INFERRED,
            versionName = config.versionName,
            isGooglePlay = false,
            channel = "domestic",
        ),
        resources = MonetTestResourceSummary(
            baseResourceDigest = MonetTestDigestEvidence(
                status = MonetTestEvidenceStatus.UNAVAILABLE,
                reason = "decoded resources do not preserve the base APK resource-table boundary",
            ),
            fullResourceDigest = decoded.graph.resourceDigest(),
            exactProfiles = emptyList(),
            structuralProfiles = structuralProfiles.map(MonetStructuralProfile::toReport),
            graphKind = "DECODED_RESOURCE_GRAPH",
            binaryXmlShapesComparable = decoded.binaryXmlShapesComparable,
            limitations = decoded.limitations,
        ),
        graph = decoded.graph,
        catalog = catalog,
        profiles = emptyList(),
        provider = provider,
        payload = payload,
        workerRoot = workerRoot,
    )
}

private fun executeResolutionAndSmoke(
    environment: MonetTestEnvironment,
    started: Instant,
    startedNanos: Long,
    inputMetadata: MonetTestInputMetadata,
    resources: MonetTestResourceSummary,
    graph: MonetResourceGraph,
    catalog: MonetRoleCatalog,
    profiles: List<MonetProfile>,
    provider: ReportingDexEvidenceProvider,
    payload: File,
    workerRoot: File,
): MonetTestSampleReport {
    val resolution = try {
        MonetResourceResolver.resolve(
            graph = graph,
            catalog = catalog,
            profiles = profiles,
            sdkInt = DESKTOP_SMOKE_SDK,
            provider = provider,
        )
    } catch (error: MonetResolutionException) {
        return resultReport(
            environment = environment,
            started = started,
            startedNanos = startedNanos,
            input = inputMetadata,
            resources = resources,
            graph = graph,
            catalog = catalog,
            profiles = profiles,
            resolution = error.report,
            dexEvidence = provider.report(),
            outcome = MonetTestOutcome.FAIL,
            failureMessage = error.message,
        )
    }

    return try {
        val overlays = MonetOverlayBuilder(
            payloadDir = payload,
            catalog = catalog,
            resolution = resolution,
            targetGraph = graph,
            options = DESKTOP_SMOKE_OPTIONS,
            sdkInt = DESKTOP_SMOKE_SDK,
            blurPalette = DESKTOP_BLUR_PALETTE,
        ).buildAll(workerRoot.resolve("overlays"))
        val overlayReports = overlays.map { overlay ->
            validateOverlay(overlay, catalog, resolution)
        }
        resultReport(
            environment = environment,
            started = started,
            startedNanos = startedNanos,
            input = inputMetadata,
            resources = resources,
            graph = graph,
            catalog = catalog,
            profiles = profiles,
            resolution = resolution,
            dexEvidence = provider.report(),
            outcome = MonetTestOutcome.PASS,
            overlays = overlayReports,
        )
    } catch (error: Throwable) {
        resultReport(
            environment = environment,
            started = started,
            startedNanos = startedNanos,
            input = inputMetadata,
            resources = resources,
            graph = graph,
            catalog = catalog,
            profiles = profiles,
            resolution = resolution,
            dexEvidence = provider.report(),
            outcome = MonetTestOutcome.FAIL,
            failureMessage = "Overlay smoke failed: ${error.message ?: error.javaClass.name}",
        )
    }
}

private fun resultReport(
    environment: MonetTestEnvironment,
    started: Instant,
    startedNanos: Long,
    input: MonetTestInputMetadata,
    resources: MonetTestResourceSummary,
    graph: MonetResourceGraph,
    catalog: MonetRoleCatalog,
    profiles: List<MonetProfile>,
    resolution: MonetResolutionReport,
    dexEvidence: MonetTestDexEvidenceReport,
    outcome: MonetTestOutcome,
    overlays: List<MonetTestOverlayReport> = emptyList(),
    failureMessage: String? = null,
): MonetTestSampleReport {
    val roles = catalog.roles.map { role ->
        role.toReport(graph, profiles, resolution)
    }
    val coreFailures = roles.filter { role ->
        role.core && role.failure != null && role.failure != MonetResolutionFailure.SDK_UNSUPPORTED.name
    }.map(MonetTestRoleReport::roleId)
    val optionalSkips = resolution.skipped.map(MonetRoleDiagnostic::roleId)
    return MonetTestSampleReport(
        startedAt = started.toString(),
        finishedAt = Instant.now().toString(),
        elapsedMillis = elapsedMillis(startedNanos),
        outcome = outcome,
        environment = environment,
        input = input,
        resources = resources,
        roles = roles,
        coreFailures = coreFailures,
        optionalSkips = optionalSkips,
        dexEvidence = dexEvidence,
        overlays = overlays,
        failureMessage = failureMessage,
    )
}

private fun MonetRoleDefinition.toReport(
    graph: MonetResourceGraph,
    profiles: List<MonetProfile>,
    resolution: MonetResolutionReport,
): MonetTestRoleReport {
    val diagnostic = resolution.diagnostics[id]
    val profileKeys = profiles.mapNotNull { profile -> profile.roles[id] }.distinct()
    val profileTarget = profileKeys.singleOrNull()?.let(graph::node)?.toTarget()
    val finalTarget = resolution.resolved[id]?.let { resolved ->
        MonetTestResourceTarget(resolved.resourceId, resolved.key.type, resolved.key.name)
    }
    val stagedEvidenceIds = diagnostic?.stages
        ?.filter { stage ->
            stage.stage == MonetResolutionStage.XML_SHAPE_AND_REFERENCES ||
                stage.stage == MonetResolutionStage.INCOMING_RELATIONSHIPS
        }
        ?.flatMap(MonetCandidateStageDiagnostic::afterCandidateIds)
        .orEmpty()
    val evidenceIds = buildSet {
        profileTarget?.let { add(it.resourceId) }
        finalTarget?.let { add(it.resourceId) }
        addAll(monetCandidateSet(stagedEvidenceIds).sampleResourceIds)
    }
    return MonetTestRoleReport(
        roleId = id,
        core = core,
        profileTarget = profileTarget,
        genericCandidates = monetCandidateSet(diagnostic?.candidateIds.orEmpty()),
        finalTarget = finalTarget,
        candidateStages = diagnostic?.stages.orEmpty().map(MonetCandidateStageDiagnostic::toReport),
        xmlEvidence = evidenceIds.sorted().mapNotNull { resourceId ->
            graph.node(resourceId)?.let { node ->
                MonetTestXmlEvidence(
                    resourceId = resourceId,
                    type = node.key.type,
                    name = node.key.name,
                    shapeSha256 = graph.xmlShapes(resourceId).map { it.sha256 }.sorted(),
                    incomingResources = monetCandidateSet(graph.incoming(resourceId)),
                    outgoingResources = monetCandidateSet(graph.outgoing(resourceId)),
                )
            }
        },
        failure = diagnostic?.failure?.name,
        message = diagnostic?.message,
    )
}

private fun MonetCandidateStageDiagnostic.toReport() = MonetTestCandidateStage(
    stage = stage.name,
    beforeCandidates = monetCandidateSet(beforeCandidateIds),
    afterCandidates = monetCandidateSet(afterCandidateIds),
)

private fun dev.ujhhgtg.wekit.extensions.monet.MonetResourceNode?.toTarget(): MonetTestResourceTarget? =
    this?.let { node -> MonetTestResourceTarget(node.id, node.key.type, node.key.name) }

private fun MonetProfile.toReport() = MonetTestProfileMatch(
    resourceDigest = resourceDigest,
    versionName = versionName,
    channel = channel,
    roleCount = roles.size,
)

private fun MonetStructuralProfile.toReport() = MonetTestStructuralProfileMatch(
    versionName = versionName,
    channel = channel,
    roleCount = roles.size,
    sourceKind = sourceKind,
    sourceSnapshotSha256 = sourceEvidence?.resourceSnapshotSha256,
)

private fun validateOverlay(
    overlay: MonetBuiltOverlay,
    catalog: MonetRoleCatalog,
    resolution: MonetResolutionReport,
): MonetTestOverlayReport {
    MonetApkSigner.verifySignedApk(overlay.file)
    val definition = catalog.overlays.single { it.id == overlay.overlayId }
    val validatedTargets = linkedMapOf<String, String>()
    dev.ujhhgtg.wekit.extensions.monet.loadMonetTemplate(overlay.file).use { apk ->
        val manifest = requireNotNull(apk.androidManifest) { "built overlay has no manifest" }
        val pkg = requireNotNull(apk.tableBlock.pickOne()) { "built overlay has no resource package" }
        require(manifest.packageName == overlay.packageName)
        require(pkg.name == overlay.packageName)
        require(manifest.overlayTargetPackage() == TARGET_PACKAGE)
        overlay.roleIds.sorted().forEach { roleId ->
            val target = requireNotNull(resolution.resolved[roleId])
            requireNotNull(pkg.getResource(target.key.type, target.key.name)) {
                "built overlay ${overlay.fileName} lost $roleId -> ${target.key}"
            }
            require(roleId in definition.templateResources)
            validatedTargets[roleId] = "${target.key.type}/${target.key.name}"
        }
    }
    val blur = overlay.diagnostics.blurPalette?.let { palette ->
        MonetTestBlurPaletteReport(
            lightArgb = palette.lightArgb,
            nightArgb = palette.nightArgb,
            lightSource = palette.lightSource,
            nightSource = palette.nightSource,
        )
    }
    return MonetTestOverlayReport(
        overlayId = overlay.overlayId,
        packageName = overlay.packageName,
        fileName = overlay.fileName,
        sizeBytes = overlay.file.length(),
        sha256 = sha256(overlay.file),
        roleIds = overlay.roleIds.sorted(),
        skippedRoleIds = overlay.skippedRoleIds.sorted(),
        kept = overlay.kept,
        added = overlay.added,
        rewritten = overlay.rewritten,
        skipped = overlay.skipped,
        targetPackage = TARGET_PACKAGE,
        validatedRoleTargets = validatedTargets,
        parseable = true,
        signatureVerified = true,
        blurPalette = blur,
    )
}

private fun infrastructureReport(
    config: MonetTestWorkerConfig,
    environment: MonetTestEnvironment,
    started: Instant,
    startedNanos: Long,
    error: Throwable,
): MonetTestSampleReport = MonetTestSampleReport(
    startedAt = started.toString(),
    finishedAt = Instant.now().toString(),
    elapsedMillis = elapsedMillis(startedNanos),
    outcome = MonetTestOutcome.INFRASTRUCTURE_FAILURE,
    environment = environment,
    input = MonetTestInputMetadata(
        kind = config.inputKind,
        path = config.inputPath.toString(),
        fileName = config.inputPath.fileName?.toString().orEmpty(),
        metadataSource = if (config.inputKind == MonetTestInputKind.DECODED_RES) {
            MonetTestMetadataSource.PATH_INFERRED
        } else {
            MonetTestMetadataSource.APK_MANIFEST
        },
        versionCode = config.versionCode.takeIf { config.inputKind != MonetTestInputKind.DECODED_RES },
        versionName = config.versionName,
        isGooglePlay = config.isGooglePlay.takeIf { config.inputKind != MonetTestInputKind.DECODED_RES },
        channel = if (config.isGooglePlay) "google-play" else "domestic",
    ),
    dexEvidence = MonetTestDexEvidenceReport(
        status = if (config.inputKind == MonetTestInputKind.DECODED_RES) {
            MonetTestDexEvidenceStatus.UNAVAILABLE
        } else {
            MonetTestDexEvidenceStatus.FAILED
        },
        reason = error.message ?: error.javaClass.name,
    ),
    failureMessage = error.message,
    infrastructureError = error.toMonetTestError(),
)

private interface ReportingDexEvidenceProvider : MonetDexEvidenceProvider {
    fun report(): MonetTestDexEvidenceReport
}

private class RecordingDexEvidenceProvider(
    private val bridge: DexKitBridge,
) : ReportingDexEvidenceProvider {
    private val queries = mutableListOf<MonetTestDexQueryReport>()
    private var failure: String? = null

    override fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> = try {
        val evidence = DexKitMonetEvidenceCollector.collect(bridge, candidates)
        queries += MonetTestDexQueryReport(
            candidates = candidates.map(MonetDexCandidate::toReport),
            evidence = evidence.map(MonetResourceDexEvidence::toReport),
        )
        evidence
    } catch (error: Throwable) {
        failure = error.message ?: error.javaClass.name
        throw error
    }

    override fun report() = MonetTestDexEvidenceReport(
        status = when {
            failure != null -> MonetTestDexEvidenceStatus.FAILED
            queries.isEmpty() -> MonetTestDexEvidenceStatus.NOT_REQUESTED
            else -> MonetTestDexEvidenceStatus.COLLECTED
        },
        reason = failure,
        queries = queries.toList(),
    )
}

private class UnavailableDexEvidenceProvider : ReportingDexEvidenceProvider {
    private val queries = mutableListOf<MonetTestDexQueryReport>()

    override fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> {
        queries += MonetTestDexQueryReport(
            candidates = candidates.map(MonetDexCandidate::toReport),
            evidence = emptyList(),
        )
        error(DECODED_DEX_UNAVAILABLE_REASON)
    }

    override fun report() = MonetTestDexEvidenceReport(
        status = MonetTestDexEvidenceStatus.UNAVAILABLE,
        reason = DECODED_DEX_UNAVAILABLE_REASON,
        queries = queries.toList(),
    )
}

private fun MonetDexCandidate.toReport() = MonetTestDexCandidateReport(resourceId, type, name)

private fun MonetResourceDexEvidence.toReport() = MonetTestResourceDexEvidenceReport(
    resourceId = resourceId,
    methods = methods.map { method ->
        MonetTestMethodEvidenceReport(
            descriptor = method.descriptor,
            stableStrings = method.stableStrings,
            invokedMethodShapes = method.invokedMethodShapes,
            neighboringResourceIds = method.neighboringResourceIds,
            fieldAccesses = method.fieldAccesses.map { field ->
                MonetTestFieldAccessReport(field.descriptor, field.access.name)
            },
        )
    },
)

private object MonetNativeLibraryLoader {
    private var loadedPath: Path? = null

    @Synchronized
    fun load(path: Path) {
        val canonical = path.toRealPath()
        val existing = loadedPath
        require(existing == null || existing == canonical) {
            "DexKit native library already loaded from $existing, cannot load $canonical"
        }
        if (existing == null) {
            System.load(canonical.toString())
            loadedPath = canonical
        }
    }
}

private fun findPayloadDirectory(): File {
    val current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
    return listOf(
        current.resolve("app/embedded/monet"),
        current.resolve("../../app/embedded/monet"),
    ).firstOrNull { candidate -> candidate.resolve("monet_roles.json").isFile }
        ?: error("could not locate app/embedded/monet from $current")
}

private fun decodedResourceSnapshot(resourceDir: File): String {
    val root = resourceDir.canonicalFile.toPath()
    val files = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile)
            .filter { path ->
                val parent = path.parent?.fileName?.toString().orEmpty()
                val name = path.fileName.toString()
                name.endsWith(".xml") &&
                    (parent.startsWith("drawable") ||
                        parent.startsWith("layout") ||
                        (parent.startsWith("values") && name == "colors.xml"))
            }
            .sorted { left, right ->
                root.relativize(left).toString().compareTo(root.relativize(right).toString())
            }
            .toList()
    }
    val manifest = buildString {
        files.forEach { path ->
            append(root.relativize(path).joinToString("/") { it.toString() })
            append('\t').append(sha256(path.toFile())).append('\n')
        }
    }
    return sha256(manifest.toByteArray())
}

private fun decodedSampleName(resourceDir: File): String {
    val parts = resourceDir.canonicalFile.toPath().map(Path::toString)
    val appIndex = parts.indexOfLast { it == "app" }
    return if (appIndex > 0) parts[appIndex - 1] else resourceDir.name
}

private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun elapsedMillis(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

private fun AndroidManifestBlock.overlayTargetPackage(): String? =
    manifestElement
        ?.getElement("overlay")
        ?.searchAttributeByName("targetPackage")
        ?.valueAsString

private val DESKTOP_SMOKE_OPTIONS = MonetGenerationOptions(
    bubbleStyle = MonetBubbleStyle.MODERN,
    multiSceneCornersEnabled = false,
    tabStyle = MonetTabStyle.BLUR,
    userScope = MonetUserScope.CURRENT,
)
private val DESKTOP_BLUR_PALETTE = MonetBlurPalette(
    lightRgb = 0x123456,
    nightRgb = 0xabcdef,
    lightSource = "system_surface_container_light",
    nightSource = "system_surface_container_dark",
)
private const val DESKTOP_SMOKE_SDK = 33
private const val TARGET_PACKAGE = "com.tencent.mm"
private const val DECODED_DEX_UNAVAILABLE_REASON = "decoded resources contain no DEX evidence"

internal data class MonetPreparedResourceApk(
    val sourceName: String,
    val file: File,
)

internal class MonetPreparedApkInput(
    val baseApk: File,
    val resourceApks: List<MonetPreparedResourceApk>,
    val dexBytes: List<ByteArray>,
    val nestedApkCount: Int,
    val extractionRoot: File?,
    private val workRoot: File?,
) : Closeable {
    override fun close() {
        extractionRoot?.let { root ->
            check(!root.exists() || root.deleteRecursively()) {
                "failed to delete Monet test extraction directory: $root"
            }
        }
        workRoot?.takeIf { it.isDirectory && it.list().orEmpty().isEmpty() }?.delete()
    }
}

internal fun prepareApkInput(
    inputKind: MonetTestInputKind,
    input: File,
    workRoot: File,
): MonetPreparedApkInput = when (inputKind) {
    MonetTestInputKind.APK -> prepareStandaloneApk(input)
    MonetTestInputKind.APKS -> prepareSplitArchive(input, workRoot)
    MonetTestInputKind.DECODED_RES -> error("decoded resources do not contain APK inputs")
}

private fun prepareStandaloneApk(apk: File): MonetPreparedApkInput {
    require(apk.isFile) { "APK input is not a regular file: $apk" }
    val inspected = inspectApk("base.apk", apk)
    require(inspected.hasResources) { "standalone APK has no root resources.arsc: $apk" }
    require(inspected.dexEntries.isNotEmpty()) { "standalone APK has no classes*.dex: $apk" }
    return MonetPreparedApkInput(
        baseApk = apk,
        resourceApks = listOf(MonetPreparedResourceApk(inspected.sourceName, apk)),
        dexBytes = inspected.dexEntries.map(ApkDexEntry::bytes),
        nestedApkCount = 1,
        extractionRoot = null,
        workRoot = null,
    )
}

private fun prepareSplitArchive(apks: File, workRoot: File): MonetPreparedApkInput {
    require(apks.isFile) { "APKS input is not a regular file: $apks" }
    return ZipFile(apks).use { archive ->
        val seenNames = linkedSetOf<String>()
        val nestedEntries = archive.entries().asSequence()
            .filter { entry -> !entry.isDirectory && entry.name.endsWith(".apk") }
            .onEach { entry ->
                validateNestedApkName(entry.name)
                require(seenNames.add(entry.name)) { "duplicate nested APK entry: ${entry.name}" }
            }
            .sortedWith(compareBy<ZipEntry>({ !it.name.isBaseApkName() }, ZipEntry::getName))
            .toList()
        require(nestedEntries.isNotEmpty()) { "APKS contains no nested APK entries: $apks" }
        require(nestedEntries.count { it.name.isBaseApkName() } == 1) {
            "APKS must contain exactly one base.apk"
        }

        require(workRoot.mkdirs() || workRoot.isDirectory) {
            "failed to create Monet test work root: $workRoot"
        }
        val extractionRoot = Files.createTempDirectory(workRoot.toPath(), "sample-").toFile()
        try {
            val extracted = nestedEntries.mapIndexed { index, entry ->
                val output = extractionRoot.resolve(
                    "${index.toString().padStart(3, '0')}-${entry.name.substringAfterLast('/')}",
                )
                archive.getInputStream(entry).use { source ->
                    output.outputStream().buffered().use(source::copyTo)
                }
                inspectApk(entry.name, output)
            }
            val resourceApks = extracted
                .filter(InspectedApk::hasResources)
                .map { inspected -> MonetPreparedResourceApk(inspected.sourceName, inspected.file) }
            require(resourceApks.firstOrNull()?.sourceName?.isBaseApkName() == true) {
                "APKS base.apk has no root resources.arsc"
            }
            val dexEntries = extracted.flatMap(InspectedApk::dexEntries)
            require(dexEntries.isNotEmpty()) { "APKS contains no classes*.dex entries" }
            MonetPreparedApkInput(
                baseApk = extracted.single { it.sourceName.isBaseApkName() }.file,
                resourceApks = resourceApks,
                dexBytes = dexEntries.map(ApkDexEntry::bytes),
                nestedApkCount = extracted.size,
                extractionRoot = extractionRoot,
                workRoot = workRoot,
            )
        } catch (error: Throwable) {
            extractionRoot.deleteRecursively()
            if (workRoot.isDirectory && workRoot.list().orEmpty().isEmpty()) workRoot.delete()
            throw error
        }
    }
}

private fun inspectApk(sourceName: String, apk: File): InspectedApk = ZipFile(apk).use { archive ->
    val names = linkedSetOf<String>()
    val entries = archive.entries().asSequence()
        .filterNot(ZipEntry::isDirectory)
        .onEach { entry ->
            require(names.add(entry.name)) { "duplicate entry ${entry.name} in nested APK $sourceName" }
        }
        .toList()
    val dexEntries = entries.mapNotNull { entry ->
        val match = DEX_ENTRY.matchEntire(entry.name) ?: return@mapNotNull null
        val ordinal = match.groupValues[1].takeIf(String::isNotEmpty)?.toInt() ?: 1
        ApkDexEntry(
            sourceName = sourceName,
            ordinal = ordinal,
            bytes = archive.getInputStream(entry).use { it.readBytes() },
        )
    }.sortedWith(compareBy(ApkDexEntry::sourceName, ApkDexEntry::ordinal))
    InspectedApk(
        sourceName = sourceName,
        file = apk,
        hasResources = entries.any { it.name == RESOURCE_TABLE_PATH },
        dexEntries = dexEntries,
    )
}

private fun validateNestedApkName(name: String) {
    require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name) {
        "unsafe nested APK entry name: $name"
    }
    require(name.split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." && ':' !in segment
    }) {
        "unsafe nested APK entry name: $name"
    }
}

private fun String.isBaseApkName(): Boolean = this == "base.apk" || endsWith("/base.apk")

private data class InspectedApk(
    val sourceName: String,
    val file: File,
    val hasResources: Boolean,
    val dexEntries: List<ApkDexEntry>,
)

private data class ApkDexEntry(
    val sourceName: String,
    val ordinal: Int,
    val bytes: ByteArray,
)

private const val RESOURCE_TABLE_PATH = "resources.arsc"
private val DEX_ENTRY = Regex("classes(\\d*)\\.dex")
