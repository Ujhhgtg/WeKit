import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

abstract class GenerateDexResolutionMetadataTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val owners = sourceDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull(::scanDexResolverSource)
            .sortedBy { it.qualifiedClassName }
            .toList()

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

        val outputRoot = outputDir.get().asFile
        val namespacePath = namespace.get().replace('.', '/')
        val metadataFile = outputRoot.resolve(
            "$namespacePath/dexkit/resolution/GeneratedDexResolutionMetadata.kt"
        )
        val compatibilityFile = outputRoot.resolve("$namespacePath/dexkit/cache/GeneratedMethodHashes.kt")

        metadataFile.parentFile.mkdirs()
        metadataFile.writeText(renderMetadataSource(namespace.get(), owners))

        compatibilityFile.parentFile.mkdirs()
        compatibilityFile.writeText(
            """
            package ${namespace.get()}.dexkit.cache

            import ${namespace.get()}.dexkit.resolution.GeneratedDexResolutionMetadata

            object GeneratedMethodHashes {
                val HASHES: Map<String, String> = GeneratedDexResolutionMetadata.LEGACY_OWNER_HASHES
            }
            """.trimIndent() + "\n",
        )
    }
}

private const val FINGERPRINT_SCHEMA_SALT = "wekit-dex-resolution-metadata-v2"

private fun renderMetadataSource(namespace: String, owners: List<DexResolverSource>): String {
    val ownerEntries = owners.joinToString(",\n") { owner ->
        val ownerSafetyFingerprint = fingerprint(
            stableId = owner.qualifiedClassName,
            kind = "OWNER_SAFETY",
            source = owner.ownerSafetySource,
        )
        val producerEntries = owner.producers.joinToString(",\n") { producer ->
            val localFingerprint = if (producer.usesOwnerSafetyFingerprint) {
                ownerSafetyFingerprint
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
                        usesOwnerSafetyFingerprint = ${producer.usesOwnerSafetyFingerprint},
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
                ownerSafetyFingerprint = ${ownerSafetyFingerprint.asKotlinString()},
                producers = $producers,
                customOutputPropertyNames = $customOutputs,
            )
        """.trimIndent()
    }
    val ownersMap = if (ownerEntries.isEmpty()) "emptyMap()" else "mapOf(\n$ownerEntries\n    )"

    return """
        package $namespace.dexkit.resolution

        object GeneratedDexResolutionMetadata {
            val OWNERS: Map<String, DexOwnerMetadata> = $ownersMap

            val LEGACY_OWNER_HASHES: Map<String, String> = OWNERS.mapValues { (_, owner) ->
                owner.ownerSafetyFingerprint
            }
        }
    """.trimIndent() + "\n"
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
