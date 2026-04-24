package dev.ujhhgtg.wekit.hooks.core

import android.content.pm.ApplicationInfo
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.constants.PreferenceKeys
import dev.ujhhgtg.wekit.constants.PreferenceKeys.NO_DEX_RESOLVE
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.DexResolverDialogContent
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object HookItemsLoader {

    private val TAG = nameOf(HookItemsLoader)

    fun loadHookItems(appInfo: ApplicationInfo) {
        val allHookItems = HookItemsProvider.ALL_HOOK_ITEMS
        val allDexItems = allHookItems.filterIsInstance<IResolvesDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(appInfo, allBrokenItems)

        val elapsed = measureTime {
            allHookItems.forEach { hookItem ->
                val isBroken = hookItem is IResolvesDex && allBrokenItems.contains(hookItem)

                if (isBroken) {
                    // A partially-loaded item: delegates that loaded from cache are usable,
                    // but we cannot guarantee all dependencies are present, so skip activation.
                    WeLogger.w(TAG, "skipping ${(hookItem as BaseHookItem).path} — incomplete cache, awaiting re-resolution")
                    return@forEach
                }

                hookItem.startup()
            }
        }
        WeLogger.i(TAG, "enabling all hook items took $elapsed")

        if (TargetProcesses.isInMain && WePrefs.getBoolOrFalse(PreferenceKeys.SHOW_TOAST_ON_STARTUP_COMPLETE)) {
            showToast("WeKit 加载成功!")
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    private fun loadDescriptorsFromCache(items: List<IResolvesDex>): List<IResolvesDex> {
        val failedItems = mutableListOf<IResolvesDex>()

        for (item in items) {
            val path = (item as? BaseHookItem)?.path ?: "unknown"
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                // loadFromCache 逐委托加载；返回未命中的 key 集合
                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    failedItems += item
                    // 已命中的委托此时已经可用；hook 仍然跳过（见 loadHookItems），
                    // 等 DexKit 把缺失的部分补齐、cache 更新后下次启动即完整。
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache(path) }
                failedItems += item
            }
        }

        return failedItems
    }

    private fun handleBrokenItems(
        appInfo: ApplicationInfo,
        brokenItems: List<IResolvesDex>
    ) {
        if (WePrefs.getBoolOrFalse(NO_DEX_RESOLVE)) return
        if (!TargetProcesses.isInMain) return

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val activity = withTimeoutOrNull(90_000L.milliseconds) {
                while (true) {
                    delay(200.milliseconds)
                    LauncherUI.getInstance()?.let { return@withTimeoutOrNull it }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            if (activity == null) {
                WeLogger.e(TAG, "wait for main activity timed out, dex resolution dialog skipped")
                return@launch
            }

            delay(1_500.milliseconds)

            withContext(Dispatchers.Main) {
                showComposeDialog(activity) {
                    DexResolverDialogContent(
                        activity,
                        brokenItems,
                        appInfo,
                        MainScope(),
                        dialog,
                        onDismiss
                    )
                }
            }
        }
    }
}
