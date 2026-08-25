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
        fixture.output.writeText("previous")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(fixture.request(bubbleStyle = MonetBubbleStyle.PRO), fixture.listener)
        }

        assertEquals("previous", fixture.output.readText())
        assertFalse(fixture.temporaryOutput.exists())
        assertFalse(fixture.workDir.exists())
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
    fun `pipeline passes only profiles with the canonical graph digest to resolver`() {
        val fixture = Fixture()
        val matchingDigest = fixture.stages.graph.resourceDigest()
        fixture.stages.profileCatalog = profileCatalog(
            matchingDigest = matchingDigest,
            nonMatchingDigest = "a".repeat(64),
        )

        fixture.pipeline.generate(fixture.request(), fixture.listener)

        assertEquals(listOf(matchingDigest), fixture.stages.resolverProfiles.map(MonetProfile::resourceDigest))
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
        val fixture = Fixture(resolutionFailure = MonetResolutionException(failure))
        fixture.output.writeText("previous")
        fixture.diagnostics.writeText("older diagnostics")

        assertThrows(MonetGenerationException::class.java) {
            fixture.pipeline.generate(fixture.request(), fixture.listener)
        }

        assertEquals("previous", fixture.output.readText())
        assertTrue(fixture.diagnostics.readText().contains("chat.bubble.incoming.normal"))
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

    private inner class Fixture(
        failOverlay: String? = null,
        resolutionFailure: MonetResolutionException? = null,
        omitLastOverlayFromPackage: Boolean = false,
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
        val stages = FakeStages(failOverlay, resolutionFailure, omitLastOverlayFromPackage)
        val pipeline = MonetGenerationPipeline(stages)

        fun request(
            bubbleStyle: MonetBubbleStyle = MonetBubbleStyle.MODERN,
            sourceApkPaths: List<String> = listOf(baseApk.absolutePath),
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
            outputZip = output,
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
    ) : MonetGenerationPipelineStages {
        val graph = MonetResourceGraph(
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
        var profileCatalog = profileCatalog(graph.resourceDigest(), "a".repeat(64))
        var loadedApks: List<File> = emptyList()
        var resolverProfiles: List<MonetProfile> = emptyList()
        var resolverProvider: MonetDexEvidenceProvider? = null

        override fun loadGraph(apkPaths: List<File>, targetPackage: String): MonetResourceGraph {
            loadedApks = apkPaths
            assertEquals("com.tencent.mm", targetPackage)
            return graph
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
                        else -> overlaysByEntry[name]?.file?.readBytes() ?: name.toByteArray()
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
