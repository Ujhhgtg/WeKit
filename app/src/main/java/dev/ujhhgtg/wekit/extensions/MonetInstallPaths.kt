package dev.ujhhgtg.wekit.extensions

import java.io.File

internal data class MonetInstallPathSet(
    val baseDir: File,
    val destination: File,
    val staging: File,
    val previous: File,
)

internal object MonetInstallPaths {

    private val contentVersion = Regex("[0-9a-f]{12}")

    fun requireContentVersion(version: String) {
        require(contentVersion.matches(version)) {
            "invalid Monet generator content version: $version"
        }
    }

    fun resolve(baseDir: File, version: String): MonetInstallPathSet {
        requireContentVersion(version)
        val canonicalBase = baseDir.canonicalFile
        return MonetInstallPathSet(
            baseDir = canonicalBase,
            destination = directChild(canonicalBase, version),
            staging = directChild(canonicalBase, ".$version-installing"),
            previous = directChild(canonicalBase, ".$version-previous"),
        )
    }

    private fun directChild(baseDir: File, name: String): File {
        val child = File(baseDir, name).canonicalFile
        require(child.parentFile == baseDir) { "unsafe Monet generator install path: $name" }
        return child
    }
}
