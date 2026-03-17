package moe.ouom.wekit.dexkit.cache

import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.constants.PreferenceKeys
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.utils.ModulePaths
import moe.ouom.wekit.utils.logging.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Dex 缓存管理器
 * 负责管理 Dex 查找结果的缓存，支持版本控制和增量更新
 */
object DexCacheManager {

    private val TAG = nameof(DexCacheManager)

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val HOST_VERSION_FILE = "host_version.txt"
    private const val CACHE_FILE_SUFFIX = ".json"
    private lateinit var cacheDir: Path
    private var currentHostVersion: String = ""

    fun init(hostVersion: String) {
        cacheDir = ModulePaths.data!! / CACHE_DIR_NAME
        cacheDir.createDirectories()

        currentHostVersion = hostVersion

        // 检查宿主版本是否变化
        val versionFile = cacheDir / HOST_VERSION_FILE
        if (versionFile.exists()) {
            val cachedVersion = versionFile.readText().trim()
            if (cachedVersion != hostVersion) {
                WeLogger.i(
                    TAG,
                    "Host version changed: $cachedVersion -> $hostVersion, clearing all cache"
                )
                clearAllCache()

                // 重置"禁用版本适配"配置，确保新版本能够正常适配
                WePrefs.putBool(PreferenceKeys.NO_DEX_RESOLVE, false)
                WeLogger.i(
                    TAG,
                    "Reset disable_version_adaptation to false due to version change"
                )
            }
        }

        // 保存当前版本
        versionFile.writeText(hostVersion)
    }

    /**
     * 检查 HookItem 的缓存是否有效
     * @param item 实现了 IDexFind 的 HookItem
     * @return true 表示缓存有效，false 表示需要重新查找
     */
    fun isItemCacheValid(item: IResolvesDex): Boolean {
        if (item !is BaseHookItem) {
            WeLogger.w(TAG, "Item is not BaseHookItem, cannot get path")
            return false
        }

        val cacheFile = getCacheFile(item.path)
        if (!cacheFile.exists()) {
            WeLogger.d(TAG, "Cache not found for: ${item.path}")
            return false
        }

        try {
            val json = JSONObject(cacheFile.readText())

            val cachedMethodHash = json.optString("methodHash", "")
            val currentMethodHash = calculateMethodHash(item)

            if (cachedMethodHash != currentMethodHash) {
                WeLogger.d(
                    TAG,
                    "dex location method of ${item.path} changed: cached=$cachedMethodHash, current=$currentMethodHash"
                )
                return false
            }

            // 检查缓存数据是否为空
            val dataKeys = json.keys().asSequence()
                .filter { key -> key !in listOf("methodHash", "hostVersion", "timestamp") }
                .toList()

            if (dataKeys.isEmpty()) {
                WeLogger.d(TAG, "Cache is empty for: ${item.path}, need rescan")
                return false
            }

            // 验证缓存数据的完整性：检查所有值是否有效
            var hasInvalidData = false
            for (key in dataKeys) {
                val value = json.optString(key, "")
                if (value.isEmpty() || value == "null") {
                    WeLogger.d(
                        TAG,
                        "Cache has invalid data for key: $key in ${item.path}"
                    )
                    hasInvalidData = true
                    break
                }
            }

            if (hasInvalidData) {
                WeLogger.d(
                    TAG,
                    "Cache data incomplete for: ${item.path}, need rescan"
                )
                return false
            }

//            WeLogger.d(TAG, "Cache valid for: ${item.path}, keys: $dataKeys")
            return true
        } catch (e: Exception) {
            WeLogger.e("DexCacheManager: Failed to read cache for: ${item.path}", e)
            return false
        }
    }

    /**
     * 计算 dexFind 方法的哈希值
     * 用于检测方法逻辑是否发生变化
     * 使用编译时生成的hash值，避免运行时通过classLoader获取资源失败的问题
     */
    private fun calculateMethodHash(item: IResolvesDex): String {
        try {
            val clazz = item::class.java
            val className = clazz.name

            // 优先使用编译时生成的hash值
            val generatedHash = GeneratedMethodHashes.getHash(className)
            if (generatedHash.isNotEmpty()) {
                return generatedHash
            }

            WeLogger.w(
                TAG,
                "No generated hash for $className, using method signature fallback"
            )
            val method =
                clazz.getDeclaredMethod("resolveDex", DexKitBridge::class.java)

            val signature = buildString {
                append(method.declaringClass.name)
                append("::")
                append(method.name)
                append("(")
                method.parameterTypes.forEach { append(it.name).append(",") }
                append(")")
            }

            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(signature.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            WeLogger.e("DexCacheManager: Failed to calculate method hash", e)
            return ""
        }
    }

    fun saveItemCache(item: IResolvesDex, data: Map<String, Any>) {
        if (item !is BaseHookItem) {
            WeLogger.w(TAG, "Item is not BaseHookItem, cannot get path")
            return
        }

        val cacheFile = getCacheFile(item.path)
        try {
            val json = JSONObject()
            json.put("methodHash", calculateMethodHash(item))
            json.put("hostVersion", currentHostVersion)
            json.put("timestamp", System.currentTimeMillis())

            // 保存自定义数据
            data.forEach { (key, value) ->
                json.put(key, value)
            }

            cacheFile.writeText(json.toString(2))
            WeLogger.d(TAG, "Cache saved for: ${item.path}")
        } catch (e: Exception) {
            WeLogger.e("DexCacheManager: Failed to save cache for: ${item.path}", e)
        }
    }

    fun loadItemCache(item: IResolvesDex): Map<String, Any>? {
        if (item !is BaseHookItem) {
            WeLogger.w(TAG, "Item is not BaseHookItem, cannot get path")
            return null
        }

        val cacheFile = getCacheFile(item.path)
        if (!cacheFile.exists()) {
            return null
        }

        try {
            val json = JSONObject(cacheFile.readText())
            val result = mutableMapOf<String, Any>()

            json.keys().forEach { key ->
                if (key !in listOf("methodHash", "hostVersion", "timestamp")) {
                    result[key] = json.get(key)
                }
            }

            return result
        } catch (e: Exception) {
            WeLogger.e("DexCacheManager: Failed to load cache for: ${item.path}", e)
            return null
        }
    }

    fun deleteCache(path: String) {
        val cacheFile = getCacheFile(path)
        cacheFile.deleteIfExists()
    }

    fun clearAllCache() {
        cacheDir.listDirectoryEntries().forEach { path ->
            if (path.name != HOST_VERSION_FILE) {
                path.deleteIfExists()
            }
        }
        WeLogger.i(TAG, "All cache cleared")
    }

    private fun getCacheFile(path: String): Path {
        // 将路径转换为文件名（替换 / 为 _）
        val fileName = path.replace("/", "_") + CACHE_FILE_SUFFIX
        return cacheDir / fileName
    }

    fun getOutdatedItems(items: List<IResolvesDex>): List<IResolvesDex> {
        return items.filter { !isItemCacheValid(it) }
    }
}
