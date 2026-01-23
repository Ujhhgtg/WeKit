package moe.ouom.wekit.dexkit.cache

import android.content.Context
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.util.log.WeLogger
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Dex 缓存管理器
 * 负责管理 Dex 查找结果的缓存，支持版本控制和增量更新
 */
object DexCacheManager {

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val HOST_VERSION_FILE = "host_version.txt"
    private const val CACHE_FILE_SUFFIX = ".json"

    /**
     * 开发模式：设置为 true 时完全禁用缓存，每次都重新扫描
     * 生产环境请设置为 false 以提升性能
     */
    private const val DEV_MODE = false

    private lateinit var cacheDir: File
    private var currentHostVersion: String = ""

    /**
     * 初始化缓存管理器
     * @param context 应用上下文
     * @param hostVersion 宿主应用版本号
     */
    fun init(context: Context, hostVersion: String) {
        cacheDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        currentHostVersion = hostVersion

        // 检查宿主版本是否变化
        val versionFile = File(cacheDir, HOST_VERSION_FILE)
        if (versionFile.exists()) {
            val cachedVersion = versionFile.readText().trim()
            if (cachedVersion != hostVersion) {
                WeLogger.i("DexCacheManager", "Host version changed: $cachedVersion -> $hostVersion, clearing all cache")
                clearAllCache()
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
    fun isCacheValid(item: IDexFind): Boolean {
        // 开发模式：强制禁用缓存
        if (DEV_MODE) {
            if (item is BaseHookItem) {
                WeLogger.w("DexCacheManager", "[DEV_MODE] Cache disabled for: ${item.path}")
            }
            return false
        }

        if (item !is BaseHookItem) {
            WeLogger.w("DexCacheManager", "Item is not BaseHookItem, cannot get path")
            return false
        }

        val cacheFile = getCacheFile(item.path)
        if (!cacheFile.exists()) {
            WeLogger.d("DexCacheManager", "Cache not found for: ${item.path}")
            return false
        }

        try {
            val json = JSONObject(cacheFile.readText())

            val cachedMethodHash = json.optString("methodHash", "")
            val currentMethodHash = calculateMethodHash(item)

            WeLogger.d(
                "DexCacheManager",
                "Hash comparison for: ${item.path}, cached: $cachedMethodHash, current: $currentMethodHash"
            )

            if (cachedMethodHash != currentMethodHash) {
                WeLogger.d(
                    "DexCacheManager",
                    "Method logic changed for: ${item.path}, cached: $cachedMethodHash, current: $currentMethodHash"
                )
                return false
            }

            // 检查缓存数据是否为空
            val dataKeys = json.keys().asSequence()
                .filter { key -> key !in listOf("methodHash", "hostVersion", "timestamp") }
                .toList()

            if (dataKeys.isEmpty()) {
                WeLogger.d("DexCacheManager", "Cache is empty for: ${item.path}, need rescan")
                return false
            }

            // 验证缓存数据的完整性：检查所有值是否有效
            var hasInvalidData = false
            for (key in dataKeys) {
                val value = json.optString(key, "")
                if (value.isEmpty() || value == "null") {
                    WeLogger.d("DexCacheManager", "Cache has invalid data for key: $key in ${item.path}")
                    hasInvalidData = true
                    break
                }
            }

            if (hasInvalidData) {
                WeLogger.d("DexCacheManager", "Cache data incomplete for: ${item.path}, need rescan")
                return false
            }

            WeLogger.d("DexCacheManager", "Cache valid for: ${item.path}, keys: $dataKeys")
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
    private fun calculateMethodHash(item: IDexFind): String {
        try {
            val clazz = item::class.java
            val className = clazz.name

            // 优先使用编译时生成的hash值
            val generatedHash = GeneratedMethodHashes.getHash(className)
            if (generatedHash.isNotEmpty()) {
                return generatedHash
            }

            // 降级方案：使用方法签名（当编译时hash不可用时）
            WeLogger.w("DexCacheManager", "No generated hash for $className, using method signature fallback")
            val method = clazz.getDeclaredMethod("dexFind", org.luckypray.dexkit.DexKitBridge::class.java)
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

    /**
     * 保存缓存
     * @param item 实现了 IDexFind 的 HookItem
     * @param data 要缓存的数据（JSON 格式）
     */
    fun saveCache(item: IDexFind, data: Map<String, Any>) {
        if (item !is BaseHookItem) {
            WeLogger.w("DexCacheManager", "Item is not BaseHookItem, cannot get path")
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
            WeLogger.d("DexCacheManager", "Cache saved for: ${item.path}")
        } catch (e: Exception) {
            WeLogger.e("DexCacheManager: Failed to save cache for: ${item.path}", e)
        }
    }

    /**
     * 读取缓存
     * @param item 实现了 IDexFind 的 HookItem
     * @return 缓存的数据，如果不存在则返回 null
     */
    fun loadCache(item: IDexFind): Map<String, Any>? {
        if (item !is BaseHookItem) {
            WeLogger.w("DexCacheManager", "Item is not BaseHookItem, cannot get path")
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

    /**
     * 删除指定 HookItem 的缓存
     */
    fun deleteCache(path: String) {
        val cacheFile = getCacheFile(path)
        if (cacheFile.exists()) {
            cacheFile.delete()
            WeLogger.d("DexCacheManager", "Cache deleted for: $path")
        }
    }

    /**
     * 清空所有缓存
     */
    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name != HOST_VERSION_FILE) {
                file.delete()
            }
        }
        WeLogger.i("DexCacheManager", "All cache cleared")
    }

    /**
     * 获取缓存文件
     */
    private fun getCacheFile(path: String): File {
        // 将路径转换为文件名（替换 / 为 _）
        val fileName = path.replace("/", "_") + CACHE_FILE_SUFFIX
        return File(cacheDir, fileName)
    }

    /**
     * 获取所有需要更新的 HookItem
     * @param items 所有实现了 IDexFind 的 HookItem
     * @return 需要更新的 HookItem 列表
     */
    fun getOutdatedItems(items: List<IDexFind>): List<IDexFind> {
        return items.filter { !isCacheValid(it) }
    }
}
