package dev.ujhhgtg.wekit.extensions.monet

import android.content.res.Resources
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBlurPalette
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEventV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListenerV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequestV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStageV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sun.misc.Unsafe
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MonetGenerationPipelineTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `failed selected overlay preserves previous successful zip`() {
        val fixture = Fixture(failOverlay = "MonetWeChatBubblePro.apk")
        fixture.workDir.mkdirs()
        val sentinel = fixture.workDir.resolve("caller-owned.txt").apply { writeText("keep") }
        fixture.output.writeText("previous")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(fixture.request(bubbleStyle = MonetBubbleStyle.PRO), fixture.listener)
        }

        assertEquals("previous", fixture.output.readText())
        assertFalse(fixture.temporaryOutput.exists())
        assertEquals("keep", sentinel.readText())
        assertEquals(listOf(sentinel), fixture.workDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `pipeline emits exact progress order`() {
        val fixture = Fixture()

        fixture.pipeline.generate(
            fixture.request(bubbleStyle = MonetBubbleStyle.CLASSIC),
            fixture.listener,
        )

        assertEquals(
            listOf(
                MonetGenerationStageV2.PREPARING,
                MonetGenerationStageV2.SCANNING_RESOURCES,
                MonetGenerationStageV2.RESOLVING_RESOURCES,
                MonetGenerationStageV2.BUILDING_OVERLAYS,
                MonetGenerationStageV2.SIGNING,
                MonetGenerationStageV2.PACKAGING,
            ),
            fixture.progressStages,
        )
    }

    @Test
    fun `pipeline loads only base and resource bearing splits in deterministic order`() {
        val fixture = Fixture()
        val secondResourceSplit = fixture.apk("b-resource.apk", resourceBearing = true)
        val abiSplit = fixture.apk("a-abi.apk", resourceBearing = false)
        val firstResourceSplit = fixture.apk("a-resource.apk", resourceBearing = true)

        fixture.pipeline.generate(
            fixture.request(
                sourceApkPaths = listOf(
                    fixture.baseApk.absolutePath,
                    secondResourceSplit.absolutePath,
                    abiSplit.absolutePath,
                    firstResourceSplit.absolutePath,
                ),
            ),
            fixture.listener,
        )

        assertEquals(
            listOf(fixture.baseApk, firstResourceSplit, secondResourceSplit).map(File::getCanonicalFile),
            fixture.stages.loadedApks,
        )
    }

    @Test
    fun `pipeline selects profiles by base graph digest and resolves the full split graph`() {
        val fixture = Fixture()
        val resourceSplit = fixture.apk("profile-resource.apk", resourceBearing = true)
        val matchingDigest = fixture.stages.baseGraph.resourceDigest()
        assertFalse(matchingDigest == fixture.stages.fullGraph.resourceDigest())
        fixture.stages.profileCatalog = profileCatalog(
            matchingDigest = matchingDigest,
            nonMatchingDigest = "a".repeat(64),
        )

        fixture.pipeline.generate(
            fixture.request(sourceApkPaths = listOf(fixture.baseApk.path, resourceSplit.path)),
            fixture.listener,
        )

        assertEquals(listOf(matchingDigest), fixture.stages.resolverProfiles.map(MonetProfile::resourceDigest))
        assertTrue(fixture.stages.resolverGraph === fixture.stages.fullGraph)
        assertEquals(
            listOf(listOf(fixture.baseApk.canonicalFile, resourceSplit.canonicalFile)),
            fixture.stages.loadedApkBatches,
        )
        assertEquals(listOf("base-digest", "resolution-graph"), fixture.stages.graphLoadOperations)
        assertEquals(fixture.request().dexEvidenceProvider, fixture.stages.resolverProvider)
    }

    @Test
    fun `resolution failure atomically publishes complete diagnostics and preserves output`() {
        val failure = MonetRoleDiagnostic(
            roleId = "chat.bubble.incoming.normal",
            core = true,
            failure = MonetResolutionFailure.AMBIGUOUS,
            candidateIds = listOf(0x7f080001, 0x7f080002),
            stages = emptyList(),
            message = "fixture ambiguity",
        )
        val priorRole = MonetResolvedRole(
            roleId = "chat.background",
            resourceId = 0x7f060001,
            key = MonetResourceKey("color", "chat_background"),
            profileMatched = false,
        )
        val fixture = Fixture(
            resolutionFailure = MonetResolutionException(
                diagnostic = failure,
                report = MonetResolutionReport(
                    resolved = mapOf(priorRole.roleId to priorRole),
                    skipped = emptyList(),
                    diagnostics = mapOf(failure.roleId to failure),
                ),
            ),
        )
        fixture.output.writeText("previous")
        fixture.diagnostics.writeText("older diagnostics")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(fixture.request(), fixture.listener)
        }

        assertEquals("previous", fixture.output.readText())
        assertTrue(fixture.diagnostics.readText().contains("chat.bubble.incoming.normal"))
        assertTrue(fixture.diagnostics.readText().contains("chat.background"))
        assertTrue(fixture.diagnostics.readText().contains("AMBIGUOUS"))
        assertFalse(File(fixture.diagnostics.parentFile, fixture.diagnostics.name + ".tmp").exists())
    }

    @Test
    fun `package reopen rejects missing selected overlay without replacing output`() {
        val fixture = Fixture(omitLastOverlayFromPackage = true)
        fixture.output.writeText("previous")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(
                fixture.request(bubbleStyle = MonetBubbleStyle.CLASSIC),
                fixture.listener,
            )
        }

        assertEquals("previous", fixture.output.readText())
        assertFalse(fixture.temporaryOutput.exists())
    }

    @Test
    fun `package reopen rejects equal size selected overlay corruption`() {
        val fixture = Fixture(corruptOverlayInPackage = true)
        fixture.output.writeText("previous")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(fixture.request(), fixture.listener)
        }

        assertEquals("previous", fixture.output.readText())
        assertFalse(fixture.temporaryOutput.exists())
    }

    @Test
    fun `preparing listener failure preserves caller work root and temporary output`() {
        val fixture = Fixture()
        fixture.workDir.mkdirs()
        val sentinel = fixture.workDir.resolve("caller-owned.txt").apply { writeText("keep") }
        fixture.output.writeText("previous")
        fixture.temporaryOutput.writeText("caller temporary")
        val throwingListener = MonetGenerationListenerV2 { error("listener failure") }

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(fixture.request(), throwingListener)
        }

        assertEquals("keep", sentinel.readText())
        assertEquals("previous", fixture.output.readText())
        assertEquals("caller temporary", fixture.temporaryOutput.readText())
    }

    @Test
    fun `overlapping caller work root is rejected without deleting payload or output`() {
        val fixture = Fixture()
        fixture.output.writeText("previous")
        val payloadSentinel = fixture.payloadDir.resolve("caller-owned.txt").apply { writeText("keep") }

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(
                fixture.request(workDir = fixture.output.parentFile!!),
                fixture.listener,
            )
        }

        assertEquals("keep", payloadSentinel.readText())
        assertEquals("previous", fixture.output.readText())
        assertTrue(fixture.baseApk.isFile)
    }

    @Test
    fun `payload directory cannot be caller work root`() {
        val fixture = Fixture()
        val payloadSentinel = fixture.payloadDir.resolve("caller-owned.txt").apply { writeText("keep") }

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(
                fixture.request(workDir = fixture.payloadDir),
                fixture.listener,
            )
        }

        assertEquals("keep", payloadSentinel.readText())
        REQUIRED_PAYLOADS.forEach { name -> assertTrue(fixture.payloadDir.resolve(name).isFile) }
    }

    @Test
    fun `diagnostics scratch alias is rejected before output or diagnostics mutation`() {
        val fixture = Fixture()
        val aliasDir = fixture.output.parentFile!!.resolve("alias").apply { mkdir() }
        val aliasedScratchOutput = aliasDir.resolve("../monet-resolution.json.tmp")
        val canonicalScratchOutput = aliasedScratchOutput.canonicalFile.apply { writeText("prior output") }
        fixture.diagnostics.writeText("prior diagnostics")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(
                fixture.request(outputZip = aliasedScratchOutput),
                fixture.listener,
            )
        }

        assertEquals("prior output", canonicalScratchOutput.readText())
        assertEquals("prior diagnostics", fixture.diagnostics.readText())
        assertTrue(aliasDir.isDirectory)
    }

    @Test
    fun `invalid source list preserves all caller paths before run ownership`() {
        val fixture = Fixture()
        fixture.workDir.mkdirs()
        val workSentinel = fixture.workDir.resolve("caller-owned.txt").apply { writeText("work") }
        val payloadSentinel = fixture.payloadDir.resolve("caller-owned.txt").apply { writeText("payload") }
        fixture.output.writeText("prior output")
        fixture.temporaryOutput.writeText("prior temporary")
        fixture.diagnostics.writeText("prior diagnostics")
        val missingSplit = fixture.output.parentFile!!.resolve("missing-split.apk")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(
                fixture.request(
                    sourceApkPaths = listOf(fixture.baseApk.path, missingSplit.path),
                ),
                fixture.listener,
            )
        }

        assertEquals("work", workSentinel.readText())
        assertEquals("payload", payloadSentinel.readText())
        assertEquals("prior output", fixture.output.readText())
        assertEquals("prior temporary", fixture.temporaryOutput.readText())
        assertEquals("prior diagnostics", fixture.diagnostics.readText())
    }

    private inner class Fixture(
        failOverlay: String? = null,
        resolutionFailure: MonetResolutionException? = null,
        omitLastOverlayFromPackage: Boolean = false,
        corruptOverlayInPackage: Boolean = false,
    ) {
        val payloadDir = File(tempDir, "payload-${nextFixtureId()}").apply(::writeRequiredPayload)
        val workDir = File(tempDir, "work-${nextFixtureId()}")
        val output = File(tempDir, "output-${nextFixtureId()}.zip")
        val temporaryOutput = File(output.parentFile, output.name + ".tmp")
        val diagnostics = File(output.parentFile, "monet-resolution.json")
        val baseApk = apk("base-${nextFixtureId()}.apk", resourceBearing = true)
        val progressStages = mutableListOf<MonetGenerationStageV2>()
        val listener = MonetGenerationListenerV2 { event ->
            if (event is MonetGenerationEventV2.Progress) progressStages += event.stage
        }
        val stages = FakeStages(
            failOverlay,
            resolutionFailure,
            omitLastOverlayFromPackage,
            corruptOverlayInPackage,
        )
        val pipeline = MonetGenerationPipeline(stages)

        fun request(
            bubbleStyle: MonetBubbleStyle = MonetBubbleStyle.MODERN,
            sourceApkPaths: List<String> = listOf(baseApk.absolutePath),
            workDir: File = this.workDir,
            outputZip: File = output,
        ) = MonetGenerationRequestV2(
            resources = uninitializedResources(),
            packageName = "com.tencent.mm",
            sourceApkPaths = sourceApkPaths,
            versionCode = 3084,
            versionName = "8.0.72",
            isGooglePlay = true,
            sdkInt = 33,
            currentUserId = 10,
            options = MonetGenerationOptions(
                bubbleStyle = bubbleStyle,
                multiSceneCornersEnabled = true,
                tabStyle = MonetTabStyle.SOLID,
                userScope = MonetUserScope.CURRENT,
            ),
            blurPalette = null,
            dexEvidenceProvider = NO_DEX_EVIDENCE,
            payloadDir = payloadDir,
            workDir = workDir,
            outputZip = outputZip,
        )

        fun apk(name: String, resourceBearing: Boolean): File = File(tempDir, name).also { file ->
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                val entryName = if (resourceBearing) "resources.arsc" else "lib/arm64-v8a/libfixture.so"
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }

    private class FakeStages(
        private val failOverlay: String?,
        private val resolutionFailure: MonetResolutionException?,
        private val omitLastOverlayFromPackage: Boolean,
        private val corruptOverlayInPackage: Boolean,
    ) : MonetGenerationPipelineStages {
        val baseGraph = MonetResourceGraph(
            listOf(
                MonetResourceNode(
                    id = 0x7f080001,
                    key = MonetResourceKey("drawable", "fixture"),
                    values = listOf(
                        MonetConfiguredValue("", MonetResourceValue.Literal("INT_COLOR_ARGB8", 1)),
                    ),
                ),
            ),
        )
        val fullGraph = MonetResourceGraph(
            listOf(
                requireNotNull(baseGraph.node(0x7f080001)),
                MonetResourceNode(
                    id = 0x7f080002,
                    key = MonetResourceKey("drawable", "split_fixture"),
                    values = listOf(
                        MonetConfiguredValue("", MonetResourceValue.Literal("INT_COLOR_ARGB8", 2)),
                    ),
                ),
            ),
        )
        var profileCatalog = profileCatalog(baseGraph.resourceDigest(), "a".repeat(64))
        var loadedApks: List<File> = emptyList()
        val loadedApkBatches = mutableListOf<List<File>>()
        var resolverProfiles: List<MonetProfile> = emptyList()
        var resolverProvider: MonetDexEvidenceProvider? = null
        var resolverGraph: MonetResourceGraph? = null
        val graphLoadOperations = mutableListOf<String>()

        override fun loadBaseResourceDigest(baseApk: File, targetPackage: String): String {
            graphLoadOperations += "base-digest"
            assertEquals("com.tencent.mm", targetPackage)
            return baseGraph.resourceDigest()
        }

        override fun loadGraph(apkPaths: List<File>, targetPackage: String): MonetResourceGraph {
            graphLoadOperations += "resolution-graph"
            loadedApks = apkPaths
            loadedApkBatches += apkPaths
            assertEquals("com.tencent.mm", targetPackage)
            return if (apkPaths.size == 1) baseGraph else fullGraph
        }

        override fun loadRoleCatalog(payloadDir: File): MonetRoleCatalog = ROLE_CATALOG

        override fun loadProfileCatalog(payloadDir: File): MonetProfileCatalog = profileCatalog

        override fun resolve(
            graph: MonetResourceGraph,
            catalog: MonetRoleCatalog,
            profiles: List<MonetProfile>,
            sdkInt: Int,
            provider: MonetDexEvidenceProvider,
        ): MonetResolutionReport {
            resolverProfiles = profiles
            resolverProvider = provider
            resolverGraph = graph
            resolutionFailure?.let { throw it }
            return RESOLUTION_REPORT
        }

        override fun buildOverlays(
            request: MonetGenerationRequestV2,
            catalog: MonetRoleCatalog,
            resolution: MonetResolutionReport,
            graph: MonetResourceGraph,
            outputDir: File,
        ): List<MonetBuiltOverlay> {
            val names = buildList {
                add("MonetWeChat.apk")
                if (request.options.bubbleStyle == MonetBubbleStyle.CLASSIC) {
                    add("MonetWeChatClassicBubble.apk")
                }
                if (request.options.bubbleStyle == MonetBubbleStyle.PRO) {
                    add("MonetWeChatBubblePro.apk")
                }
                if (request.options.multiSceneCornersEnabled) {
                    add("MonetWeChatMultiSceneCorners.apk")
                }
                add("MonetWeChatSolidTab.apk")
            }
            return names.mapIndexed { index, name ->
                val file = outputDir.resolve(name).apply {
                    parentFile!!.mkdirs()
                    writeText("signed-$name")
                }
                MonetBuiltOverlay(
                    overlayId = "fixture-$index",
                    packageName = "fixture.$index",
                    fileName = name,
                    file = file,
                    roleIds = emptySet(),
                    skippedRoleIds = emptySet(),
                    kept = 1,
                    added = 0,
                    rewritten = 0,
                    skipped = 0,
                    diagnostics = MonetOverlayBuildDiagnostics(),
                )
            }
        }

        override fun verifySignedOverlay(overlay: MonetBuiltOverlay) {
            if (overlay.fileName == failOverlay) error("selected overlay verification failed")
        }

        override fun packageModule(
            request: MonetGenerationRequestV2,
            overlays: List<MonetBuiltOverlay>,
            diagnosticsFile: File,
            outputZip: File,
        ) {
            val overlayEntries = overlays.map { overlay ->
                "system/product/overlay/${overlay.fileName}"
            }.let { if (omitLastOverlayFromPackage) it.dropLast(1) else it }
            val overlaysByEntry = overlays.associateBy { overlay ->
                "system/product/overlay/${overlay.fileName}"
            }
            val entries = REQUIRED_MODULE_ENTRIES + overlayEntries
            ZipOutputStream(outputZip.outputStream().buffered()).use { zip ->
                entries.sorted().forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    val bytes = when (name) {
                        "monet-resolution.json" -> diagnosticsFile.readBytes()
                        else -> overlaysByEntry[name]?.file?.readBytes()?.let { original ->
                            if (corruptOverlayInPackage) {
                                original.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
                            } else {
                                original
                            }
                        } ?: name.toByteArray()
                    }
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            ZipFile(outputZip).use { zip -> assertTrue(zip.entries().hasMoreElements()) }
        }
    }

    private fun writeRequiredPayload(payloadDir: File) {
        REQUIRED_PAYLOADS.forEach { name ->
            payloadDir.resolve(name).apply {
                parentFile!!.mkdirs()
                writeText("fixture")
            }
        }
    }

    private fun uninitializedResources(): Resources {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (unsafeField.get(null) as Unsafe).allocateInstance(Resources::class.java) as Resources
    }

    private fun nextFixtureId(): Int = fixtureId++

    companion object {
        private var fixtureId = 0

        private val NO_DEX_EVIDENCE = MonetDexEvidenceProvider { emptyList() }
        private val ROLE_CATALOG = MonetRoleCatalog(
            schemaVersion = 1,
            roles = listOf(MonetRoleDefinition(id = "fixture.role", type = "drawable", core = true)),
            overlays = emptyList(),
        )
        private val RESOLUTION_REPORT = MonetResolutionReport(
            resolved = emptyMap(),
            skipped = emptyList(),
            diagnostics = emptyMap(),
        )
        private val REQUIRED_PAYLOADS = setOf(
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
        private val REQUIRED_MODULE_ENTRIES = setOf(
            "META-INF/com/google/android/update-binary",
            "META-INF/com/google/android/updater-script",
            "boot-completed.sh",
            "common.sh",
            "config.conf",
            "customize.sh",
            "module.prop",
            "monet-resolution.json",
            "service.sh",
        )

        private fun profileCatalog(
            matchingDigest: String,
            nonMatchingDigest: String,
        ): MonetProfileCatalog = MonetProfileCatalog(
            schemaVersion = 1,
            digestAlgorithm = MonetProfileCatalog.SUPPORTED_DIGEST_ALGORITHM,
            verifiedProfiles = listOf(
                MonetVerifiedProfile(
                    resourceDigest = matchingDigest,
                    versionName = "digest-match",
                    versionCode = 9999,
                    channel = "google-play",
                    sourceApksSha256 = "b".repeat(64),
                    roles = emptyMap(),
                ),
                MonetVerifiedProfile(
                    resourceDigest = nonMatchingDigest,
                    versionName = "8.0.72",
                    versionCode = 3084,
                    channel = "google-play",
                    sourceApksSha256 = "c".repeat(64),
                    roles = emptyMap(),
                ),
            ),
            structuralOnlyProfiles = MonetProfileCatalog.DOMESTIC_VERSIONS.map { version ->
                MonetStructuralProfile(
                    versionName = version,
                    channel = "domestic",
                    selectable = false,
                    reason = "fixture structural evidence",
                    roles = mapOf("fixture.role" to MonetResourceKey("drawable", "wrong")),
                )
            },
        )
    }
}
