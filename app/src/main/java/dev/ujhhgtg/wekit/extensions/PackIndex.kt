package dev.ujhhgtg.wekit.extensions

import kotlinx.serialization.Serializable

/**
 * The remotely published index (`manifest.json` Release asset): for every pack,
 * the latest version, its Release asset file name, and the asset's SHA-256.
 * Built and uploaded alongside the assets by `cargo xtask extensions pack`.
 */
@Serializable
data class PackIndex(
    val packs: List<PackIndexEntry>,
)

@Serializable
data class PackIndexEntry(
    val id: String,
    val version: String,
    val asset: String,
    val sha256: String,
    /** Absolute URL of an externally hosted asset (e.g. Hugging Face); null → GitHub Release asset. */
    val externalUrl: String? = null,
    /** Exact asset size in bytes when known; enables HTTP Range resume for partial downloads. */
    val bytes: Long? = null,
    /** Pack-type-specific metadata persisted into the install manifest (e.g. local-model meta). */
    val meta: String? = null,
)
