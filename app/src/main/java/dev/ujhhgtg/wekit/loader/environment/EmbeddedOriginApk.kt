package dev.ujhhgtg.wekit.loader.environment

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object EmbeddedOriginApk {
    data class Source(val assetPath: String, val file: File)

    private val assetPaths =
        listOf(
            "assets/lspatch/origin.apk",
            "assets/npatch/origin.apk",
            "assets/origin.apk",
        )

    fun findAndExtract(hostApk: File, cacheDirectory: File): Source? =
        ZipFile(hostApk).use { host ->
            val entry = assetPaths.firstNotNullOfOrNull(host::getEntry) ?: return@use null
            if (entry.isDirectory || entry.size < 0 || entry.crc < 0) {
                throw IOException("Invalid embedded APK entry: ${entry.name}")
            }
            val target = File(cacheDirectory, "origin-${entry.crc.toString(16)}-${entry.size}.apk")
            if (!target.isFile || target.length() != entry.size) {
                if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
                    throw IOException("Cannot create embedded APK cache: $cacheDirectory")
                }
                val temporary = File.createTempFile("origin-", ".tmp", cacheDirectory)
                try {
                    val copied =
                        host.getInputStream(entry).use { input ->
                            temporary.outputStream().use { output ->
                                input.copyTo(output, 64 * 1024)
                            }
                        }
                    if (copied != entry.size) {
                        throw IOException(
                            "Incomplete embedded APK: ${entry.name} ($copied/${entry.size})",
                        )
                    }
                    ZipFile(temporary).use {}
                    if (!temporary.renameTo(target)) {
                        throw IOException("Cannot publish embedded APK cache: $target")
                    }
                } finally {
                    temporary.delete()
                }
            } else {
                // verify that the cached apk still opens as a zip; if not, ZipFile throws
                // IOException and propogates it to the upper try-catch
                ZipFile(target).use {}
            }
            Source(entry.name, target)
        }
}
