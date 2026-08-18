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
)
