package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/**
 * One downloadable extension pack. Deliberately NOT a strategy abstraction over
 * "pack types": each pack declares its own metadata and owns its install/mount
 * logic; only the generic plumbing (index, download, verify, state, storage) is
 * shared in [ExtensionPacks].
 */
interface ExtensionPack {
    val id: String
    val displayOrder: Int

    /** UI metadata: display name resource shown on the management screen and in dialogs. */
    val nameRes: Int

    /** UI metadata: short description resource shown under the pack name. */
    val descriptionRes: Int

    /** UI metadata: leading icon on the management screen. */
    val icon: ImageVector

    /** Directory holding versioned install payloads (manifest.json + payload). */
    fun installDir(): File

    /** Directory for in-flight downloads. */
    fun stagingDir(): File

    /** Manifest of the newest installed payload, or null when nothing is installed. */
    fun installedManifest(): PackManifest? =
        installDir().listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { PackFs.readManifest(it) }
            .maxByOrNull { it.installedAtEpochMs }

    fun isInstalled(): Boolean = installedManifest() != null

    /** True while the pack's payload is loaded/active — deletion is refused then. */
    fun isInUse(): Boolean

    /** Whether this pack is offered at all on the current device (e.g. ABI gate). */
    fun isSupported(): Boolean = true

    /** Remove version directories other than [keep] (staging excluded). */
    fun sweepOtherVersions(keep: String) {
        installDir().listFiles()
            ?.filter { it.isDirectory && it.name != keep && !it.name.startsWith(".") }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Installs the already-SHA-256-verified temp file under [version]. [meta] is
     * the index entry's pack-specific metadata, when the remote index carries any.
     */
    fun install(verifiedTmp: File, version: String, sha256: String, meta: String? = null)

    /** Hook fired by [ExtensionPacks] after a successful install. */
    fun onInstalled() {}

    /** Hook fired by [ExtensionPacks] after a successful delete. */
    fun onRemoved() {}
}
