package moe.ouom.wekit.hooks.core

import android.app.Activity
import android.content.pm.ApplicationInfo
import moe.ouom.wekit.config.ConfigManager
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.constants.Constants.Companion.PrekClickableXXX
import moe.ouom.wekit.constants.Constants.Companion.PrekXXX
import moe.ouom.wekit.core.dsl.DexMethodDescriptorWrapper
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.factory.HookItemFactory
import moe.ouom.wekit.ui.creator.center.DexFinderDialog
import moe.ouom.wekit.util.common.SyncUtils
import moe.ouom.wekit.util.log.WeLogger

/**
 * HookItem 加载器
 * 负责加载所有 HookItem，并检查是否需要更新 Dex 缓存
 */
class HookItemLoader {

    /**
     * 加载并判断哪些需要加载（向后兼容的简化版本）
     * @param process 目标进程
     */
    fun loadHookItem(process: Int) {
        val classLoader = RuntimeConfig.getHostClassLoader()
        val appInfo = RuntimeConfig.getHostApplicationInfo()

        loadHookItem(process, classLoader, appInfo)
    }

    /**
     * 加载并判断哪些需要加载
     * @param process 目标进程
     * @param classLoader 宿主 ClassLoader
     * @param appInfo 宿主 ApplicationInfo
     */
    fun loadHookItem(
        process: Int,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo
    ) {
        // 获取全量 HookItem 列表
        val allHookItems = HookItemFactory.getAllItemListStatic()
        // 筛选出所有需要进行 Dex 查找的项
        val allDexFindItems = allHookItems.filterIsInstance<IDexFind>()
        // 检查哪些项的缓存已经过期（版本变动或未初始化）
        val outdatedItems = DexCacheManager.getOutdatedItems(allDexFindItems)

        if (outdatedItems.isNotEmpty()) {
            WeLogger.i("HookItemLoader", "Found ${outdatedItems.size} outdated items (including disabled ones), starting update")

            WeLogger.i("HookItemLoader", "Launching DexFinderDialog for global cache refresh")
            Thread {
                while (true) {
                    Thread.sleep(10)
                    val activity = RuntimeConfig.getLauncherUIActivity()
                    if (activity != null) {
                        SyncUtils.post {
                            val dialog = DexFinderDialog(activity, classLoader, appInfo, outdatedItems)
                            // 显示对话框后直接返回
                            dialog.show()
                            return@post
                        }
                        return@Thread
                    }
                }

            }.start()

            return
        } else {
            // 所有项的缓存均有效
            WeLogger.i("HookItemLoader", "All Dex cache is valid, loading descriptors into memory")
            loadDescriptorsFromCache(allDexFindItems)
        }

        // 根据配置和进程过滤需要执行的项
        val enabledItems = mutableListOf<Any>()

        allHookItems.forEach { hookItem ->
            var isEnabled = false

            when (hookItem) {
                is BaseSwitchFunctionHookItem -> {
                    hookItem.isEnabled = ConfigManager.getDefaultConfig()
                        .getBooleanOrFalse("$PrekXXX${hookItem.path}")
                    isEnabled = hookItem.isEnabled && process == hookItem.targetProcess
                }
                is BaseClickableFunctionHookItem -> {
                    hookItem.isEnabled = ConfigManager.getDefaultConfig()
                        .getBooleanOrFalse("$PrekClickableXXX${hookItem.path}")
                    isEnabled = (hookItem.isEnabled && process == hookItem.targetProcess) || hookItem.alwaysRun
                }
                is ApiHookItem -> {
                    isEnabled = process == hookItem.targetProcess
                }
            }

            if (isEnabled) {
                enabledItems.add(hookItem)
            }
        }

        // 执行加载
        WeLogger.i("HookItemLoader", "Executing load for ${enabledItems.size} enabled items in process: $process")
        loadAllItems(enabledItems)
    }

    /**
     * 从缓存加载 descriptor
     */
    private fun loadDescriptorsFromCache(items: List<IDexFind>) {
        items.forEach { item ->
            try {
                val cache = DexCacheManager.loadCache(item)
                if (cache != null) {
                    WeLogger.d("HookItemLoader", "Loading cache for ${(item as? BaseHookItem)?.path}, cache keys: ${cache.keys}")
                    item.loadFromCache(cache)
                } else {
                    WeLogger.w("HookItemLoader", "Cache is null for ${(item as? BaseHookItem)?.path}")
                }
            } catch (e: Exception) {
                WeLogger.e("HookItemLoader: Failed to load descriptors from cache", e)
            }
        }
    }

    /**
     * 加载所有已启用的 HookItem
     */
    private fun loadAllItems(items: List<Any>) {
        items.forEach { hookItem ->
            when (hookItem) {
                is BaseSwitchFunctionHookItem -> {
                    WeLogger.i("HookItemLoader", "[BaseSwitchFunctionHookItem] Initializing ${hookItem.path}...")
                    hookItem.startLoad()
                }
                is BaseClickableFunctionHookItem -> {
                    WeLogger.i("HookItemLoader", "[BaseClickableFunctionHookItem] Initializing ${hookItem.path}...")
                    hookItem.startLoad()
                }
                is ApiHookItem -> {
                    WeLogger.i("HookItemLoader", "[API] Initializing ${hookItem.path}...")
                    hookItem.startLoad()
                }
            }
        }
    }
}
