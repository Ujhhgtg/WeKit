package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.constants.PreferenceKeys
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.hooks.core.BaseHookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.KnownPaths
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.createDirectoriesNoThrow
import org.json.JSONObject
import java.nio.file.Path
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
 * 缓存的 key → value 由各 [dev.ujhhgtg.wekit.dexkit.dsl.DexDelegateBase] 直接提供
 */
object DexCacheManager {

    private val TAG = This.Class.simpleName

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val CACHE_FILE_SUFFIX = ".json"
    private const val KEY_HOST_VERSION = "host_version"

    private val cacheDir: Path by lazy {
        (KnownPaths.moduleData / CACHE_DIR_NAME).createDirectoriesNoThrow()
    }

    fun init(currentHostVersion: String) {
        val cachedVersion = WePrefs.getString(KEY_HOST_VERSION)
        if (cachedVersion != currentHostVersion) {
            WeLogger.i(TAG, "host version changed: $cachedVersion -> $currentHostVersion, resetting all cache")
            clearAllCache()
            WePrefs.putBool(PreferenceKeys.NO_DEX_RESOLVE, false)
            WeLogger.i(TAG, "disabling NO_DEX_RESOLVE due to host version change")
        }

        WePrefs.putString(KEY_HOST_VERSION, currentHostVersion)
    }

    /**
     * 检查 HookItem 的缓存是否完整有效。
     *
     * 有效条件：
     * 1. 缓存文件存在
     * 2. methodHash 匹配（检测代码变化）
     * 3. [item] 的每个委托 key 都有非空值
     */
    fun isItemCacheValid(item: IResolvesDex): Boolean {
        if (item !is BaseHookItem) {
            WeLogger.w(TAG, "Item is not BaseHookItem, cannot get path")
            return false
        }

        val cacheFile = getCacheFile(item.path)
        if (!cacheFile.exists()) {
            WeLogger.d(TAG, "cache not found for ${item.path}")
            return false
        }

        return try {
            val json = JSONObject(cacheFile.readText())

            val cachedHash = json.optString("methodHash", "")
            val currentHash = calculateMethodHash(item)
            if (cachedHash != currentHash) {
                WeLogger.d(TAG, "resolveDex of ${item.path} changed: cached=$cachedHash, current=$currentHash")
                return false
            }

            // 每个委托对应一个 key，全部必须存在且非空
            val missingOrEmpty = item.dexDelegates.filter { delegate ->
                val v = json.optString(delegate.key, "")
                v.isEmpty() || v == "null"
            }

            if (missingOrEmpty.isNotEmpty()) {
                WeLogger.d(TAG, "cache incomplete for ${item.path}, missing keys: ${missingOrEmpty.map { it.key }}")
                return false
            }

            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read cache for: ${item.path}", e)
            false
        }
    }

    /**
     * 将 [item] 所有委托的当前描述符持久化到缓存文件。
     * 数据来自 [IResolvesDex.collectDescriptors]。
     */
    fun saveItemCache(item: IResolvesDex) {
        if (item !is BaseHookItem) {
            error("item is not BaseHookItem")
        }

        val cacheFile = getCacheFile(item.path)
        try {
            val json = JSONObject()
            json.put("methodHash", calculateMethodHash(item))
            json.put("timestamp", System.currentTimeMillis())

            item.collectDescriptors().forEach { (key, value) ->
                json.put(key, value)
            }

            cacheFile.writeText(json.toString(2))
            WeLogger.d(TAG, "cache saved for: ${item.path}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to save cache for: ${item.path}", e)
        }
    }

    /**
     * 从缓存文件加载原始 Map（不包含元数据 key）。
     * 由 [IResolvesDex.loadFromCache] 消费，后者负责逐委托分发。
     */
    fun loadItemCache(item: IResolvesDex): Map<String, Any>? {
        if (item !is BaseHookItem) {
            error("item is not BaseHookItem")
        }

        val cacheFile = getCacheFile(item.path)
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            buildMap {
                for (key in json.keys()) {
                    if (key !in META_KEYS) put(key, json.get(key))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to load cache for: ${item.path}", e)
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

    fun getOutdatedItems(items: List<IResolvesDex>): List<IResolvesDex> =
        items.filter { !isItemCacheValid(it) }

    // ---------------------------------------------------------------------------

    private val META_KEYS = setOf("methodHash", "timestamp")

    private fun getCacheFile(path: String): Path =
        cacheDir / (path.replace("/", "_") + CACHE_FILE_SUFFIX)

    /**
     * 获取 resolveDex 方法编译时生成的哈希，用于检测实现变化。
     */
    private fun calculateMethodHash(item: IResolvesDex): String {
        val className = item.javaClass.name
        val hash = GeneratedMethodHashes.HASHES[className]
        if (hash.isNullOrBlank())
            error("failed to retrieve method hash for item $className; this shouldn't happen")
        return hash
    }
}
