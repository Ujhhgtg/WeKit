package moe.ouom.wekit.hooks.core

import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.constants.Constants.PrekClickableXXX
import moe.ouom.wekit.constants.Constants.PrekDisableVersionAdaptation
import moe.ouom.wekit.constants.Constants.PrekXXX
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.factory.HookItemFactory
import moe.ouom.wekit.ui.content.DexFinderContent
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.log.WeLogger

/**
 * HookItem 加载器
 * 负责加载所有 HookItem，优先加载有效缓存，后台异步修复无效缓存
 */
class HookItemLoader {
    fun loadHookItem(process: Int) {
        val classLoader = RuntimeConfig.getHostClassLoader()
        val appInfo = RuntimeConfig.getHostApplicationInfo()

        loadHookItem(process, classLoader, appInfo)
    }

    /**
     * 加载并判断哪些需要加载
     * 策略：
     * 1. 识别出哪些缓存过期，哪些缓存有效
     * 2. 尝试加载有效缓存，若加载失败则归入“待修复列表”
     * 3. 对“待修复列表”启动异步线程弹出 Dialog
     * 4. 仅筛选出那些配置开启且缓存就绪（或不需要缓存）的项进行最终加载
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

        // 检查哪些项的缓存已经过期
        val outdatedItems = DexCacheManager.getOutdatedItems(allDexFindItems)

        // 筛选出理论上缓存有效的项
        val potentiallyValidItems = allDexFindItems.filterNot { outdatedItems.contains(it) }

        WeLogger.i(
            "HookItemLoader",
            "Found ${outdatedItems.size} outdated items, ${potentiallyValidItems.size} potentially valid items"
        )

        // 尝试从缓存加载 Descriptor，返回加载失败的项
        val corruptedItems = loadDescriptorsFromCache(potentiallyValidItems)

        // 汇总所有不可用的项
        val allBrokenItems = (outdatedItems + corruptedItems).distinct()

        // 如果存在不可用的项，根据配置决定是否启动修复流程
        if (allBrokenItems.isNotEmpty()) {
            handleBrokenItemsAsync(process, appInfo, allBrokenItems)
        } else {
            WeLogger.i("HookItemLoader", "All Dex cache entries are valid.")
        }

        // 开始构建最终需要执行的列表
        val enabledItems = mutableListOf<Any>()

        allHookItems.forEach { hookItem ->
            // 如果该项需要 Dex 查找，且属于 损坏/过期 列表，则直接跳过，不尝试加载
            if (hookItem is IDexFind && allBrokenItems.contains(hookItem)) {
                WeLogger.w(
                    "HookItemLoader",
                    "Skipping ${(hookItem as? BaseHookItem)?.path} due to missing or invalid cache"
                )
                return@forEach
            }

            var isEnabled = false
            when (hookItem) {
                is BaseSwitchFunctionHookItem -> {
                    hookItem.isEnabled = WeConfig.getDefaultConfig()
                        .getBooleanOrFalse("$PrekXXX${hookItem.path}")
                    isEnabled = hookItem.isEnabled && process == hookItem.targetProcess
                }

                is BaseClickableFunctionHookItem -> {
                    hookItem.isEnabled = WeConfig.getDefaultConfig()
                        .getBooleanOrFalse("$PrekClickableXXX${hookItem.path}")
                    isEnabled =
                        (hookItem.isEnabled && process == hookItem.targetProcess) || hookItem.alwaysRun
                }

                is ApiHookItem -> {
                    // API 类通常不需要 DexFind 或者是硬编码，通常总是允许尝试
                    isEnabled = process == hookItem.targetProcess
                }
            }

            if (isEnabled) {
                enabledItems.add(hookItem)
            }
        }

        // 执行加载（此时列表里只有 缓存有效 或 不需要缓存 的项）
        WeLogger.i(
            "HookItemLoader",
            "Executing load for ${enabledItems.size} ready items in process: $process"
        )
        loadAllItems(enabledItems)
    }

    /**
     * 异步处理损坏或过期的项
     */
    private fun handleBrokenItemsAsync(
        process: Int,
        appInfo: ApplicationInfo,
        brokenItems: List<IDexFind>
    ) {
        val disableVersionAdaptation = WeConfig.getDefaultConfig()
            .getBooleanOrFalse(PrekDisableVersionAdaptation)

        if (disableVersionAdaptation) {
            WeLogger.w(
                "HookItemLoader",
                "Version adaptation disabled. ${brokenItems.size} items will not run."
            )
            return
        }

        WeLogger.i(
            "HookItemLoader",
            "Launching background thread to repair ${brokenItems.size} items"
        )

        Thread {
            val startTime = System.currentTimeMillis()
            val timeoutMs = 90 * 1000L // 90 秒超时

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                // 只有主进程才处理 UI 弹窗
                if (process != SyncUtils.PROC_MAIN) return@Thread

                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                val activity = RuntimeConfig.getLauncherUIActivity()
                if (activity != null) {
                    // 确保 Activity 已经初始化完成
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                    }

                    SyncUtils.post {
                        WeLogger.i("HookItemLoader", "Showing DexFinderDialog for repair")
                        showComposeDialog(activity) { onDismiss ->
                            DexFinderContent(
                                activity,
                                brokenItems,
                                appInfo,
                                CoroutineScope(Dispatchers.Main + SupervisorJob()),
                                onDismiss = onDismiss
                            )
                        }
                    }
                    return@Thread
                }
            }
            WeLogger.e(
                "HookItemLoader",
                "Wait for LauncherUIActivity timed out after 90s, dialog skipped"
            )
        }.start()
    }

    /**
     * 从缓存加载 descriptor
     * @return 加载失败的项列表
     */
    private fun loadDescriptorsFromCache(items: List<IDexFind>): List<IDexFind> {
        val failedItems = mutableListOf<IDexFind>()

        items.forEach { item ->
            try {
                val cache = DexCacheManager.loadCache(item)
                if (cache != null) {
                    // WeLogger.d("HookItemLoader", "Loading cache for ${(item as? BaseHookItem)?.path}")
                    item.loadFromCache(cache)
                } else {
                    WeLogger.w(
                        "HookItemLoader",
                        "Cache is null for ${(item as? BaseHookItem)?.path}"
                    )
                    failedItems.add(item)
                }
            } catch (e: Exception) {
                // 捕获所有异常，视为缓存损坏
                val path = (item as? BaseHookItem)?.path ?: "unknown"
                WeLogger.e("HookItemLoader", "Cache load failed for $path", e)

                // 尝试清理坏掉的缓存
                try {
                    DexCacheManager.deleteCache(path)
                } catch (_: Exception) {
                }

                failedItems.add(item)
            }
        }

        return failedItems
    }

    /**
     * 加载所有已筛选通过的 HookItem
     */
    private fun loadAllItems(items: List<Any>) {
        items.forEach { hookItem ->
            runCatching {
                when (hookItem) {
                    is BaseSwitchFunctionHookItem -> {
                        WeLogger.i("HookItemLoader", "[Switch] Init ${hookItem.path}")
                    }

                    is BaseClickableFunctionHookItem -> {
                        WeLogger.i("HookItemLoader", "[Clickable] Init ${hookItem.path}")
                    }

                    is ApiHookItem -> {
                        WeLogger.i("HookItemLoader", "[API] Init ${hookItem.path}")
                        hookItem.startLoad()
                    }
                }
            }.onFailure { e ->
                WeLogger.e(
                    "HookItemLoader",
                    "Error initializing item: ${hookItem.javaClass.simpleName}",
                    e
                )
            }
        }
    }
}