package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_API_VERSION
import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_ENTRYPOINT_V1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MonetExtensionArchiveTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `valid archive extracts every declared runtime file`() {
        val archive = writeArchive()
        val staging = temp.resolve("valid-staging")

        val metadata = extract(archive, staging)

        assertEquals(MONET_GENERATOR_API_VERSION, metadata.apiVersion)
        assertEquals(MONET_GENERATOR_ENTRYPOINT_V1, metadata.entrypoint)
        assertEquals(FILE_CONTENTS.keys, metadata.files.keys)
        FILE_CONTENTS.forEach { (name, content) ->
            assertEquals(content, staging.resolve(name).readText())
        }
        assertEquals(
            Json.parseToJsonElement(archiveExtensionJson(archive)),
            Json.parseToJsonElement(staging.resolve("extension.json").readText()),
        )
    }

    @Test
    fun `parent traversal entry is rejected without escaping staging`() {
        val archive = writeArchive(extraEntries = mapOf("../escape" to "escaped"))
        val staging = temp.resolve("traversal-staging")

        assertThrows(IllegalArgumentException::class.java) { extract(archive, staging) }

        assertFalse(temp.resolve("escape").exists())
    }

    @Test
    fun `absolute entry is rejected`() {
        val archive = writeArchive(extraEntries = mapOf("/absolute" to "bad"))

        assertThrows(IllegalArgumentException::class.java) {
            extract(archive, temp.resolve("absolute-staging"))
        }
    }

    @Test
    fun `backslash entry is rejected`() {
        val archive = writeArchive(extraEntries = mapOf("payload\\escape" to "bad"))

        assertThrows(IllegalArgumentException::class.java) {
            extract(archive, temp.resolve("backslash-staging"))
        }
    }

    @Test
    fun `dot segment entry is rejected`() {
        val archive = writeArchive(extraEntries = mapOf("payload/./escape" to "bad"))

        assertThrows(IllegalArgumentException::class.java) { extract(archive, temp.resolve("dot-staging")) }
    }

    @Test
    fun `duplicate archive entry is rejected`() {
        val archive = writeArchive(extraEntries = mapOf("classee.dex" to "duplicate"))
        replaceAscii(archive, "classee.dex", "classes.dex")

        assertThrows(IllegalArgumentException::class.java) {
            extract(archive, temp.resolve("duplicate-staging"))
        }
    }

    @Test
    fun `undeclared extra entry is rejected`() {
        val archive = writeArchive(extraEntries = mapOf("payload/extra" to "bad"))

        assertThrows(IllegalArgumentException::class.java) { extract(archive, temp.resolve("extra-staging")) }
    }

    @Test
    fun `missing classes dex is rejected`() {
        val archive = writeArchive(actualFiles = FILE_CONTENTS - "classes.dex")

        assertThrows(IllegalArgumentException::class.java) {
            extract(archive, temp.resolve("missing-dex-staging"))
        }
    }

    @Test
    fun `API version mismatch is rejected`() {
        val archive = writeArchive(apiVersion = MONET_GENERATOR_API_VERSION + 1)

        assertThrows(IllegalArgumentException::class.java) { extract(archive, temp.resolve("api-staging")) }
    }

    @Test
    fun `entrypoint mismatch is rejected`() {
        val archive = writeArchive(entrypoint = "invalid.Entrypoint")

        assertThrows(IllegalArgumentException::class.java) {
            extract(archive, temp.resolve("entrypoint-staging"))
        }
    }

    @Test
    fun `declared hash mismatch is rejected`() {
        val archive = writeArchive(
            declaredHashes = hashes(FILE_CONTENTS).toMutableMap().apply {
                this["classes.dex"] = "0".repeat(64)
            },
        )

        assertThrows(IllegalArgumentException::class.java) { extract(archive, temp.resolve("hash-staging")) }
    }

    @Test
    fun `short declared hash is rejected before extraction`() {
        val archive = writeArchive(
            declaredHashes = hashes(FILE_CONTENTS).toMutableMap().apply {
                this["classes.dex"] = "0".repeat(63)
            },
        )
        val staging = temp.resolve("short-hash-staging")

        assertThrows(IllegalArgumentException::class.java) { extract(archive, staging) }

        assertFalse(staging.resolve("classes.dex").exists())
    }

    @Test
    fun `non-hex declared hash is rejected before extraction`() {
        val archive = writeArchive(
            declaredHashes = hashes(FILE_CONTENTS).toMutableMap().apply {
                this["classes.dex"] = "g".repeat(64)
            },
        )
        val staging = temp.resolve("non-hex-hash-staging")

        assertThrows(IllegalArgumentException::class.java) { extract(archive, staging) }

        assertFalse(staging.resolve("classes.dex").exists())
    }

    @Test
    fun `installed directory is revalidated against metadata and hashes`() {
        val staging = temp.resolve("installed-staging")
        extract(writeArchive(), staging)
        PackFs.writeManifest(
            staging,
            PackManifest("monet-generator", "0123456789ab", "a".repeat(64), 1L),
        )

        MonetExtensionArchive.verifyInstalled(
            staging,
            MONET_GENERATOR_API_VERSION,
            MONET_GENERATOR_ENTRYPOINT_V1,
        )
        staging.resolve("payload/monet_tables.json").writeText("corrupted")

        assertThrows(IllegalArgumentException::class.java) {
            MonetExtensionArchive.verifyInstalled(
                staging,
                MONET_GENERATOR_API_VERSION,
                MONET_GENERATOR_ENTRYPOINT_V1,
            )
        }
    }

    @Test
    fun `installed directory rejects unexpected runtime files`() {
        val staging = temp.resolve("installed-extra-staging")
        extract(writeArchive(), staging)
        staging.resolve("payload/extra").writeText("unexpected")

        assertThrows(IllegalArgumentException::class.java) {
            MonetExtensionArchive.verifyInstalled(
                staging,
                MONET_GENERATOR_API_VERSION,
                MONET_GENERATOR_ENTRYPOINT_V1,
            )
        }
    }

    @Test
    fun `installed metadata contract is revalidated`() {
        val staging = temp.resolve("installed-metadata-staging")
        extract(writeArchive(), staging)
        staging.resolve("extension.json").writeText(
            staging.resolve("extension.json").readText()
                .replace("\"apiVersion\":1", "\"apiVersion\":2"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MonetExtensionArchive.verifyInstalled(
                staging,
                MONET_GENERATOR_API_VERSION,
                MONET_GENERATOR_ENTRYPOINT_V1,
            )
        }
    }

    @Test
    fun `metadata is limited to sixteen KiB before extraction`() {
        val archive = writeArchive(metadataPaddingBytes = 16 * 1024)
        val staging = temp.resolve("oversized-metadata-staging")

        assertThrows(IllegalArgumentException::class.java) { extract(archive, staging) }

        assertFalse(staging.resolve("classes.dex").exists())
    }

    @Test
    fun `each runtime entry enforces its declared size limit`() {
        val limits = linkedMapOf(
            "classes.dex" to 8 * 1024 * 1024,
            "payload/template_api31.apk" to 1024 * 1024,
            "payload/template_api34.apk" to 1024 * 1024,
            "payload/monet_tables.json" to 1024 * 1024,
            "payload/customize.sh" to 64 * 1024,
            "payload/update-binary" to 64 * 1024,
            "payload/updater-script" to 64 * 1024,
        )

        limits.forEach { (name, limit) ->
            val actualFiles = FILE_CONTENTS + (name to "x".repeat(limit + 1))
            val archive = writeArchive(
                actualFiles = actualFiles,
                declaredHashes = hashes(actualFiles),
            )

            assertThrows(
                IllegalArgumentException::class.java,
                { extract(archive, temp.resolve("oversized-${archiveCount}-staging")) },
                name,
            )
        }
    }

    @Test
    fun `streaming entry limit rejects bytes beyond declared budget`() {
        val output = ByteArrayOutputStream()
        val limiter = MonetExtractionLimiter(16)

        assertThrows(IllegalArgumentException::class.java) {
            limiter.copy(
                ByteArrayInputStream("12345".encodeToByteArray()),
                output,
                "classes.dex",
                4,
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun `streaming aggregate limit spans archive entries`() {
        val limiter = MonetExtractionLimiter(5)
        limiter.copy(
            ByteArrayInputStream("123".encodeToByteArray()),
            ByteArrayOutputStream(),
            "classes.dex",
            4,
        )

        assertThrows(IllegalArgumentException::class.java) {
            limiter.copy(
                ByteArrayInputStream("456".encodeToByteArray()),
                ByteArrayOutputStream(),
                "payload/customize.sh",
                4,
            )
        }
    }

    private fun extract(archive: File, staging: File): MonetExtensionMetadata =
        MonetExtensionArchive.extractAndVerify(
            archive,
            staging,
            MONET_GENERATOR_API_VERSION,
            MONET_GENERATOR_ENTRYPOINT_V1,
        )

    private fun writeArchive(
        actualFiles: Map<String, String> = FILE_CONTENTS,
        declaredHashes: Map<String, String> = hashes(FILE_CONTENTS),
        apiVersion: Int = MONET_GENERATOR_API_VERSION,
        entrypoint: String = MONET_GENERATOR_ENTRYPOINT_V1,
        extraEntries: Map<String, String> = emptyMap(),
        metadataPaddingBytes: Int = 0,
    ): File {
        val metadataFields = linkedMapOf(
            "apiVersion" to JsonPrimitive(apiVersion),
            "entrypoint" to JsonPrimitive(entrypoint),
            "files" to JsonObject(declaredHashes.mapValues { JsonPrimitive(it.value) }),
        )
        if (metadataPaddingBytes > 0) {
            metadataFields["padding"] = JsonPrimitive("x".repeat(metadataPaddingBytes))
        }
        val extensionJson = JsonObject(metadataFields).toString()
        return temp.resolve("archive-${archiveCount++}.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                writeEntry(zip, "extension.json", extensionJson)
                actualFiles.forEach { (name, content) -> writeEntry(zip, name, content) }
                extraEntries.forEach { (name, content) -> writeEntry(zip, name, content) }
            }
        }
    }

    private fun archiveExtensionJson(archive: File): String =
        java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry("extension.json")).readBytes().decodeToString()
        }

    private fun hashes(files: Map<String, String>): Map<String, String> =
        files.mapValues { (_, content) ->
            temp.resolve("hash-${hashCount++}").also { it.writeText(content) }.let(PackFs::sha256)
        }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.encodeToByteArray())
        zip.closeEntry()
    }

    private fun replaceAscii(file: File, from: String, to: String) {
        require(from.length == to.length)
        val bytes = file.readBytes()
        val needle = from.encodeToByteArray()
        val replacement = to.encodeToByteArray()
        var replacements = 0
        for (index in 0..bytes.size - needle.size) {
            if (bytes.copyOfRange(index, index + needle.size).contentEquals(needle)) {
                replacement.copyInto(bytes, index)
                replacements++
            }
        }
        require(replacements == 2) { "expected local and central ZIP names" }
        file.writeBytes(bytes)
    }

    private companion object {
        val FILE_CONTENTS = linkedMapOf(
            "classes.dex" to "dex",
            "payload/customize.sh" to "customize",
            "payload/monet_tables.json" to "tables",
            "payload/template_api31.apk" to "api31",
            "payload/template_api34.apk" to "api34",
            "payload/update-binary" to "binary",
            "payload/updater-script" to "script",
        )
    }

    private var archiveCount = 0
    private var hashCount = 0
}
