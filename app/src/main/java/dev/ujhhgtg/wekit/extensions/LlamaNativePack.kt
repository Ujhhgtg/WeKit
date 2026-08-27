package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Memory
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaController
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.util.zip.ZipFile

/** Thrown when the local inference engine is needed but its pack is not installed. */
class LlamaPackNotInstalledException(message: String) : RuntimeException(message)

/**
 * llama-native 扩展包:arm64 zip,安装时把两个变体都解到 version 目录——
 * libwekit_llama.so(CPU/Vulkan)与 libwekit_llama_opencl.so(额外含 OpenCL),
 * 父进程只加载基础变体提供控制器 JNI，每个 app_process 子进程独立加载所选变体。
 */
object LlamaNativePack : ExtensionPack {

    override val id = "llama-native"
    override val displayOrder = 4
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

    override fun isSupported(): Boolean = supportsArm64ExtensionProcess()

    /** The requested variant's library file, or null when not installed. */
    fun libraryFile(opencl: Boolean): File? {
        val manifest = installedManifest() ?: return null
        val name = if (opencl) LIB_OPENCL else LIB
        val lib = baseDir.resolve(manifest.version).resolve(name)
        return if (lib.isFile) lib else null
    }

    override fun isInUse(): Boolean = LocalLlamaController.isLifecycleActive()

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        val staging = File(baseDir, ".$version-installing").apply { deleteRecursively(); mkdirs() }
        ZipFile(verifiedTmp).use { zip ->
            for (entryName in listOf("$ABI/$LIB", "$ABI/$LIB_OPENCL")) {
                val entry = zip.getEntry(entryName) ?: error("llama-native pack has no $entryName")
                val file = File(staging, entryName.substringAfter('/'))
                zip.getInputStream(entry).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                file.setReadable(true, true)
                file.setExecutable(true, true)
            }
        }
        PackFs.writeManifest(staging, PackManifest(id, version, sha256, System.currentTimeMillis()))
        val destination = baseDir.resolve(version).apply { deleteRecursively() }
        require(staging.renameTo(destination)) { "cannot publish llama-native $version" }
        sweepOtherVersions(version)
        WeLogger.i("LlamaNativePack", "installed llama-native $version")
    }
}
