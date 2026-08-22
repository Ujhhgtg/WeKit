package dev.ujhhgtg.wekit.extensions

import android.os.Build
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Memory
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaController
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown when the local inference engine is needed but its pack is not installed. */
class LlamaPackNotInstalledException(message: String) : RuntimeException(message)

/**
 * llama-native 扩展包:arm64 zip,安装时把两个变体都解到 version 目录——
 * libwekit_llama.so(CPU/Vulkan)与 libwekit_llama_opencl.so(额外含 OpenCL),
 * 加载时由 NativeLoader.ensureLlamaLoaded 按需二选一(进程内只会加载一个变体)。
 */
object LlamaNativePack : ExtensionPack {

    override val id = "llama-native"
    override val nameRes = R.string.extensions_pack_llama_native_name
    override val descriptionRes = R.string.extensions_pack_llama_native_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Memory

    private const val ABI = "arm64-v8a"
    private const val LIB = "libwekit_llama.so"
    private const val LIB_OPENCL = "libwekit_llama_opencl.so"

    private val baseDir: File
        get() = File(HostInfo.application.filesDir, "wekit-extensions/$id")

    override fun installDir(): File = baseDir

    override fun stagingDir(): File = File(baseDir, ".staging")

    override fun isSupported(): Boolean = Build.SUPPORTED_ABIS.contains(ABI)

    /** The requested variant's library file, or null when not installed. */
    fun libraryFile(opencl: Boolean): File? {
        val manifest = installedManifest() ?: return null
        val name = if (opencl) LIB_OPENCL else LIB
        val lib = baseDir.resolve(manifest.version).resolve(name)
        return if (lib.isFile) lib else null
    }

    override fun isInUse(): Boolean =
        NativeLoader.isLlamaLoaded() || LocalLlamaController.isRunning()

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        val versionDir = baseDir.resolve(version)
        versionDir.deleteRecursively()
        versionDir.mkdirs()

        ZipFile(verifiedTmp).use { zip ->
            // Inner manifest (written by xtask): per-file sha256 for post-extraction re-verification.
            val manifestEntry = zip.getEntry("manifest.json") ?: error("llama-native pack has no inner manifest")
            val hashes = Json.parseToJsonElement(zip.getInputStream(manifestEntry).readBytes().decodeToString())
                .jsonObject["files"]!!.jsonObject
                .mapValues { it.value.jsonPrimitive.content }

            for (entryName in listOf("$ABI/$LIB", "$ABI/$LIB_OPENCL")) {
                val entry = zip.getEntry(entryName) ?: error("llama-native pack has no $entryName")
                val fileName = entryName.substringAfter('/')
                val tmpSo = File(versionDir, "$fileName.tmp")
                zip.getInputStream(entry).use { input ->
                    tmpSo.outputStream().use { output -> input.copyTo(output) }
                }
                if (!PackFs.verify(tmpSo, hashes.getValue(entryName))) {
                    tmpSo.delete()
                    error("inner manifest SHA-256 mismatch for $entryName")
                }
                tmpSo.setReadable(true, true)
                tmpSo.setExecutable(true, true)
                PackFs.atomicReplace(tmpSo, File(versionDir, fileName))
            }
        }
        PackFs.writeManifest(
            versionDir,
            PackManifest(id, version, sha256, System.currentTimeMillis()),
        )
        sweepOtherVersions(version)
        WeLogger.i("LlamaNativePack", "installed llama-native $version")
    }
}
