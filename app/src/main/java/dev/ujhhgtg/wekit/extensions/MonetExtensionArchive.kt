package dev.ujhhgtg.wekit.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
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
    private val sha256 = Regex("[0-9a-fA-F]{64}")

    private val requiredFiles = setOf(
        "classes.dex",
        "payload/customize.sh",
        "payload/monet_tables.json",
        "payload/template_api31.apk",
        "payload/template_api34.apk",
        "payload/update-binary",
        "payload/updater-script",
    )

    fun extractAndVerify(
        archive: File,
        stagingDir: File,
        expectedApiVersion: Int,
        expectedEntrypoint: String,
    ): MonetExtensionMetadata {
        require(archive.isFile) { "Monet extension archive does not exist: $archive" }
        require(stagingDir.mkdirs() || stagingDir.isDirectory) {
            "cannot create Monet extension staging directory: $stagingDir"
        }
        val stagingCanonical = stagingDir.canonicalPath

        return ZipFile(archive).use { zip ->
            val entries = mutableListOf<ZipEntry>()
            val names = mutableSetOf<String>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                requireSafePath(entry.name)
                require(!entry.isDirectory) { "unexpected Monet extension directory: ${entry.name}" }
                require(names.add(entry.name)) { "duplicate Monet extension entry: ${entry.name}" }
                entries += entry
            }

            val metadataEntry = entries.singleOrNull { it.name == METADATA_NAME }
                ?: throw IllegalArgumentException("Monet extension must contain one $METADATA_NAME")
            val metadataBytes = zip.getInputStream(metadataEntry).use { it.readBytes() }
            val metadata = json.decodeFromString(
                MonetExtensionMetadata.serializer(),
                metadataBytes.decodeToString(),
            )
            require(metadata.apiVersion == expectedApiVersion) {
                "incompatible Monet extension API ${metadata.apiVersion}"
            }
            require(metadata.entrypoint == expectedEntrypoint) {
                "incompatible Monet extension entrypoint ${metadata.entrypoint}"
            }
            metadata.files.keys.forEach(::requireSafePath)
            metadata.files.forEach { (name, hash) ->
                require(sha256.matches(hash)) {
                    "invalid Monet extension SHA-256 for $name"
                }
            }
            require(metadata.files.keys == requiredFiles) {
                "Monet extension file declaration mismatch"
            }
            require(names == requiredFiles + METADATA_NAME) {
                "Monet extension archive entries do not match its declarations"
            }

            entries.filterNot { it.name == METADATA_NAME }.forEach { entry ->
                val destination = containedDestination(stagingDir, stagingCanonical, entry.name)
                val parent = destination.parentFile!!
                require(parent.mkdirs() || parent.isDirectory) {
                    "cannot create Monet extension directory: $parent"
                }
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                require(PackFs.verify(destination, metadata.files.getValue(entry.name))) {
                    "Monet extension SHA-256 mismatch for ${entry.name}"
                }
            }

            val installedMetadata = containedDestination(stagingDir, stagingCanonical, METADATA_NAME)
            installedMetadata.writeBytes(metadataBytes)
            metadata
        }
    }

    private fun requireSafePath(name: String) {
        val segments = name.split('/')
        require(
            name.isNotEmpty() &&
                !File(name).isAbsolute &&
                '\\' !in name &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) { "unsafe Monet extension path: $name" }
    }

    private fun containedDestination(stagingDir: File, stagingCanonical: String, name: String): File {
        val destination = File(stagingDir, name)
        require(destination.canonicalPath.startsWith(stagingCanonical + File.separator)) {
            "unsafe Monet extension path: $name"
        }
        return destination
    }
}
