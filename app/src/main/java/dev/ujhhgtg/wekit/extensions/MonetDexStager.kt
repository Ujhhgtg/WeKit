package dev.ujhhgtg.wekit.extensions

import java.io.File

internal fun stageReadOnlyMonetDex(
    source: File,
    target: File,
    expectedSha256: String,
): File {
    if (target.isFile && !target.canWrite() && PackFs.verify(target, expectedSha256)) {
        return target
    }
    target.delete()
    val parent = target.parentFile!!
    require(parent.mkdirs() || parent.isDirectory) {
        "cannot create Monet generator code cache: $parent"
    }
    try {
        target.outputStream().use { output ->
            require(target.setReadOnly()) {
                "cannot make Monet generator DEX read-only"
            }
            source.inputStream().use { input -> input.copyTo(output) }
        }
        require(!target.canWrite()) { "Monet generator DEX remains writable" }
        require(PackFs.verify(target, expectedSha256)) {
            "Monet generator code-cache DEX SHA-256 mismatch"
        }
        return target
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}
