package dev.ujhhgtg.wekit.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Neurology
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaController
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaSync
import dev.ujhhgtg.wekit.agent.model.local.parseLocalModelMeta
import dev.ujhhgtg.wekit.utils.HostInfo
import java.io.File

/**
 * Qwen3.8-4B-Distill 模型扩展包:裸 GGUF(index 条目走 externalUrl,从
 * Hugging Face 下载)。install 在同一文件系统内先组装带 manifest 的隐藏
 * candidate，再以 previous 目录提供同版本发布回滚；失败时把已验证 GGUF
 * 还原到下载缓存，重试不再发起 2.8 GB HTTP 请求。
 */
object QwenModelPack : ModelExtensionPack {

    override val id = "qwen3.8-4b-distill"
    override val displayOrder = 5
    override val nameRes = R.string.extensions_pack_qwen_model_name
    override val descriptionRes = R.string.extensions_pack_qwen_model_desc
    override val icon: ImageVector = MaterialSymbols.Outlined.Neurology

    private const val MODEL_FILE = "model.gguf"

    /** Mirrors xtask's QWEN_MODEL_META for installs whose manifest predates the meta field. */
    private const val FALLBACK_META = """{
  "schemaVersion": 1,
  "models": [{
    "id": "qwen3.8-4b-distill-q4km",
    "displayName": "Qwen3.8-4B Distill",
    "file": "model.gguf",
    "quant": "Q4_K_M",
    "defaultContextWindow": 32768,
    "maxContextWindow": 262144,
    "maxTokens": 8192,
    "defaultReasoningEffort": "medium",
    "supportsThinking": true,
    "sampling": { "temperature": 0.6, "topP": 0.95, "topK": 20 }
  }]
}"""

    private val baseDir: File
        get() = File(HostInfo.application.filesDir, "wekit-extensions/$id")

    override fun installDir(): File = baseDir

    override fun stagingDir(): File = File(baseDir, ".staging")

    override fun isSupported(): Boolean = supportsArm64ExtensionProcess()

    override fun isInUse(): Boolean =
        LocalLlamaController.isRunning() && LocalLlamaController.loadedModelPath() == modelFile()?.absolutePath

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        val destination = baseDir.resolve(version).apply { deleteRecursively(); mkdirs() }
        PackFs.atomicReplace(verifiedTmp, destination.resolve(MODEL_FILE))
        PackFs.writeManifest(destination, PackManifest(id, version, sha256, System.currentTimeMillis(), meta))
        sweepOtherVersions(version)
    }

    override fun onInstalled() = LocalLlamaSync.schedule()

    override fun onRemoved() = LocalLlamaSync.schedule()

    /** The installed GGUF weights, or null when not installed. */
    fun modelFile(): File? {
        val manifest = installedManifest() ?: return null
        val file = baseDir.resolve(manifest.version).resolve(MODEL_FILE)
        return if (file.isFile) file else null
    }

    override fun installedModel(): InstalledLocalModel? {
        val manifest = installedManifest() ?: return null
        val file = baseDir.resolve(manifest.version).resolve(MODEL_FILE)
        if (!file.isFile) return null
        return parseLocalModelMeta(manifest.meta ?: FALLBACK_META, file)
    }

}
