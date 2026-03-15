package moe.ouom.wekit.hooks.utils

import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.ouom.wekit.utils.RuntimeConfig
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.constants.PreferenceKeys.NO_DEX_RESOLVE
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.ui.content.DexResolverDialogContent
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.TargetProcessUtils
import moe.ouom.wekit.utils.logging.WeLogger

object HookItemsLoader {

    private val TAG = nameof(HookItemsLoader)

    fun loadHookItems(process: Int) {
        val appInfo = HostInfo.appInfo
        loadHookItems(process, appInfo)
    }

    /**
     * 加载并判断哪些需要加载
     * 策略：
     * 1. 识别出哪些缓存过期，哪些缓存有效
     * 2. 尝试加载有效缓存，若加载失败则归入“待修复列表”
     * 3. 对“待修复列表”启动异步线程弹出 Dialog
     * 4. 仅筛选出那些配置开启且缓存就绪（或不需要缓存）的项进行最终加载
     */
    fun loadHookItems(
        process: Int,
        appInfo: ApplicationInfo
    ) {
        // 获取全量 HookItem 列表
        val allHookItems = HookItemFactory.getItems()

        // 筛选出所有需要进行 Dex 查找的项
        val allDexResolvingItems = allHookItems.filterIsInstance<IResolvesDex>()

        // 检查哪些项的缓存已经过期
        val outdatedItems = DexCacheManager.getOutdatedItems(allDexResolvingItems)

        // 筛选出理论上缓存有效的项
        val potentiallyValidItems = allDexResolvingItems.filterNot { outdatedItems.contains(it) }

        WeLogger.i(
            TAG,
            "found ${outdatedItems.size} outdated items, ${potentiallyValidItems.size} potentially valid items"
        )

        // 尝试从缓存加载 Descriptor，返回加载失败的项
        val corruptedItems = loadDescriptorsFromCache(potentiallyValidItems)

        // 汇总所有不可用的项
        val allBrokenItems = (outdatedItems + corruptedItems).distinct()

        // 如果存在不可用的项，根据配置决定是否启动修复流程
        if (allBrokenItems.isNotEmpty()) {
            handleBrokenItemsAsync(process, appInfo, allBrokenItems)
        }

        // 开始构建最终需要执行的列表
        val enabledItems = mutableListOf<BaseHookItem>()

        allHookItems.forEach { hookItem ->
            // 如果该项需要 Dex 查找，且属于 损坏/过期 列表，则直接跳过，不尝试加载
            if (hookItem is IResolvesDex && allBrokenItems.contains(hookItem)) {
                WeLogger.w(
                    TAG,
                    "Skipping ${(hookItem as? BaseHookItem)?.path} due to missing or invalid cache"
                )
                return@forEach
            }

            var isEnabled = false
            when (hookItem) {
                is ClickableHookItem -> {
                    hookItem.setEnabledSilently(WePrefs.getBoolOrFalse(hookItem.path))
                    isEnabled =
                        (hookItem.isEnabled && process == hookItem.targetProcess) || hookItem.alwaysEnable
                }

                is SwitchHookItem -> {
                    hookItem.setEnabledSilently(WePrefs.getBoolOrFalse(hookItem.path))
                    isEnabled = hookItem.isEnabled && process == hookItem.targetProcess
                }

                is ApiHookItem -> {
                    isEnabled = process == hookItem.targetProcess
                }
            }

            if (isEnabled) {
                enabledItems.add(hookItem)
            }
        }

        // 执行加载（此时列表里只有 缓存有效 或 不需要缓存 的项）
        loadAllItems(enabledItems)
    }

    private fun handleBrokenItemsAsync(
        process: Int,
        appInfo: ApplicationInfo,
        brokenItems: List<IResolvesDex>
    ) {
        val noDexResolve = WePrefs.getBoolOrFalse(NO_DEX_RESOLVE)

        if (noDexResolve) {
            WeLogger.w(
                TAG,
                "dex resolution disabled; ${brokenItems.size} items will not load"
            )
            return
        }

        WeLogger.i(
            TAG,
            "launching background thread to repair ${brokenItems.size} items"
        )

        Thread {
            val startTime = System.currentTimeMillis()
            val timeoutMs = 90 * 1000L // 90 秒超时

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                // 只有主进程才处理 UI 弹窗
                if (process != TargetProcessUtils.PROC_MAIN) return@Thread

                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                val activity = RuntimeConfig.getLauncherUiActivity()
                if (activity != null) {
                    // 确保 Activity 已经初始化完成
                    runCatching {
                        Thread.sleep(1500)
                    }

                    Handler(Looper.getMainLooper()).post {
                        showComposeDialog(activity) {
                            DexResolverDialogContent(
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
                TAG,
                "wait for main activity timed out, dex resolution dialog skipped"
            )
        }.start()
    }

    /**
     * 从缓存加载 descriptor
     * @return 加载失败的项列表
     */
    private fun loadDescriptorsFromCache(items: List<IResolvesDex>): List<IResolvesDex> {
        val failedItems = mutableListOf<IResolvesDex>()

        items.forEach { item ->
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache != null) {
                    item.loadFromCache(cache)
                } else {
                    WeLogger.w(
                        TAG,
                        "Cache is null for ${(item as? BaseHookItem)?.path}"
                    )
                    failedItems.add(item)
                }
            } catch (e: Exception) {
                // 捕获所有异常，视为缓存损坏
                val path = (item as? BaseHookItem)?.path ?: "unknown"
                WeLogger.e(TAG, "Cache load failed for $path", e)

                // 尝试清理坏掉的缓存
                runCatching {
                    DexCacheManager.deleteCache(path)
                }
                failedItems.add(item)
            }
        }

        return failedItems
    }

    private fun loadAllItems(items: List<BaseHookItem>) {
        items.forEach { hookItem ->
            runCatching {
                WeLogger.i(TAG, "initializing ${hookItem.path}")
                hookItem.enable()
            }.onFailure { e ->
                WeLogger.e(
                    TAG,
                    "error initializing item ${hookItem.javaClass.simpleName}",
                    e
                )
            }
        }
    }
}