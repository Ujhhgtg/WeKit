package dev.ujhhgtg.wekit.ui.content

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.dexkit.resolution.resolveAllDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.withDexKitSuspending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

internal sealed interface LocalDexProgress {
    val displayName: String

    data class Start(override val displayName: String) : LocalDexProgress
    data class Complete(override val displayName: String) : LocalDexProgress
    data class Failed(
        override val displayName: String,
        val error: Exception,
    ) : LocalDexProgress
}

internal data class LocalDexFailure(
    val displayName: String,
    val error: Exception,
)

internal data class LocalDexResolutionResult(
    val failures: List<LocalDexFailure>,
)

internal object LocalDexResolver {
    private const val TAG = "LocalDexResolver"

    suspend fun resolve(
        items: List<IResolveDex>,
        onProgress: suspend (LocalDexProgress) -> Unit,
    ): LocalDexResolutionResult = coroutineScope {
        val results = withDexKitSuspending { dexKit ->
            items.asFlow()
                .map { item ->
                    async(Dispatchers.IO) {
                        val displayName = (item as BaseFeature).technicalPath
                        onProgress(LocalDexProgress.Start(displayName))
                        try {
                            item.resolveAllDex(dexKit)
                            DexCacheManager.saveItemCache(item)
                            onProgress(LocalDexProgress.Complete(displayName))
                            null
                        } catch (error: Exception) {
                            WeLogger.e(TAG, "failed to resolve: $displayName", error)
                            onProgress(LocalDexProgress.Failed(displayName, error))
                            LocalDexFailure(displayName, error)
                        }
                    }
                }
                .buffer(8)
                .map { it.await() }
                .toList()
        }
        LocalDexResolutionResult(results.filterNotNull())
    }
}
