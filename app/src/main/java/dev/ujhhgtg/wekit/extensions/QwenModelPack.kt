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

    override fun installedManifest(): PackManifest? {
        val manifest = baseDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { PackFs.readManifest(it) }
            .maxByOrNull { it.installedAtEpochMs }
            ?: return null
        // Crash after candidate->destination but before rollback cleanup: the
        // visible complete install is authoritative, so release its rollback.
        // A candidate is never removed from this read path: it may belong to
        // an install that is concurrently between staging and publication.
        if (isComplete(baseDir.resolve(manifest.version), manifest.version, manifest.sha256)) {
            previousDir(manifest.version).deleteRecursively()
        }
        return manifest
    }

    override fun isInUse(): Boolean =
        LocalLlamaController.isRunning() && LocalLlamaController.loadedModelPath() == modelFile()?.absolutePath

    override fun recoverInterruptedInstall(verifiedTmp: File, version: String, sha256: String) {
        baseDir.mkdirs()
        val candidate = candidateDir(version)
        val destination = baseDir.resolve(version)
        val previous = previousDir(version)

        // Crash after old->previous but before candidate->destination: prefer
        // completing the fully staged publication. If that rename cannot be
        // completed, restore the old install before recovering the new blob.
        if (!destination.exists() && isComplete(candidate, version, sha256) &&
            candidate.renameTo(destination)
        ) {
            previous.deleteRecursively()
            return
        }
        if (!destination.exists() && previous.isDirectory) {
            require(previous.renameTo(destination)) { "cannot restore prior Qwen model $version" }
        }
        if (isComplete(destination, version, sha256)) {
            candidate.deleteRecursively()
            previous.deleteRecursively()
            return
        }

        if (isComplete(candidate, version, sha256)) {
            verifiedTmp.parentFile?.mkdirs()
            PackFs.atomicReplace(candidate.resolve(MODEL_FILE), verifiedTmp)
        }
        candidate.deleteRecursively()
        if (destination.exists()) previous.deleteRecursively()
    }

    override fun install(verifiedTmp: File, version: String, sha256: String, meta: String?) {
        recoverInterruptedInstall(verifiedTmp, version, sha256)
        val candidate = candidateDir(version)
        val destination = baseDir.resolve(version)
        val previous = previousDir(version)
        if (isComplete(destination, version, sha256)) {
            verifiedTmp.delete()
            sweepOtherVersions(version)
            return
        }

        candidate.deleteRecursively()
        previous.deleteRecursively()
        require(candidate.mkdirs()) { "cannot create Qwen install candidate $candidate" }
        // The manifest is the recovery marker: a candidate is reusable only
        // when both this complete marker and the already-verified model exist.
        PackFs.writeManifest(
            candidate,
            PackManifest(id, version, sha256, System.currentTimeMillis(), meta),
        )

        var published = false
        try {
            PackFs.atomicReplace(verifiedTmp, candidate.resolve(MODEL_FILE))
            if (destination.exists()) {
                require(destination.renameTo(previous)) {
                    "cannot preserve prior Qwen model $version"
                }
            }
            if (!candidate.renameTo(destination)) {
                if (previous.isDirectory) {
                    require(previous.renameTo(destination)) {
                        "cannot restore prior Qwen model $version"
                    }
                }
                error("cannot publish Qwen model $version")
            }
            published = true
            previous.deleteRecursively()
            sweepOtherVersions(version)
        } finally {
            if (!published) {
                if (!destination.exists() && previous.isDirectory) {
                    require(previous.renameTo(destination)) {
                        "cannot restore prior Qwen model $version"
                    }
                }
                val stagedModel = candidate.resolve(MODEL_FILE)
                if (stagedModel.isFile) PackFs.atomicReplace(stagedModel, verifiedTmp)
                candidate.deleteRecursively()
            }
        }
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

    private fun candidateDir(version: String): File = baseDir.resolve(".$version-installing")

    private fun previousDir(version: String): File = baseDir.resolve(".$version-previous")

    private fun isComplete(dir: File, version: String, sha256: String): Boolean {
        val manifest = PackFs.readManifest(dir) ?: return false
        return manifest.id == id && manifest.version == version &&
            manifest.sha256.equals(sha256, ignoreCase = true) &&
            dir.resolve(MODEL_FILE).isFile
    }
}
