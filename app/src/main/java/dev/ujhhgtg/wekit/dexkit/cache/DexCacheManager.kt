package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.unreachable
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Dex 缓存管理器
 * 负责管理 Dex 查找结果的缓存，支持版本控制和增量更新。
 *
 * 缓存的 key → value 由各 [dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate] 直接提供
 */
object DexCacheManager {

    private const val TAG = "DexCacheManager"

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val CACHE_FILE_SUFFIX = ".json"
    private const val KEY_HOST_VERSION = "host_version"

    private val cacheDir: Path by lazy {
        (KnownPaths.moduleData / CACHE_DIR_NAME).createDirsSafe()
    }

    fun init(currentVer: String) {
        val cachedVer = WePrefs.getString(KEY_HOST_VERSION)
        if (cachedVer != currentVer) {
            WeLogger.i(TAG, "host version changed: $cachedVer -> $currentVer, resetting all cache")
            clearAllCache()
            Preferences.noDexResolve = false
            WeLogger.i(TAG, "disabling NO_DEX_RESOLVE due to host version change")
        }

        WePrefs.putString(KEY_HOST_VERSION, currentVer)
    }

    /**
     * 检查 Feature 的缓存是否完整有效。
     *
     * 有效条件：
     * 1. 缓存文件存在
     * 2. methodHash 匹配（检测代码变化）
     * 3. [item] 的每个委托 key 都有非空值
     */
    fun isItemCacheValid(item: IResolveDex): Boolean {
        if (item !is BaseFeature) unreachable()

        val cacheFile = getCacheFile(item.technicalId)
        if (!cacheFile.exists()) {
            WeLogger.d(TAG, "cache not found for ${item.technicalId}")
            return false
        }

        return try {
            val json = JSONObject(cacheFile.readText())

            val cachedHash = json.optString("methodHash", "")
            val currentHash = methodHash(item)
            if (cachedHash != currentHash) {
                WeLogger.d(TAG, "resolveDex of ${item.technicalPath} changed: cached=$cachedHash, current=$currentHash")
                return false
            }

            // 每个委托对应一个 key，全部必须存在且非空
            val missingOrEmpty = item.dexDelegates.filter { delegate ->
                val v = json.optString(delegate.key, "")
                v.isEmpty() || v == "null"
            }

            if (missingOrEmpty.isNotEmpty()) {
                WeLogger.d(TAG, "cache incomplete for ${item.technicalPath}, missing keys: ${missingOrEmpty.map { it.key }}")
                return false
            }

            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read cache for: ${item.technicalPath}", e)
            false
        }
    }

    /**
     * 将 [item] 所有委托的当前描述符持久化到缓存文件。
     * 数据来自 [IResolveDex.collectDescriptors]。
     */
    fun saveItemCache(item: IResolveDex) {
        if (item !is BaseFeature) {
            error("item is not BaseFeature")
        }

        val cacheFile = getCacheFile(item.technicalId)
        try {
            val json = JSONObject()
            json.put("methodHash", methodHash(item))
            json.put("timestamp", System.currentTimeMillis())

            item.collectDescriptors().forEach { (key, value) ->
                json.put(key, value)
            }

            cacheFile.writeText(json.toString(2))
            WeLogger.d(TAG, "cache saved for: ${item.technicalPath}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to save cache for: ${item.technicalPath}", e)
        }
    }

    /**
     * 从缓存文件加载原始 Map（不包含元数据 key）。
     * 由 [IResolveDex.loadFromCache] 消费，后者负责逐委托分发。
     */
    fun loadItemCache(item: IResolveDex): Map<String, Any>? {
        if (item !is BaseFeature) {
            error("item is not BaseFeature")
        }

        val cacheFile = getCacheFile(item.technicalId)
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            buildMap {
                for (key in json.keys()) {
                    if (key !in META_KEYS) put(key, json.get(key))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to load cache for: ${item.technicalPath}", e)
            null
        }
    }

    fun deleteCache(path: String) {
        getCacheFile(path).deleteIfExists()
    }

    fun clearAllCache() {
        cacheDir.listDirectoryEntries().forEach { path ->
            path.deleteIfExists()
        }
        WeLogger.i(TAG, "all cache cleared")
    }

    fun getOutdatedItems(items: List<IResolveDex>): List<IResolveDex> =
        items.filter { !isItemCacheValid(it) }

    internal fun importCloudCaches(entries: List<CloudDexCacheEntry>) {
        writeCloudCacheFiles(cacheDir, entries, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------------------

    private val META_KEYS = setOf("methodHash", "timestamp")

    internal fun cacheFileName(technicalId: String): String =
        technicalId.replace("/", "_") + CACHE_FILE_SUFFIX

    private fun getCacheFile(technicalId: String): Path =
        cacheDir / cacheFileName(technicalId)

    /**
     * 获取 resolveDex 方法编译时生成的哈希，用于检测实现变化。
     */
    internal fun methodHash(item: IResolveDex): String {
        val className = item.javaClass.name
        val hash = GeneratedMethodHashes.HASHES[className]
        if (hash.isNullOrBlank())
            error("failed to retrieve method hash for item $className; this shouldn't happen")
        return hash
    }
}

internal fun writeCloudCacheFiles(
    cacheDir: Path,
    entries: List<CloudDexCacheEntry>,
    timestamp: Long,
) {
    if (entries.isEmpty()) return
    require(entries.map(CloudDexCacheEntry::technicalId).distinct().size == entries.size) {
        "duplicate cloud cache technical ID"
    }

    Files.createDirectories(cacheDir)
    val transactionId = "${System.currentTimeMillis()}-${System.nanoTime()}"
    val staged = mutableListOf<CloudCacheStagedFile>()
    val committed = mutableSetOf<Path>()
    try {
        for (entry in entries) {
            val destination = cacheDir.resolve(DexCacheManager.cacheFileName(entry.technicalId))
            val temp = destination.resolveSibling(".${destination.fileName}.$transactionId.tmp")
            val backup = destination.resolveSibling(".${destination.fileName}.$transactionId.bak")
            staged += CloudCacheStagedFile(destination, temp, backup)
            val json = buildString {
                append("{\n")
                append("  \"methodHash\": ").appendJsonString(entry.methodHash).append(",\n")
                append("  \"timestamp\": ").append(timestamp)
                entry.descriptors.forEach { (key, value) ->
                    append(",\n  ").appendJsonString(key).append(": ").appendJsonString(value)
                }
                append("\n}")
            }
            Files.writeString(temp, json)
        }

        for (file in staged) {
            if (Files.exists(file.destination)) {
                moveReplacing(file.destination, file.backup)
            }
            moveReplacing(file.temp, file.destination)
            committed.add(file.destination)
        }
    } catch (error: Exception) {
        for (file in staged.asReversed()) {
            if (Files.exists(file.backup)) {
                runCatching { moveReplacing(file.backup, file.destination) }
            } else if (file.destination in committed) {
                runCatching { Files.deleteIfExists(file.destination) }
            }
        }
        throw error
    } finally {
        staged.forEach { file ->
            runCatching { Files.deleteIfExists(file.temp) }
            runCatching { Files.deleteIfExists(file.backup) }
        }
    }
}

private data class CloudCacheStagedFile(
    val destination: Path,
    val temp: Path,
    val backup: Path,
)

private fun moveReplacing(source: Path, destination: Path) {
    try {
        Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, destination, REPLACE_EXISTING)
    }
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    return append('"')
}
