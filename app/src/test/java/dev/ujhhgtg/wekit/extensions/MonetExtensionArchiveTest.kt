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
    ): File {
        val extensionJson = JsonObject(
            mapOf(
                "apiVersion" to JsonPrimitive(apiVersion),
                "entrypoint" to JsonPrimitive(entrypoint),
                "files" to JsonObject(declaredHashes.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString()
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
