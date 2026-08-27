package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_API_VERSION
import dev.ujhhgtg.wekit.extensions.monet.api.MONET_GENERATOR_ENTRYPOINT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `archive extracts metadata and runtime files`() {
        val archive = writeArchive()
        val staging = temp.resolve("staging")
        val metadata = extract(archive, staging)

        assertEquals(MONET_GENERATOR_API_VERSION, metadata.apiVersion)
        assertEquals(MONET_GENERATOR_ENTRYPOINT, metadata.entrypoint)
        FILE_CONTENTS.forEach { (name, content) -> assertEquals(content, staging.resolve(name).readText()) }
    }

    @Test
    fun `archive enforces the expected API and entrypoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(writeArchive(apiVersion = MONET_GENERATOR_API_VERSION + 1), temp.resolve("api"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            extract(writeArchive(entrypoint = "invalid.Entrypoint"), temp.resolve("entrypoint"))
        }
    }

    @Test
    fun `installed metadata can be read again`() {
        val staging = temp.resolve("installed")
        extract(writeArchive(), staging)
        val metadata = MonetExtensionArchive.verifyInstalled(
            staging,
            MONET_GENERATOR_API_VERSION,
            MONET_GENERATOR_ENTRYPOINT,
        )
        assertEquals(MONET_GENERATOR_ENTRYPOINT, metadata.entrypoint)
    }

    private fun extract(archive: File, staging: File): MonetExtensionMetadata =
        MonetExtensionArchive.extractAndVerify(
            archive,
            staging,
            MONET_GENERATOR_API_VERSION,
            MONET_GENERATOR_ENTRYPOINT,
        )

    private fun writeArchive(
        apiVersion: Int = MONET_GENERATOR_API_VERSION,
        entrypoint: String = MONET_GENERATOR_ENTRYPOINT,
    ): File {
        val metadata = JsonObject(
            mapOf(
                "apiVersion" to JsonPrimitive(apiVersion),
                "entrypoint" to JsonPrimitive(entrypoint),
                "files" to JsonObject(FILE_CONTENTS.mapValues { JsonPrimitive("unused") }),
            ),
        ).toString()
        return temp.resolve("archive-${archiveCount++}.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                writeEntry(zip, "extension.json", metadata)
                FILE_CONTENTS.forEach { (name, content) -> writeEntry(zip, name, content) }
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.encodeToByteArray())
        zip.closeEntry()
    }

    private companion object {
        var archiveCount = 0
        val FILE_CONTENTS = linkedMapOf("classes.dex" to "dex", "payload/item" to "payload")
    }
}
