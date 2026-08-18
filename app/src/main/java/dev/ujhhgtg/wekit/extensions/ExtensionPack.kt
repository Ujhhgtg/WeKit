package dev.ujhhgtg.wekit.extensions

import java.io.File

/**
 * One downloadable extension pack. Deliberately NOT a strategy abstraction over
 * "pack types": each pack declares its own metadata and owns its install/mount
 * logic; only the generic plumbing (download, verify, state, storage) is shared
 * in [ExtensionPacks].
 */
interface ExtensionPack {
    val id: String
    val pinnedVersion: String
    val pinnedSha256: String
    val assetName: String

    /** Directory holding versioned install payloads (manifest.json + payload). */
    fun installDir(): File

    /** Directory for in-flight downloads. */
    fun stagingDir(): File

    fun isInstalled(): Boolean = PackFs.readManifest(installDir().resolve(pinnedVersion))?.version == pinnedVersion

    /** True while the pack's payload is loaded/active — deletion is refused then. */
    fun isInUse(): Boolean

    /** Installs the already-SHA-256-verified temp file. */
    fun install(verifiedTmp: File)
}
