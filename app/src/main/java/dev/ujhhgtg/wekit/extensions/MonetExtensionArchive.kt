package dev.ujhhgtg.wekit.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@Serializable
internal data class MonetExtensionMetadata(
    val apiVersion: Int,
    val entrypoint: String,
    val files: Map<String, String>,
)

internal class MonetExtractionLimiter(
    private val aggregateLimit: Long,
) {
    private var extractedBytes = 0L

    fun copy(
        input: InputStream,
        output: OutputStream,
        name: String,
        entryLimit: Long,
    ) {
        var entryBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val nextEntryBytes = entryBytes + count
            require(nextEntryBytes <= entryLimit) {
                "Monet extension entry exceeds size limit: $name"
            }
            val nextExtractedBytes = extractedBytes + count
            require(nextExtractedBytes <= aggregateLimit) {
                "Monet extension exceeds aggregate size limit"
            }
            output.write(buffer, 0, count)
            entryBytes = nextEntryBytes
            extractedBytes = nextExtractedBytes
        }
    }
}

internal object MonetExtensionArchive {

    private const val METADATA_NAME = "extension.json"
    private const val PACK_MANIFEST_NAME = "manifest.json"
    private const val PAYLOAD_DIR_NAME = "payload"
    private const val KIB = 1024L
    private const val MIB = 1024L * KIB
    private const val METADATA_MAX_BYTES = 16L * KIB
    private const val AGGREGATE_MAX_BYTES = 16L * MIB

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256 = Regex("[0-9a-fA-F]{64}")

    private val runtimeLimits = linkedMapOf(
        "classes.dex" to 8L * MIB,
        "payload/customize.sh" to 64L * KIB,
        "payload/update-binary" to 64L * KIB,
        "payload/updater-script" to 64L * KIB,
    )
    private val requiredFiles = runtimeLimits.keys
    private val archiveLimits = runtimeLimits + (METADATA_NAME to METADATA_MAX_BYTES)

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
            var declaredBytes = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                requireSafePath(entry.name)
                require(!entry.isDirectory) { "unexpected Monet extension directory: ${entry.name}" }
                require(names.add(entry.name)) { "duplicate Monet extension entry: ${entry.name}" }
                val limit = archiveLimits[entry.name]
                    ?: throw IllegalArgumentException("unexpected Monet extension entry: ${entry.name}")
                if (entry.size >= 0) {
                    require(entry.size <= limit) {
                        "Monet extension entry exceeds size limit: ${entry.name}"
                    }
                    require(entry.size <= AGGREGATE_MAX_BYTES - declaredBytes) {
                        "Monet extension exceeds aggregate size limit"
                    }
                    declaredBytes += entry.size
                }
                entries += entry
            }
            require(names == archiveLimits.keys) {
                "Monet extension archive entries do not match the fixed runtime layout"
            }

            val limiter = MonetExtractionLimiter(AGGREGATE_MAX_BYTES)
            val metadataEntry = entries.single { it.name == METADATA_NAME }
            val metadataBytes = ByteArrayOutputStream().use { output ->
                zip.getInputStream(metadataEntry).use { input ->
                    limiter.copy(input, output, METADATA_NAME, METADATA_MAX_BYTES)
                }
                output.toByteArray()
            }
            val metadata = decodeAndValidateMetadata(
                metadataBytes,
                expectedApiVersion,
                expectedEntrypoint,
            )

            entries.filterNot { it.name == METADATA_NAME }.forEach { entry ->
                val destination = containedDestination(stagingDir, stagingCanonical, entry.name)
                val parent = destination.parentFile!!
                require(parent.mkdirs() || parent.isDirectory) {
                    "cannot create Monet extension directory: $parent"
                }
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output ->
                        limiter.copy(input, output, entry.name, runtimeLimits.getValue(entry.name))
                    }
                }
            }

            val installedMetadata = containedDestination(stagingDir, stagingCanonical, METADATA_NAME)
            installedMetadata.writeBytes(metadataBytes)
            verifyRuntimeDirectory(stagingDir, metadata)
            metadata
        }
    }

    fun verifyInstalled(
        installedDir: File,
        expectedApiVersion: Int,
        expectedEntrypoint: String,
    ): MonetExtensionMetadata {
        require(installedDir.isDirectory) {
            "missing Monet extension directory: $installedDir"
        }
        val installedCanonical = installedDir.canonicalPath
        val metadataFile = containedDestination(installedDir, installedCanonical, METADATA_NAME)
        requireRegularFile(metadataFile, METADATA_NAME)
        val metadataBytes = ByteArrayOutputStream().use { output ->
            val limiter = MonetExtractionLimiter(METADATA_MAX_BYTES)
            metadataFile.inputStream().use { input ->
                limiter.copy(input, output, METADATA_NAME, METADATA_MAX_BYTES)
            }
            output.toByteArray()
        }
        val metadata = decodeAndValidateMetadata(
            metadataBytes,
            expectedApiVersion,
            expectedEntrypoint,
        )
        verifyRuntimeDirectory(installedDir, metadata)
        return metadata
    }

    private fun decodeAndValidateMetadata(
        bytes: ByteArray,
        expectedApiVersion: Int,
        expectedEntrypoint: String,
    ): MonetExtensionMetadata {
        val metadata = json.decodeFromString(
            MonetExtensionMetadata.serializer(),
            bytes.decodeToString(),
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
        return metadata
    }

    private fun verifyRuntimeDirectory(
        directory: File,
        metadata: MonetExtensionMetadata,
    ) {
        val canonical = directory.canonicalPath
        val rootEntries = directory.listFiles()
            ?: throw IllegalArgumentException("cannot list Monet extension directory: $directory")
        val allowedRootNames = setOf(METADATA_NAME, PACK_MANIFEST_NAME, PAYLOAD_DIR_NAME, "classes.dex")
        require(rootEntries.mapTo(mutableSetOf()) { it.name } in setOf(
            allowedRootNames,
            allowedRootNames - PACK_MANIFEST_NAME,
        )) { "Monet extension installed layout mismatch" }

        val payloadDir = containedDestination(directory, canonical, PAYLOAD_DIR_NAME)
        require(Files.isDirectory(payloadDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "missing Monet extension payload directory"
        }
        val expectedPayloadNames = requiredFiles
            .filter { it.startsWith("$PAYLOAD_DIR_NAME/") }
            .mapTo(mutableSetOf()) { it.substringAfter('/') }
        val payloadEntries = payloadDir.listFiles()
            ?: throw IllegalArgumentException("cannot list Monet extension payload directory")
        require(payloadEntries.mapTo(mutableSetOf()) { it.name } == expectedPayloadNames) {
            "Monet extension payload layout mismatch"
        }

        var aggregateBytes = requireFileWithinLimit(
            containedDestination(directory, canonical, METADATA_NAME),
            METADATA_NAME,
            METADATA_MAX_BYTES,
        )
        val packManifest = containedDestination(directory, canonical, PACK_MANIFEST_NAME)
        if (packManifest.exists()) requireRegularFile(packManifest, PACK_MANIFEST_NAME)
        for ((name, limit) in runtimeLimits) {
            val file = containedDestination(directory, canonical, name)
            aggregateBytes += requireFileWithinLimit(file, name, limit)
            require(aggregateBytes <= AGGREGATE_MAX_BYTES) {
                "Monet extension exceeds aggregate size limit"
            }
            require(PackFs.verify(file, metadata.files.getValue(name))) {
                "Monet extension SHA-256 mismatch for $name"
            }
        }
    }

    private fun requireFileWithinLimit(file: File, name: String, limit: Long): Long {
        requireRegularFile(file, name)
        val size = file.length()
        require(size <= limit) { "Monet extension entry exceeds size limit: $name" }
        return size
    }

    private fun requireRegularFile(file: File, name: String) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "missing Monet extension file: $name"
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

    private fun containedDestination(directory: File, canonical: String, name: String): File {
        val destination = File(directory, name)
        require(destination.canonicalPath.startsWith(canonical + File.separator)) {
            "unsafe Monet extension path: $name"
        }
        return destination
    }
}
