package dev.ujhhgtg.wekit.hooks.utils

import android.content.pm.ApplicationInfo
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.ujhhgtg.wekit.constants.PreferenceKeys.NO_DEX_RESOLVE
import dev.ujhhgtg.wekit.core.model.ApiHookItem
import dev.ujhhgtg.wekit.core.model.BaseHookItem
import dev.ujhhgtg.wekit.core.model.ClickableHookItem
import dev.ujhhgtg.wekit.core.model.SwitchHookItem
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.DexResolverDialogContent
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object HookItemsLoader {

    private val TAG = nameof(HookItemsLoader)

    fun loadHookItems(process: Int) {
        val appInfo = HostInfo.appInfo
        loadHookItems(process, appInfo)
    }

    fun loadHookItems(
        process: Int,
        appInfo: ApplicationInfo
    ) {
        val allHookItems = HookItemsFactory.getItems()
        val allDexResolvingItems = allHookItems.filterIsInstance<IResolvesDex>()
        val outdatedItems = DexCacheManager.getOutdatedItems(allDexResolvingItems)
        val validItems = allDexResolvingItems.filterNot { outdatedItems.contains(it) }

        if (outdatedItems.isNotEmpty())
            WeLogger.i(
                TAG,
                "found ${validItems.size} valid items, ${outdatedItems.size} outdated items"
            )

        val corruptedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + corruptedItems).distinct()

        if (allBrokenItems.isNotEmpty()) {
            handleBrokenItems(process, appInfo, allBrokenItems)
        }

        val elapsed = measureTime {
            allHookItems.forEach { hookItem ->
                if (hookItem is IResolvesDex && allBrokenItems.contains(hookItem)) {
                    WeLogger.w(
                        TAG,
                        "skipping ${hookItem.path} due to missing or invalid cache"
                    )
                    return@forEach
                }

                when (hookItem) {
                    is ClickableHookItem -> {
                        hookItem.setEnabledSilently(WePrefs.getBoolOrFalse(hookItem.path))
                        if (hookItem.isEnabled || hookItem.alwaysRun) hookItem.enable(process)
                    }

                    is SwitchHookItem -> {
                        hookItem.setEnabledSilently(WePrefs.getBoolOrFalse(hookItem.path))
                        if (hookItem.isEnabled) hookItem.enable(process)
                    }

                    is ApiHookItem -> {
                        hookItem.enable(process)
                    }
                }
            }
        }
        WeLogger.i(TAG, "enabling all hook items took $elapsed")
    }

    private fun handleBrokenItems(
        process: Int,
        appInfo: ApplicationInfo,
        brokenItems: List<IResolvesDex>
    ) {
        if (WePrefs.getBoolOrFalse(NO_DEX_RESOLVE)) return
        if (process != TargetProcesses.PROC_MAIN) return

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val activity = withTimeoutOrNull(90_000L.milliseconds) {
                while (true) {
                    delay(200.milliseconds)
                    RuntimeConfig.getLauncherUiActivity()?.let { return@withTimeoutOrNull it }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            if (activity == null) {
                WeLogger.e(TAG, "wait for main activity timed out, dex resolution dialog skipped")
                return@launch
            }

            delay(1_500) // allow Activity to finish initializing

            withContext(Dispatchers.Main) {
                showComposeDialog(activity) {
                    DexResolverDialogContent(
                        activity,
                        brokenItems,
                        appInfo,
                        CoroutineScope(Dispatchers.Main + SupervisorJob()),
                        onDismiss = dismiss
                    )
                }
            }
        }
    }

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
                        "cache is null for ${(item as? BaseHookItem)?.path}"
                    )
                    failedItems.add(item)
                }
            } catch (e: Exception) {
                val path = (item as? BaseHookItem)?.path ?: "unknown"
                WeLogger.e(TAG, "cache load failed for $path", e)

                runCatching {
                    DexCacheManager.deleteCache(path)
                }
                failedItems.add(item)
            }
        }

        return failedItems
    }
}
