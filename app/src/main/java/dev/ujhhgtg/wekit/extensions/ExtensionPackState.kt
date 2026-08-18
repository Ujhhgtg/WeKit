package dev.ujhhgtg.wekit.extensions

sealed interface ExtensionPackState {
    data object NotInstalled : ExtensionPackState
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val bytesTotal: Long) : ExtensionPackState
    data object Verifying : ExtensionPackState
    data class Installed(val version: String) : ExtensionPackState
    data class VersionMismatch(val installedVersion: String, val pinnedVersion: String) : ExtensionPackState
    data class Failed(val reason: String) : ExtensionPackState
}

/** Pure classification so the state machine is unit-testable without Android. */
fun classifyPackState(installed: PackManifest?, pinnedVersion: String): ExtensionPackState = when {
    installed == null -> ExtensionPackState.NotInstalled
    installed.version == pinnedVersion -> ExtensionPackState.Installed(installed.version)
    else -> ExtensionPackState.VersionMismatch(installed.version, pinnedVersion)
}
