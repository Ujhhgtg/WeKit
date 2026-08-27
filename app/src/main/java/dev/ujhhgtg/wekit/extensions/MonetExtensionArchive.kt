package dev.ujhhgtg.wekit.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

@Serializable
internal data class MonetExtensionMetadata(
    val apiVersion: Int,
    val entrypoint: String,
    val files: Map<String, String>,
)

internal object MonetExtensionArchive {
    private const val METADATA_NAME = "extension.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun extractAndVerify(
        archive: File,
        stagingDir: File,
        expectedApiVersion: Int,
        expectedEntrypoint: String,
    ): MonetExtensionMetadata {
        stagingDir.mkdirs()
        return ZipFile(archive).use { zip ->
            val metadataBytes = zip.getInputStream(zip.getEntry(METADATA_NAME)).use { it.readBytes() }
            val metadata = decodeAndValidateMetadata(metadataBytes, expectedApiVersion, expectedEntrypoint)
            zip.entries().asSequence()
                .filterNot { it.isDirectory || it.name == METADATA_NAME }
                .forEach { entry ->
                    val destination = File(stagingDir, entry.name)
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use(input::copyTo)
                    }
                }
            stagingDir.resolve(METADATA_NAME).writeBytes(metadataBytes)
            metadata
        }
    }

    fun verifyInstalled(
        installedDir: File,
        expectedApiVersion: Int,
        expectedEntrypoint: String,
    ): MonetExtensionMetadata {
        val metadata = installedDir.resolve(METADATA_NAME).readBytes()
        return decodeAndValidateMetadata(metadata, expectedApiVersion, expectedEntrypoint)
    }

    private fun decodeAndValidateMetadata(
        bytes: ByteArray,
        expectedApiVersion: Int,
        expectedEntrypoint: String,
    ): MonetExtensionMetadata {
        val metadata = json.decodeFromString(MonetExtensionMetadata.serializer(), bytes.decodeToString())
        require(metadata.apiVersion == expectedApiVersion) {
            "incompatible Monet extension API ${metadata.apiVersion}"
        }
        require(metadata.entrypoint == expectedEntrypoint) {
            "incompatible Monet generator entrypoint ${metadata.entrypoint}"
        }
        return metadata
    }
}
