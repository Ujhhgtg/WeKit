package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class MonetPlay3084ApksIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `production loader reproduces audited Play 3084 resource graph digest`() {
        val apksPath = System.getProperty(PLAY_APKS_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: System.getenv(PLAY_APKS_ENV)
        assumeTrue(
            !apksPath.isNullOrBlank(),
            "$PLAY_APKS_PROPERTY system property and $PLAY_APKS_ENV environment variable are not set",
        )
        val apks = File(apksPath!!)
        assertTrue(apks.isFile, "configured Play 3084 APKS does not name a file: $apks")
        assertEquals(PLAY_APKS_SHA256, sha256(apks), "unexpected Play 3084 APKS")

        val extractionDir = File(tempDir, "resource-apks")
        assertTrue(extractionDir.mkdir(), "failed to create $extractionDir")
        try {
            val extractedResourceApks = extractResourceBearingApks(apks, extractionDir)
            assertEquals(
                EXPECTED_RESOURCE_APK_COUNT,
                extractedResourceApks.size,
                "unexpected Play 3084 resource-bearing APK boundary",
            )
            val baseApk = extractedResourceApks.single { extracted ->
                extracted.sourceName == "base.apk" || extracted.sourceName.endsWith("/base.apk")
            }.file
            val resourceApks = extractedResourceApks.map(ExtractedApk::file)

            val baseGraph = MonetApkResourceGraphLoader.load(listOf(baseApk), TARGET_PACKAGE)
            assertEquals(PLAY_BASE_RESOURCE_GRAPH_DIGEST, baseGraph.resourceDigest())

            val graph = MonetApkResourceGraphLoader.load(resourceApks, TARGET_PACKAGE)

            assertEquals(PLAY_FULL_RESOURCE_GRAPH_DIGEST, graph.resourceDigest())
            val payload = File("../../app/embedded/monet")
            val catalog = MonetRoleCatalog.load(payload)
            val profiles = MonetProfileCatalog.load(payload)
            val profile = profiles.verifiedProfiles.single()
            assertEquals(PLAY_BASE_RESOURCE_GRAPH_DIGEST, profile.resourceDigest)
            assertEquals(231, profile.roles.size)
            assertEquals(profile.roles.size, profile.roles.values.toSet().size)
            assertEquals(catalog.roles.map(MonetRoleDefinition::id).toSet(), profile.roles.keys)
            profile.roles.forEach { (roleId, key) ->
                assertEquals(catalog.roles.single { it.id == roleId }.type, key.type, roleId)
                val node = requireNotNull(graph.node(key)) { "$roleId -> $key is absent" }
                assertEquals(key, graph.node(node.id)?.key, "$roleId ID/key drift")
            }
            val violations = mutableListOf<String>()
            catalog.roles.forEach { role ->
                val target = requireNotNull(graph.node(profile.roles.getValue(role.id)))
                val signature = graph.referenceSignature(target.id)
                val structure = graph.referenceStructureSignature(target.id)
                if (role.defaultValue != null && signature?.defaultValue != role.defaultValue) {
                    violations += "${role.id}: default value"
                }
                if (role.nightValue != null && signature?.nightValue != role.nightValue) {
                    violations += "${role.id}: night value"
                }
                if (role.defaultValueStructure != null && structure?.defaultValue != role.defaultValueStructure) {
                    violations += "${role.id}: default value structure"
                }
                if (role.nightValueStructure != null && structure?.nightValue != role.nightValueStructure) {
                    violations += "${role.id}: night value structure"
                }
                if (role.xmlShapeSha256 != null && graph.xmlShapes(target.id).none {
                        it.sha256 == role.xmlShapeSha256
                    }
                ) {
                    violations += "${role.id}: XML shape expected=${role.xmlShapeSha256} " +
                        "actual=${graph.xmlShapes(target.id).map(MonetXmlShape::sha256).sorted()}"
                }
                val expectedIncomingIds = role.requiredIncomingRoleIds.map { incomingRoleId ->
                    requireNotNull(graph.node(profile.roles.getValue(incomingRoleId))).id
                }
                if (!graph.incoming(target.id).containsAll(expectedIncomingIds)) {
                    violations += "${role.id}: incoming roles ${role.requiredIncomingRoleIds}"
                }
            }
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
            val fullGraphDexProvider = RecordingAuditedDexProvider()
            val resolved = MonetResourceResolver.resolve(
                graph = graph,
                catalog = catalog,
                profiles = listOf(profile.toResolutionProfile()),
                sdkInt = 36,
                provider = fullGraphDexProvider,
            )
            assertEquals(listOf(AUDITED_DEX_CANDIDATE_IDS), fullGraphDexProvider.requestedIdSets)
            assertEquals(profile.roles, resolved.resolved.mapValues { it.value.key })

            val representativeInstalledApks = extractedResourceApks.filter { extracted ->
                extracted.sourceName in REPRESENTATIVE_INSTALLED_APKS
            }.map(ExtractedApk::file)
            assertTrue(representativeInstalledApks.size >= 3, "representative Play split subset is incomplete")
            val representativeGraph = MonetApkResourceGraphLoader.load(
                representativeInstalledApks,
                TARGET_PACKAGE,
            )
            assertEquals(PLAY_BASE_RESOURCE_GRAPH_DIGEST, baseGraph.resourceDigest())
            assertFalse(profile.resourceDigest == representativeGraph.resourceDigest())
            val representativeDexProvider = RecordingAuditedDexProvider()
            val representativeResolved = MonetResourceResolver.resolve(
                graph = representativeGraph,
                catalog = catalog,
                profiles = listOf(profile.toResolutionProfile()),
                sdkInt = 36,
                provider = representativeDexProvider,
            )
            assertEquals(listOf(AUDITED_DEX_CANDIDATE_IDS), representativeDexProvider.requestedIdSets)
            assertEquals(profile.roles, representativeResolved.resolved.mapValues { it.value.key })

            val inputLayout = requireNotNull(graph.node(MonetResourceKey("layout", "v0")))
            val inputBackground = requireNotNull(graph.node(MonetResourceKey("drawable", "bw7")))
            val quoteBackground = requireNotNull(graph.node(MonetResourceKey("drawable", "cf8")))
            val keyboardStyle = requireNotNull(graph.node(MonetResourceKey("style", "a56")))
            val pressedKey = requireNotNull(graph.node(MonetResourceKey("drawable", "dq_")))
            assertTrue(inputLayout.id in graph.incoming(inputBackground.id))
            assertTrue(inputLayout.id in graph.incoming(quoteBackground.id))
            assertTrue(keyboardStyle.id in graph.incoming(pressedKey.id))

            val styleRole = catalog.roles.single { it.id == "payment.keyboard.key.style" }
            assertEquals(
                styleRole.defaultValueStructure,
                graph.referenceStructureSignature(keyboardStyle.id)?.defaultValue,
            )
        } finally {
            check(extractionDir.deleteRecursively()) {
                "failed to recursively delete $extractionDir"
            }
        }
    }

    private fun extractResourceBearingApks(apks: File, outputDir: File): List<ExtractedApk> =
        ZipFile(apks).use { bundle ->
            val names = mutableSetOf<String>()
            val apkEntries = bundle.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk") }
                .onEach { entry ->
                    validateNestedApkName(entry.name)
                    require(names.add(entry.name)) {
                        "duplicate nested APK entry: ${entry.name}"
                    }
                }
                .sortedBy(ZipEntry::getName)
                .toList()
            require(apkEntries.isNotEmpty()) { "APKS contains no nested APK entries" }

            val resourceEntries = apkEntries.filter { entry ->
                bundle.getInputStream(entry).use { input ->
                    ZipInputStream(input.buffered()).use { nestedApk ->
                        generateSequence(nestedApk::getNextEntry).any { nestedEntry ->
                            !nestedEntry.isDirectory && nestedEntry.name == RESOURCE_TABLE_PATH
                        }
                    }
                }
            }
            require(resourceEntries.any { it.name == "base.apk" || it.name.endsWith("/base.apk") }) {
                "resource-bearing APK entries do not include base.apk"
            }

            resourceEntries.mapIndexed { index, entry ->
                val output = File(outputDir, "resource-${index.toString().padStart(3, '0')}.apk")
                bundle.getInputStream(entry).use { input ->
                    output.outputStream().buffered().use(input::copyTo)
                }
                ExtractedApk(entry.name, output)
            }
        }

    private data class ExtractedApk(val sourceName: String, val file: File)

    private class RecordingAuditedDexProvider : MonetDexEvidenceProvider {
        val requestedIdSets = mutableListOf<Set<Int>>()

        override fun query(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> {
            requestedIdSets += candidates.mapTo(linkedSetOf(), MonetDexCandidate::resourceId)
            return candidates.map { candidate ->
                if (candidate.resourceId == AUDITED_DEX_TARGET_ID) {
                    AUDITED_DEX_TARGET_EVIDENCE
                } else {
                    MonetResourceDexEvidence(candidate.resourceId, emptyList())
                }
            }
        }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val PLAY_APKS_PROPERTY = "wekit.monet.play3084Apks"
        const val PLAY_APKS_ENV = "WEKIT_MONET_PLAY_3084_APKS"
        const val PLAY_APKS_SHA256 =
            "64121c48f76dfa01e92e0ac40c4f8df8888e0d4861dcfda5b83838f06e19fd24"
        const val PLAY_FULL_RESOURCE_GRAPH_DIGEST =
            "0235e64f66ad276867de2482c2a3fd62daef0202b3061330ef0f6cf8db434ed9"
        const val PLAY_BASE_RESOURCE_GRAPH_DIGEST =
            "1c2955c55a9029ccc0c918801dd31ea301eaa601a287c5bb0ed709fe4e3b31eb"
        const val TARGET_PACKAGE = "com.tencent.mm"
        const val RESOURCE_TABLE_PATH = "resources.arsc"
        const val EXPECTED_RESOURCE_APK_COUNT = 32
        const val AUDITED_DEX_TARGET_ID = 0x7f06009f
        const val AUDITED_DEX_ALTERNATIVE_ID = 0x7f0600a0
        val AUDITED_DEX_CANDIDATE_IDS = setOf(
            AUDITED_DEX_TARGET_ID,
            AUDITED_DEX_ALTERNATIVE_ID,
        )
        val AUDITED_DEX_TARGET_EVIDENCE = MonetResourceDexEvidence(
            resourceId = AUDITED_DEX_TARGET_ID,
            methods = listOf(
                MonetMethodDexEvidence(
                    descriptor = "Lcom/tencent/mm/plugin/setting/ui/setting/" +
                        "SettingsHearingAidFinishUI;->onCreate(Landroid/os/Bundle;)V",
                    stableStrings = listOf("audio_auto_play", "process_is_from_init"),
                    invokedMethodShapes = listOf(
                        "android.content.Intent#getBooleanExtra(java.lang.String,boolean):boolean",
                        "android.content.res.Resources#getColor(int):int",
                    ),
                    neighboringResourceIds = emptyList(),
                    fieldAccesses = emptyList(),
                ),
            ),
        )
        val REPRESENTATIVE_INSTALLED_APKS = setOf(
            "base.apk",
            "split_config.zh.apk",
            "split_config.xxhdpi.apk",
            "split_delivery.apk",
        )
    }
}
