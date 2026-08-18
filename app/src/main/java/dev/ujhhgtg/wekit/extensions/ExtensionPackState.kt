package dev.ujhhgtg.wekit.extensions

sealed interface ExtensionPackState {
    data object NotInstalled : ExtensionPackState
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val bytesTotal: Long) : ExtensionPackState
    data object Verifying : ExtensionPackState
    data class Installed(val version: String) : ExtensionPackState
    data class UpdateAvailable(val installedVersion: String, val latestVersion: String) : ExtensionPackState
    data class Failed(val reason: String) : ExtensionPackState
}

/**
 * Pure classification so the state machine is unit-testable without Android.
 * A null [PackIndexEntry] means the remote index is unknown (not fetched yet or
 * the fetch failed); the comparison strips the legacy `YYYYMMDD-` date prefix so
 * installs from the old pinned scheme do not re-download identical content.
 */
fun classifyPackState(installed: PackManifest?, latest: PackIndexEntry?): ExtensionPackState = when {
    installed == null -> ExtensionPackState.NotInstalled
    latest == null -> ExtensionPackState.Installed(installed.version)
    contentId(installed.version) == contentId(latest.version) -> ExtensionPackState.Installed(installed.version)
    else -> ExtensionPackState.UpdateAvailable(installed.version, latest.version)
}

/** The 12-hex content hash inside a version string, without the legacy date prefix. */
private fun contentId(version: String): String = version.substringAfter('-')
