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
    ): LocalDexResolutionResult {
        items.forEach { onProgress(LocalDexProgress.Start((it as BaseFeature).technicalPath)) }
        return withDexKitSuspending { dexKit ->
            withContext(Dispatchers.IO) {
                val coordinator = DexResolutionCoordinator(registry, dexKit)
                val batch = coordinator.resolveOwners(items)
                DexCacheManager.saveResolvedOwners(registry, coordinator, items)
                val failures = mutableListOf<LocalDexFailure>()
                items.forEach { item ->
                    val displayName = (item as BaseFeature).technicalPath
                    val producerIds = item.dexDelegates.map { registry.producerOf(it).stableId }.toSet()
                    val failureResult = producerIds.asSequence()
                        .mapNotNull(batch.resultsByProducer::get)
                        .firstOrNull { result ->
                            result is DexNodeResult.Failed ||
                                result is DexNodeResult.Resolved && result.diagnostic.status !in setOf(
                                    DexResolutionStatus.SUCCESS,
                                    DexResolutionStatus.EXPECTED_FAILURE,
                                )
                        }
                    if (failureResult == null) {
                        onProgress(LocalDexProgress.Complete(displayName))
                    } else {
                        val error = when (failureResult) {
                            is DexNodeResult.Failed -> failureResult.error as? Exception
                                ?: IllegalStateException(failureResult.error)
                            is DexNodeResult.Resolved -> IllegalStateException(
                                "Dex resolution completed with ${failureResult.diagnostic.status}",
                            )
                        }
                        WeLogger.e(TAG, "failed to resolve: $displayName", error)
                        onProgress(LocalDexProgress.Failed(displayName, error))
                        failures += LocalDexFailure(displayName, error)
                    }
                }
                LocalDexResolutionResult(failures)
            }
        }
    }
}
