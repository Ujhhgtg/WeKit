package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
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
            val resourceApks = extractResourceBearingApks(apks, extractionDir)
            assertEquals(
                EXPECTED_RESOURCE_APK_COUNT,
                resourceApks.size,
                "unexpected Play 3084 resource-bearing APK boundary",
            )

            val graph = MonetApkResourceGraphLoader.load(resourceApks, TARGET_PACKAGE)

            assertEquals(PLAY_RESOURCE_GRAPH_DIGEST, graph.resourceDigest())
            val inputLayout = requireNotNull(graph.node(MonetResourceKey("layout", "v0")))
            val inputBackground = requireNotNull(graph.node(MonetResourceKey("drawable", "bw7")))
            val quoteBackground = requireNotNull(graph.node(MonetResourceKey("drawable", "cf8")))
            val keyboardStyle = requireNotNull(graph.node(MonetResourceKey("style", "a56")))
            val pressedKey = requireNotNull(graph.node(MonetResourceKey("drawable", "dq_")))
            assertTrue(inputLayout.id in graph.incoming(inputBackground.id))
            assertTrue(inputLayout.id in graph.incoming(quoteBackground.id))
            assertTrue(keyboardStyle.id in graph.incoming(pressedKey.id))

            val styleRole = MonetRoleCatalog.load(File("../../app/embedded/monet"))
                .roles.single { it.id == "payment.keyboard.key.style" }
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

    private fun extractResourceBearingApks(apks: File, outputDir: File): List<File> =
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
                output
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
        const val PLAY_RESOURCE_GRAPH_DIGEST =
            "0235e64f66ad276867de2482c2a3fd62daef0202b3061330ef0f6cf8db434ed9"
        const val TARGET_PACKAGE = "com.tencent.mm"
        const val RESOURCE_TABLE_PATH = "resources.arsc"
        const val EXPECTED_RESOURCE_APK_COUNT = 32
    }
}
