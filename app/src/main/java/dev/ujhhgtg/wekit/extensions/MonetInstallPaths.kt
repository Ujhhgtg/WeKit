package dev.ujhhgtg.wekit.extensions

import java.io.File

internal data class MonetInstallPathSet(
    val baseDir: File,
    val destination: File,
    val staging: File,
)

internal object MonetInstallPaths {

    fun resolve(baseDir: File, version: String): MonetInstallPathSet {
        val canonicalBase = baseDir
        return MonetInstallPathSet(
            baseDir = canonicalBase,
            destination = File(canonicalBase, version),
            staging = File(canonicalBase, ".$version-installing"),
        )
    }
}
