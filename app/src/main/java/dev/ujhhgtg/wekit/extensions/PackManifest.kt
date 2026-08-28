package dev.ujhhgtg.wekit.extensions

import kotlinx.serialization.Serializable

/** Written next to the installed payload; describes what this install directory holds. */
@Serializable
data class PackManifest(
    val id: String,
    val version: String,
    val sha256: String,
    val installedAtEpochMs: Long,
    /** Pack-type-specific metadata carried from the index entry (e.g. local-model meta). */
    val meta: String? = null,
)
