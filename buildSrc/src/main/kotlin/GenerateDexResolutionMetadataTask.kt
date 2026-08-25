import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

internal data class DexResolutionSafetySource(
    val relativePath: String,
    val sourceText: String,
)

abstract class GenerateDexResolutionMetadataTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputFile
    abstract val ownerInventoryFile: RegularFileProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val sourceRoot = sourceDir.get().asFile
        val sourceFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()
        val sourceDirSafetyDigest = dexResolutionSafetyDigest(
            sourceFiles.map { sourceFile ->
                DexResolutionSafetySource(
                    relativePath = sourceFile.relativeTo(sourceRoot).invariantSeparatorsPath,
                    sourceText = sourceFile.readText(),
                )
            },
        )
        val discoveredOwnerClassNames = sourceFiles
            .flatMap(::discoverDexResolverOwnerClassNames)
            .toSet()
        val owners = sourceFiles.asSequence()
            .flatMap(::scanDexResolverSources)
            .sortedBy { it.qualifiedClassName }
            .toList()

        requireCompleteDexResolverMetadata(
            discoveredOwnerClassNames = discoveredOwnerClassNames,
            metadataOwners = owners,
        )
        requireValidDexResolutionMetadata(owners)

        val outputRoot = outputDir.get().asFile
        val namespacePath = namespace.get().replace('.', '/')
        val metadataFile = outputRoot.resolve(
            "$namespacePath/dexkit/resolution/GeneratedDexResolutionMetadata.kt"
        )

        metadataFile.parentFile.mkdirs()
        metadataFile.writeText(
            renderMetadataSource(
                namespace = namespace.get(),
                owners = owners,
                sourceDirSafetyDigest = sourceDirSafetyDigest,
            ),
        )

        outputRoot.resolve("$namespacePath/dexkit/cache/GeneratedMethodHashes.kt").delete()

        val inventoryFile = ownerInventoryFile.get().asFile
        inventoryFile.parentFile.mkdirs()
        inventoryFile.writeText(
            owners.joinToString(separator = "\n", postfix = "\n") { it.qualifiedClassName }
        )
    }
}

internal fun requireValidDexResolutionMetadata(owners: List<DexResolverSource>) {
    val duplicateOwners = owners.groupingBy { it.qualifiedClassName }.eachCount().filterValues { it > 1 }.keys
    require(duplicateOwners.isEmpty()) {
        "Duplicate IResolveDex owner class names: ${duplicateOwners.sorted()}"
    }

    val duplicateStableIds = owners.flatMap { it.producers }
        .groupingBy { it.stableId }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(duplicateStableIds.isEmpty()) {
        "Duplicate Dex producer stable IDs: ${duplicateStableIds.sorted()}"
    }
}

internal fun requireCompleteDexResolverMetadata(
    discoveredOwnerClassNames: Set<String>,
    metadataOwners: List<DexResolverSource>,
) {
    val metadataOwnerClassNames = metadataOwners.mapTo(linkedSetOf()) { it.qualifiedClassName }
    val missing = discoveredOwnerClassNames - metadataOwnerClassNames
    val unexpected = metadataOwnerClassNames - discoveredOwnerClassNames
    require(missing.isEmpty() && unexpected.isEmpty()) {
        buildString {
            append("Dex resolution metadata owner coverage mismatch.")
            if (missing.isNotEmpty()) append(" Missing: ${missing.sorted()}.")
            if (unexpected.isNotEmpty()) append(" Unexpected: ${unexpected.sorted()}.")
        }
    }
}

private const val FINGERPRINT_SCHEMA_SALT = "wekit-dex-resolution-metadata-v2"

internal fun renderMetadataSource(
    namespace: String,
    owners: List<DexResolverSource>,
    sourceDirSafetyDigest: String,
): String {
    require(sourceDirSafetyDigest.matches(Regex("[0-9a-f]{64}"))) {
        "Dex resolver source-directory safety digest must be lowercase SHA-256."
    }
    val ownerAnnotations = owners.joinToString(",\n") { "            ${it.qualifiedClassName.asKotlinString()}" }
    val ownerEntries = owners.joinToString(",\n") { owner ->
        val producerEntries = owner.producers.joinToString(",\n") { producer ->
            val localFingerprint = if (producer.usesSourceDirSafetyFingerprint) {
                fingerprint(
                    stableId = producer.stableId,
                    kind = "${producer.kind.name}_SOURCE_DIR_SAFETY",
                    source = sourceDirSafetyDigest,
                )
            } else {
                fingerprint(producer.stableId, producer.kind.name, producer.fingerprintSource)
            }
            """
                    ${producer.stableId.asKotlinString()} to DexProducerMetadata(
                        stableId = ${producer.stableId.asKotlinString()},
                        ownerClassName = ${owner.qualifiedClassName.asKotlinString()},
                        propertyName = ${producer.propertyName?.asKotlinString() ?: "null"},
                        kind = DexProducerKind.${producer.kind.name},
                        localFingerprint = ${localFingerprint.asKotlinString()},
                        usesSourceDirSafetyFingerprint = ${producer.usesSourceDirSafetyFingerprint},
                    )
            """.trimIndent()
        }
        val producers = if (producerEntries.isEmpty()) "emptyMap()" else "mapOf(\n$producerEntries\n                )"
        val customOutputs = owner.customOutputPropertyNames.sorted()
            .joinToString(prefix = "setOf(", postfix = ")") { it.asKotlinString() }
            .let { if (owner.customOutputPropertyNames.isEmpty()) "emptySet()" else it }

        """
            ${owner.qualifiedClassName.asKotlinString()} to DexOwnerMetadata(
                ownerClassName = ${owner.qualifiedClassName.asKotlinString()},
                producers = $producers,
                customOutputPropertyNames = $customOutputs,
            )
        """.trimIndent()
    }
    val ownersMap = if (ownerEntries.isEmpty()) "emptyMap()" else "mapOf(\n$ownerEntries\n    )"

    return """
        package $namespace.dexkit.resolution

        @DexResolutionMetadataOwners(
$ownerAnnotations,
        )
        object GeneratedDexResolutionMetadata {
            const val SOURCE_DIR_SAFETY_DIGEST: String = ${sourceDirSafetyDigest.asKotlinString()}
            val OWNERS: Map<String, DexOwnerMetadata> = $ownersMap
        }
    """.trimIndent() + "\n"
}

internal fun dexResolutionSafetyDigest(sources: List<DexResolutionSafetySource>): String {
    val duplicatePaths = sources.groupingBy { normalizeSafetyPath(it.relativePath) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(duplicatePaths.isEmpty()) {
        "Duplicate Dex resolver safety-source paths: ${duplicatePaths.sorted()}"
    }

    return sha256(
        buildString {
            append("wekit-dex-resolution-source-dir-safety-v1\n")
            sources.sortedBy { normalizeSafetyPath(it.relativePath) }.forEach { source ->
                val relativePath = normalizeSafetyPath(source.relativePath)
                val normalizedSource = normalizeSafetySource(source.sourceText)
                appendLengthPrefixed(relativePath)
                appendLengthPrefixed(normalizedSource)
            }
        },
    )
}

private fun normalizeSafetyPath(path: String): String = path.replace('\\', '/')

private fun normalizeSafetySource(source: String): String =
    source.replace("\r\n", "\n").replace('\r', '\n')

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.toByteArray(Charsets.UTF_8).size)
    append(':')
    append(value)
    append('\n')
}

private fun fingerprint(stableId: String, kind: String, source: String): String =
    sha256(
        listOf(
            FINGERPRINT_SCHEMA_SALT,
            stableId,
            kind,
            normalizeFingerprintSource(source),
        ).joinToString("\n"),
    )

private fun normalizeFingerprintSource(source: String): String =
    source.replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()

private fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun String.asKotlinString(): String = buildString {
    append('"')
    this@asKotlinString.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(char)
        }
    }
    append('"')
}
