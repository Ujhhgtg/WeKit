package dev.ujhhgtg.wekit.ui.content

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.dexkit.resolution.DexNodeResult
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionCoordinator
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionRegistry
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.withDexKitSuspending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface LocalDexProgress {
    val displayName: String
    data class Start(override val displayName: String) : LocalDexProgress
    data class Complete(override val displayName: String) : LocalDexProgress
    data class Failed(override val displayName: String, val error: Exception) : LocalDexProgress
}

internal data class LocalDexFailure(val displayName: String, val error: Exception)
internal data class LocalDexResolutionResult(val failures: List<LocalDexFailure>)

internal object LocalDexResolver {
    private const val TAG = "LocalDexResolver"

    suspend fun resolve(
        registry: DexResolutionRegistry,
        items: List<IResolveDex>,
        onProgress: suspend (LocalDexProgress) -> Unit,
    ): LocalDexResolutionResult = coroutineScope {
        val progressEvents = Channel<LocalDexProgress>(Channel.UNLIMITED)
        val reporter = launch {
            for (progress in progressEvents) onProgress(progress)
        }
        try {
            withDexKitSuspending { dexKit ->
                withContext(Dispatchers.IO) {
                    val coordinator = DexResolutionCoordinator(registry, dexKit)
                    val failures = mutableListOf<LocalDexFailure>()
                    coordinator.resolveOwners(
                        items,
                        onRootStart = { item ->
                            progressEvents.trySend(
                                LocalDexProgress.Start((item as BaseFeature).technicalPath),
                            ).getOrThrow()
                        },
                        onRootFinish = { item, rootResult ->
                            val displayName = (item as BaseFeature).technicalPath
                            val failureResult = rootResult.resultsByProducer.values.firstOrNull { result ->
                                result is DexNodeResult.Failed ||
                                    result is DexNodeResult.Resolved && result.diagnostic.status !in setOf(
                                        DexResolutionStatus.SUCCESS,
                                        DexResolutionStatus.EXPECTED_FAILURE,
                                    )
                            }
                            if (failureResult == null) {
                                progressEvents.trySend(LocalDexProgress.Complete(displayName)).getOrThrow()
                            } else {
                                val error = when (failureResult) {
                                    is DexNodeResult.Failed -> failureResult.error as? Exception
                                        ?: IllegalStateException(failureResult.error)
                                    is DexNodeResult.Resolved -> IllegalStateException(
                                        "Dex resolution completed with ${failureResult.diagnostic.status}",
                                    )
                                }
                                WeLogger.e(TAG, "failed to resolve: $displayName", error)
                                progressEvents.trySend(LocalDexProgress.Failed(displayName, error)).getOrThrow()
                                failures += LocalDexFailure(displayName, error)
                            }
                        },
                    )
                    DexCacheManager.saveResolvedOwners(registry, coordinator, items)
                    LocalDexResolutionResult(failures)
                }
            }
        } finally {
            progressEvents.close()
            reporter.join()
        }
    }
}
