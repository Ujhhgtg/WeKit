package dev.ujhhgtg.wekit.extensions

import kotlin.io.path.moveTo
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.nio.file.StandardCopyOption

/** Shared file plumbing for extension packs: hashing, verification, atomic publish. */
object PackFs {

    private val json = Json { ignoreUnknownKeys = true }
    private const val MANIFEST_NAME = "manifest.json"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expected: String): Boolean =
        sha256(file).equals(expected, ignoreCase = true)

    fun atomicReplace(tmp: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.toPath().moveTo(dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun writeManifest(dir: File, manifest: PackManifest) {
        dir.resolve(MANIFEST_NAME).writeText(json.encodeToString(PackManifest.serializer(), manifest))
    }

    fun readManifest(dir: File): PackManifest? {
        val file = dir.resolve(MANIFEST_NAME)
        if (!file.isFile) return null
        return json.decodeFromString(PackManifest.serializer(), file.readText())
    }

    fun decodeIndex(text: String): PackIndex = json.decodeFromString(PackIndex.serializer(), text)
}
