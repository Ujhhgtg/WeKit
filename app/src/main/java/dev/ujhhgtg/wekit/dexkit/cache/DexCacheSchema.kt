package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import kotlinx.serialization.Serializable

@Serializable
data class DexCacheManifest(
    val schema: Int = 2,
    val owner: String,
    val timestamp: Long,
    val delegates: Map<String, DexCacheDelegateEntry>,
)

@Serializable
data class DexCacheDelegateEntry(
    val descriptor: String,
    val status: DexResolutionStatus,
    val isPlaceholder: Boolean,
    val producerFingerprint: String,
    val effectiveFingerprint: String,
    val dependencies: List<String>,
)

enum class DexCacheInvalidReason {
    MISSING_FILE,
    STALE_SCHEMA,
    OWNER_MISMATCH,
    MISSING_CURRENT_DELEGATE,
    EXTRA_CURRENT_DELEGATE,
    INVALID_DESCRIPTOR,
    INVALID_PLACEHOLDER_CLASSIFICATION,
    LOCAL_FINGERPRINT_MISMATCH,
    MISSING_DEPENDENCY,
    EFFECTIVE_FINGERPRINT_MISMATCH,
    DEPENDENCY_CYCLE,
    MALFORMED_CACHE,
}

sealed interface DexCacheValidation {
    data object Valid : DexCacheValidation

    data class Invalid(
        val reason: DexCacheInvalidReason,
        val detail: String,
    ) : DexCacheValidation
}

data class DexCacheRestoreResult(
    val validOwners: Set<String>,
    val invalidOwners: Map<String, DexCacheValidation.Invalid>,
)

internal fun selectRepairOwnerIds(
    selectedOwnerIds: Iterable<String>,
    restore: DexCacheRestoreResult,
): Set<String> = selectedOwnerIds.filterTo(sortedSetOf()) { it in restore.invalidOwners }

internal data class DexCacheCurrentDelegate(
    val id: String,
    val producerId: String,
    val producerFingerprint: String,
    val isValidDescriptor: (String) -> Boolean,
    val isPlaceholderDescriptor: (String) -> Boolean,
    val loadDescriptor: (String, DexResolutionStatus) -> Unit,
)

internal data class DexCacheCurrentOwner(
    val ownerId: String,
    val technicalId: String,
    val delegates: Map<String, DexCacheCurrentDelegate>,
)
