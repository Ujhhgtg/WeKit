package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionCoordinator
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionRegistry
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.dexkit.resolution.effectiveFingerprint
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.TreeMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DexCacheManager {
    private const val TAG = "DexCacheManager"
    private const val CACHE_DIR_NAME = "dex_cache"
    private const val CACHE_FILE_SUFFIX = ".json"
    private const val KEY_HOST_VERSION = "host_version"

    private val cacheDir: Path by lazy {
        (KnownPaths.moduleData / CACHE_DIR_NAME).createDirsSafe()
    }

    fun init(currentVer: String) {
        val cachedVer = WePrefs.getString(KEY_HOST_VERSION)
        if (cachedVer != currentVer) {
            WeLogger.i(TAG, "host version changed: $cachedVer -> $currentVer, resetting all cache")
            clearAllCache()
            Preferences.noDexResolve = false
            WeLogger.i(TAG, "disabling NO_DEX_RESOLVE due to host version change")
        }
        WePrefs.putString(KEY_HOST_VERSION, currentVer)
    }

    fun restoreValidOwners(registry: DexResolutionRegistry): DexCacheRestoreResult =
        restoreValidOwners(cacheDir, registry.currentCacheOwners())

    fun saveResolvedOwners(
        registry: DexResolutionRegistry,
        coordinator: DexResolutionCoordinator,
        owners: Collection<IResolveDex>,
    ) = saveResolvedOwners(cacheDir, registry, coordinator, owners)

    internal fun importCloudCaches(entries: List<CloudDexCacheEntry>) {
        writeCloudCacheFiles(cacheDir, entries, System.currentTimeMillis())
    }

    fun clearAllCache() {
        cacheDir.listDirectoryEntries().forEach { it.deleteIfExists() }
        WeLogger.i(TAG, "all cache cleared")
    }

    internal fun cacheFileName(technicalId: String): String =
        technicalId.replace("/", "_") + CACHE_FILE_SUFFIX
}

internal fun saveResolvedOwners(
    cacheDir: Path,
    registry: DexResolutionRegistry,
    coordinator: DexResolutionCoordinator,
    owners: Collection<IResolveDex>,
) {
    val requestedOwnerIds = owners.mapTo(linkedSetOf()) { it.javaClass.name }
    val resolvedProducerIds = coordinator.effectiveFingerprintByProducer.keys
    val manifests = registry.currentCacheOwners()
        .filter { current ->
            current.ownerId in requestedOwnerIds || current.delegates.values.any { it.producerId in resolvedProducerIds }
        }
        .mapNotNull { current ->
            val owner = registry.ownersById.getValue(current.ownerId)
            buildResolvedManifest(owner, registry, coordinator)
        }
    writeDexCacheManifests(cacheDir, manifests, registry.technicalIdsByOwner())
}

private val cacheJson = Json {
    encodeDefaults = true
    prettyPrint = true
}

internal fun restoreValidOwners(
    cacheDir: Path,
    owners: List<DexCacheCurrentOwner>,
): DexCacheRestoreResult {
    val ownersById = owners.associateByTo(TreeMap()) { it.ownerId }
    val delegatesById = owners.flatMap { it.delegates.values }.associateByTo(TreeMap()) { it.id }
    val producerOwner = owners.flatMap { owner ->
        owner.delegates.values.map { it.producerId to owner.ownerId }
    }.toMap()
    val manifests = mutableMapOf<String, DexCacheManifest>()
    val invalid = TreeMap<String, DexCacheValidation.Invalid>()

    owners.forEach { owner ->
        val file = cacheDir.resolve(DexCacheManager.cacheFileName(owner.technicalId))
        when (val parsed = parseManifest(file, owner.ownerId)) {
            is ParsedManifest.Success -> manifests[owner.ownerId] = parsed.manifest
            is ParsedManifest.Failure -> invalid[owner.ownerId] = parsed.invalid
        }
    }

    owners.forEach ownerLoop@{ owner ->
        if (owner.ownerId in invalid) return@ownerLoop
        val manifest = manifests.getValue(owner.ownerId)
        val missing = owner.delegates.keys - manifest.delegates.keys
        if (missing.isNotEmpty()) {
            invalid[owner.ownerId] = invalid(
                DexCacheInvalidReason.MISSING_CURRENT_DELEGATE,
                "missing current delegates: ${missing.sorted()}",
            )
            return@ownerLoop
        }
        val extra = manifest.delegates.keys - owner.delegates.keys
        if (extra.isNotEmpty()) {
            invalid[owner.ownerId] = invalid(
                DexCacheInvalidReason.EXTRA_CURRENT_DELEGATE,
                "extra cached delegates: ${extra.sorted()}",
            )
            return@ownerLoop
        }
        for ((delegateId, current) in owner.delegates.toSortedMap()) {
            val entry = manifest.delegates.getValue(delegateId)
            val entryError = validateEntry(current, entry, delegatesById)
            if (entryError != null) {
                invalid[owner.ownerId] = entryError
                return@ownerLoop
            }
        }
        owner.delegates.values.groupBy { it.producerId }.forEach { (producerId, delegates) ->
            val snapshots = delegates.map { manifest.delegates.getValue(it.id).producerSnapshot() }.distinct()
            if (snapshots.size != 1) {
                invalid[owner.ownerId] = invalid(
                    DexCacheInvalidReason.MALFORMED_CACHE,
                    "inconsistent cached producer metadata: $producerId",
                )
                return@ownerLoop
            }
        }
    }

    val producerState = mutableMapOf<String, VisitState>()
    val computed = mutableMapOf<String, String>()
    fun compute(producerId: String, path: List<String>): DexCacheValidation.Invalid? {
        val ownerId = producerOwner[producerId]
            ?: return invalid(DexCacheInvalidReason.MISSING_DEPENDENCY, "unknown dependency: $producerId")
        invalid[ownerId]?.let { return invalid(DexCacheInvalidReason.MISSING_DEPENDENCY, "$producerId belongs to invalid owner $ownerId") }
        when (producerState[producerId]) {
            VisitState.VALIDATED -> return null
            VisitState.VISITING -> return invalid(
                DexCacheInvalidReason.DEPENDENCY_CYCLE,
                "cache dependency cycle: ${(path + producerId).joinToString(" -> ")}",
            )
            null -> Unit
        }
        producerState[producerId] = VisitState.VISITING
        val owner = ownersById.getValue(ownerId)
        val currentOutputs = owner.delegates.values.filter { it.producerId == producerId }
        val entry = manifests.getValue(ownerId).delegates.getValue(currentOutputs.first().id)
        val dependencyFingerprints = TreeMap<String, String>()
        entry.dependencies.sorted().forEach { dependencyId ->
            compute(dependencyId, path + producerId)?.let { return it }
            dependencyFingerprints[dependencyId] = computed.getValue(dependencyId)
        }
        val expected = effectiveFingerprint(
            dev.ujhhgtg.wekit.dexkit.resolution.DexProducerMetadata(
                stableId = producerId,
                ownerClassName = ownerId,
                propertyName = null,
                kind = dev.ujhhgtg.wekit.dexkit.resolution.DexProducerKind.CUSTOM,
                localFingerprint = currentOutputs.first().producerFingerprint,
            ),
            dependencyFingerprints,
        )
        if (entry.effectiveFingerprint != expected) {
            return invalid(
                DexCacheInvalidReason.EFFECTIVE_FINGERPRINT_MISMATCH,
                "$producerId cached=${entry.effectiveFingerprint} current=$expected",
            )
        }
        computed[producerId] = expected
        producerState[producerId] = VisitState.VALIDATED
        return null
    }

    owners.forEach ownerLoop@{ owner ->
        if (owner.ownerId in invalid) return@ownerLoop
        owner.delegates.values.map { it.producerId }.distinct().sorted().forEach { producerId ->
            val failure = compute(producerId, emptyList())
            if (failure != null) {
                invalid[owner.ownerId] = failure
                return@ownerLoop
            }
        }
    }

    var changed: Boolean
    do {
        changed = false
        owners.forEach ownerLoop@{ owner ->
            if (owner.ownerId in invalid) return@ownerLoop
            val badDependency = manifests.getValue(owner.ownerId).delegates.values
                .flatMap { it.dependencies }
                .firstOrNull { dependency -> producerOwner[dependency] in invalid }
            if (badDependency != null) {
                invalid[owner.ownerId] = invalid(
                    DexCacheInvalidReason.MISSING_DEPENDENCY,
                    "dependency owner is invalid: $badDependency",
                )
                changed = true
            }
        }
    } while (changed)

    val validOwners = (ownersById.keys - invalid.keys).toSortedSet()
    validOwners.forEach { ownerId ->
        val current = ownersById.getValue(ownerId)
        val manifest = manifests.getValue(ownerId)
        current.delegates.toSortedMap().forEach { (delegateId, delegate) ->
            val entry = manifest.delegates.getValue(delegateId)
            delegate.loadDescriptor(entry.descriptor, entry.status)
        }
    }
    return DexCacheRestoreResult(validOwners, invalid)
}

private fun validateEntry(
    current: DexCacheCurrentDelegate,
    entry: DexCacheDelegateEntry,
    delegatesById: Map<String, DexCacheCurrentDelegate>,
): DexCacheValidation.Invalid? {
    if (entry.descriptor.isBlank() || entry.descriptor == "null" || !current.isValidDescriptor(entry.descriptor)) {
        return invalid(DexCacheInvalidReason.INVALID_DESCRIPTOR, "invalid descriptor: ${current.id}")
    }
    val descriptorIsPlaceholder = current.isPlaceholderDescriptor(entry.descriptor)
    val validStatus = entry.status == DexResolutionStatus.SUCCESS ||
        entry.status == DexResolutionStatus.EXPECTED_FAILURE
    val validPlaceholder = when (entry.status) {
        DexResolutionStatus.SUCCESS -> !entry.isPlaceholder && !descriptorIsPlaceholder
        DexResolutionStatus.EXPECTED_FAILURE -> entry.isPlaceholder && descriptorIsPlaceholder
        else -> false
    }
    if (!validStatus || !validPlaceholder) {
        return invalid(
            DexCacheInvalidReason.INVALID_PLACEHOLDER_CLASSIFICATION,
            "invalid status/placeholder classification: ${current.id}",
        )
    }
    if (entry.producerFingerprint != current.producerFingerprint) {
        return invalid(
            DexCacheInvalidReason.LOCAL_FINGERPRINT_MISMATCH,
            "${current.producerId} cached=${entry.producerFingerprint} current=${current.producerFingerprint}",
        )
    }
    if (entry.dependencies.size != entry.dependencies.distinct().size ||
        entry.dependencies.any { it !in delegatesById.values.map(DexCacheCurrentDelegate::producerId) }
    ) {
        return invalid(
            DexCacheInvalidReason.MISSING_DEPENDENCY,
            "invalid dependencies for ${current.producerId}: ${entry.dependencies}",
        )
    }
    return null
}

private fun buildResolvedManifest(
    owner: BaseFeature,
    registry: DexResolutionRegistry,
    coordinator: DexResolutionCoordinator,
): DexCacheManifest? {
    val delegates = TreeMap<String, DexCacheDelegateEntry>()
    for (delegate in owner.dexDelegates.sortedBy { it.stableId }) {
        val status = delegate.diagnostic.status
        val descriptor = delegate.getDescriptorString()
        val producer = registry.producerOf(delegate)
        val effective = coordinator.effectiveFingerprintByProducer[producer.stableId]
        val valid = descriptor != null && delegate.isValidDescriptor(descriptor) &&
            effective != null &&
            (status == DexResolutionStatus.SUCCESS && !delegate.isPlaceholder ||
                status == DexResolutionStatus.EXPECTED_FAILURE && delegate.isPlaceholder)
        if (!valid) return null
        delegates[delegate.stableId] = DexCacheDelegateEntry(
            descriptor = descriptor,
            status = status,
            isPlaceholder = delegate.isPlaceholder,
            producerFingerprint = producer.metadata.localFingerprint,
            effectiveFingerprint = effective,
            dependencies = coordinator.dependenciesOf(producer.stableId).sorted(),
        )
    }
    return DexCacheManifest(
        owner = owner.javaClass.name,
        timestamp = System.currentTimeMillis(),
        delegates = delegates,
    )
}

internal fun writeCloudCacheFiles(
    cacheDir: Path,
    entries: List<CloudDexCacheEntry>,
    timestamp: Long,
) {
    val manifests = entries.map { it.manifest.copy(timestamp = timestamp) }
    writeDexCacheManifests(cacheDir, manifests, entries.associate { it.manifest.owner to it.technicalId })
}

internal fun writeDexCacheManifests(
    cacheDir: Path,
    manifests: List<DexCacheManifest>,
    technicalIdsByOwner: Map<String, String>,
    move: (Path, Path) -> Unit = ::moveReplacing,
) {
    if (manifests.isEmpty()) return
    require(manifests.map(DexCacheManifest::owner).distinct().size == manifests.size) {
        "duplicate cache owner ID"
    }
    Files.createDirectories(cacheDir)
    val transactionId = "${System.currentTimeMillis()}-${System.nanoTime()}"
    val staged = mutableListOf<CacheStagedFile>()
    val committed = mutableSetOf<Path>()
    val preservedBackups = mutableSetOf<Path>()
    var primaryFailure: Exception? = null
    try {
        manifests.sortedBy { it.owner }.forEach { manifest ->
            val technicalId = requireNotNull(technicalIdsByOwner[manifest.owner])
            val destination = cacheDir.resolve(DexCacheManager.cacheFileName(technicalId))
            val temp = destination.resolveSibling(".${destination.fileName}.$transactionId.tmp")
            val backup = destination.resolveSibling(".${destination.fileName}.$transactionId.bak")
            staged += CacheStagedFile(destination, temp, backup)
            temp.writeText(cacheJson.encodeToString(manifest.stable()))
        }
        staged.forEach { file ->
            if (file.destination.exists()) move(file.destination, file.backup)
            move(file.temp, file.destination)
            committed.add(file.destination)
        }
    } catch (error: Exception) {
        primaryFailure = error
        staged.asReversed().forEach { file ->
            if (file.backup.exists()) {
                try {
                    move(file.backup, file.destination)
                } catch (rollbackError: Exception) {
                    preservedBackups.add(file.backup)
                    error.addSuppressed(rollbackError)
                }
            } else if (file.destination in committed) {
                try {
                    file.destination.deleteIfExists()
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
        }
        throw error
    } finally {
        staged.forEach { file ->
            runCatching { file.temp.deleteIfExists() }
                .exceptionOrNull()
                ?.let { primaryFailure?.addSuppressed(it) }
            if (file.backup !in preservedBackups) {
                runCatching { file.backup.deleteIfExists() }
                    .exceptionOrNull()
                    ?.let { primaryFailure?.addSuppressed(it) }
            }
        }
    }
}

private fun DexCacheManifest.stable() = copy(
    delegates = delegates.toSortedMap().mapValues { (_, entry) ->
        entry.copy(dependencies = entry.dependencies.sorted())
    },
)

private fun DexCacheDelegateEntry.producerSnapshot() =
    Triple(producerFingerprint, effectiveFingerprint, dependencies.sorted())

private sealed interface ParsedManifest {
    data class Success(val manifest: DexCacheManifest) : ParsedManifest
    data class Failure(val invalid: DexCacheValidation.Invalid) : ParsedManifest
}

private fun parseManifest(file: Path, expectedOwner: String): ParsedManifest {
    if (!file.exists()) return ParsedManifest.Failure(
        invalid(DexCacheInvalidReason.MISSING_FILE, "cache file is missing: ${file.fileName}"),
    )
    val text = try {
        file.readText()
    } catch (error: Exception) {
        return ParsedManifest.Failure(invalid(DexCacheInvalidReason.MALFORMED_CACHE, error.message.orEmpty()))
    }
    try {
        requireNoDuplicateJsonKeys(text)
        val root = Json.parseToJsonElement(text).jsonObject
        val schema = root["schema"]?.jsonPrimitive?.content?.toIntOrNull()
        if (schema != 2) {
            return ParsedManifest.Failure(invalid(DexCacheInvalidReason.STALE_SCHEMA, "schema=$schema"))
        }
        val manifest = cacheJson.decodeFromString<DexCacheManifest>(text)
        if (manifest.owner != expectedOwner) {
            return ParsedManifest.Failure(
                invalid(DexCacheInvalidReason.OWNER_MISMATCH, "cached=${manifest.owner} current=$expectedOwner"),
            )
        }
        return ParsedManifest.Success(manifest)
    } catch (error: Exception) {
        return ParsedManifest.Failure(invalid(DexCacheInvalidReason.MALFORMED_CACHE, error.message.orEmpty()))
    }
}

private fun DexResolutionRegistry.currentCacheOwners(): List<DexCacheCurrentOwner> =
    ownersById.values.sortedBy { it.javaClass.name }.map { owner ->
        DexCacheCurrentOwner(
            ownerId = owner.javaClass.name,
            technicalId = owner.technicalId,
            delegates = owner.dexDelegates.associate { delegate ->
                val producer = producerOf(delegate)
                delegate.stableId to DexCacheCurrentDelegate(
                    id = delegate.stableId,
                    producerId = producer.stableId,
                    producerFingerprint = producer.metadata.localFingerprint,
                    isValidDescriptor = delegate::isValidDescriptor,
                    isPlaceholderDescriptor = delegate::isPlaceholderDescriptor,
                    loadDescriptor = delegate::loadCachedDescriptor,
                )
            },
        )
    }

private fun DexResolutionRegistry.technicalIdsByOwner(): Map<String, String> =
    ownersById.mapValues { (_, owner) -> owner.technicalId }

private data class CacheStagedFile(val destination: Path, val temp: Path, val backup: Path)
private enum class VisitState { VISITING, VALIDATED }

private fun invalid(reason: DexCacheInvalidReason, detail: String) =
    DexCacheValidation.Invalid(reason, detail)

private fun moveReplacing(source: Path, destination: Path) {
    try {
        Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, REPLACE_EXISTING)
    }
}

/** Minimal strict JSON scan used to reject duplicate object keys before deserialization. */
internal fun requireNoDuplicateJsonKeys(text: String) {
    class Parser {
        var index = 0
        fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        fun string(): String {
            require(text[index++] == '"')
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result.toString()
                if (char == '\\') {
                    require(index < text.length)
                    val escaped = text[index++]
                    when (escaped) {
                        'u' -> {
                            require(index + 4 <= text.length)
                            result.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        else -> error("invalid JSON escape: \\$escaped")
                    }
                } else {
                    require(char >= ' ') { "unescaped control character in JSON string" }
                    result.append(char)
                }
            }
            error("unterminated JSON string")
        }
        fun value(depth: Int) {
            require(depth <= MAX_STRICT_JSON_NESTING) {
                "JSON nesting exceeds $MAX_STRICT_JSON_NESTING"
            }
            whitespace()
            when (text.getOrNull(index)) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                null -> error("missing JSON value")
                else -> while (index < text.length && text[index] !in charArrayOf(',', '}', ']')) index++
            }
            whitespace()
        }
        fun objectValue(depth: Int) {
            index++
            val keys = mutableSetOf<String>()
            whitespace()
            if (text.getOrNull(index) == '}') { index++; return }
            while (true) {
                whitespace()
                val key = string()
                require(keys.add(key)) { "duplicate JSON key: $key" }
                whitespace(); require(text[index++] == ':'); value(depth + 1); whitespace()
                when (text[index++]) {
                    '}' -> return
                    ',' -> Unit
                    else -> error("invalid JSON object")
                }
            }
        }
        fun arrayValue(depth: Int) {
            index++
            whitespace()
            if (text.getOrNull(index) == ']') { index++; return }
            while (true) {
                value(depth + 1); whitespace()
                when (text[index++]) {
                    ']' -> return
                    ',' -> Unit
                    else -> error("invalid JSON array")
                }
            }
        }
    }
    try {
        Parser().apply {
            value(0)
            whitespace()
            require(index == text.length) { "trailing JSON data" }
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("malformed JSON", error)
    } catch (error: StackOverflowError) {
        throw IllegalArgumentException("JSON nesting exceeds parser capacity", error)
    }
}

private const val MAX_STRICT_JSON_NESTING = 128
