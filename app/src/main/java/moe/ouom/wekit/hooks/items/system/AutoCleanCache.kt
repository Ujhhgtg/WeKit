package moe.ouom.wekit.hooks.items.system

import android.content.Context
import android.widget.Toast
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.formatBytesSize
import moe.ouom.wekit.utils.formatEpoch
import moe.ouom.wekit.utils.log.WeLogger
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists

@HookItem(path = "系统与隐私/清理缓存垃圾", desc = "自动或手动清理应用的缓存")
object AutoCleanCache : ClickableHookItem() {

    private val TAG = nameof(AutoCleanCache)
    private const val CLEAN_INTERVAL = 30 * 60 * 1000L // 每 30 分钟清理一次

    private var cleanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cleanPaths = run {
        val paths = mutableListOf<Path>()

        val dataDir = HostInfo.application.filesDir.parentFile!!.toPath()
        val storageDataDir = HostInfo.application.externalCacheDir!!.toPath().parent!!

        paths.add(dataDir / "cache")
        paths.add(storageDataDir / "cache")
        paths.add(dataDir / "MicroMsg" / "crash")
        paths.add(storageDataDir / "files" / "onelog")
        paths.add(storageDataDir / "files" / "tbslog")
        paths.add(storageDataDir / "files" / "Tencent" / "tbs_common_log")
        paths.add(storageDataDir / "files" / "Tencent" / "tbs_live_log")
        paths.add(dataDir / "appbrand")
        paths.add(dataDir / "cache" / "appbrand")
        paths.add(dataDir / "MicroMsg" / "appbrand")
        paths.add(dataDir / "cache" / "liteapp")
        paths.add(dataDir / "files" / "liteapp")
        paths.add(dataDir / "tinker")
        paths.add(dataDir / "tinker_server")
        paths.add(dataDir / "tinker_temp")

        return@run paths
    }

    override fun onLoad() {
        startCleaningJob()
    }

    private fun startCleaningJob() {
        cleanJob?.cancel()
        cleanJob = scope.launch {
            while (isActive) {
                performClean()
                delay(CLEAN_INTERVAL)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun performClean(): Long {
        var totalDeletedBytes = 0L
        cleanPaths.forEach { path ->
            try {
                WeLogger.d(TAG, "deleting $path")
                if (path.exists()) {
                    totalDeletedBytes += calculateSize(path)
                    path.deleteRecursively()
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "exception during cleaning: ${path.fileName}, ${e.message}")
            }
        }
        return totalDeletedBytes
    }

    private fun calculateSize(path: Path): Long {
        val file = path.toFile()
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()

        var size = 0L
        file.listFiles()?.forEach {
            size += if (it.isDirectory) calculateSize(it.toPath()) else it.length()
        }
        return size
    }

    override fun onClick(context: Context) {
        scope.launch {
            val deletedSize = performClean()
            val sizeText = formatBytesSize(deletedSize)

            val timeText =
                if (isEnabled) "\n下次自动清理将在 ${formatEpoch(System.currentTimeMillis() + CLEAN_INTERVAL)} 进行"
                else ""

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "缓存清理完成, 共释放 $sizeText$timeText",
                    Toast.LENGTH_SHORT
                ).show()
            }

            if (isEnabled) startCleaningJob()
        }
    }
}