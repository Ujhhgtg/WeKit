package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.measureTimedValue

sealed interface LocalDexProgress {
    val displayName: String

    data class Start(override val displayName: String) : LocalDexProgress
    data class Complete(override val displayName: String) : LocalDexProgress
    data class Failed(
        override val displayName: String,
        val error: Exception,
    ) : LocalDexProgress
}

data class LocalDexFailure(
    val displayName: String,
    val error: Exception,
)

data class LocalDexResolutionResult(
    val failures: List<LocalDexFailure>,
)

object LocalDexResolver {
    private const val TAG = "LocalDexResolver"
    private const val WORKER_COUNT = 4
    private val resolutionMutex = Mutex()

    /**
     * Resolves [items] and persists each outcome: a cache on success, a failure record otherwise.
     * If the batch itself throws, every item without an outcome is recorded as failed with that
     * error before rethrowing, so the next startup does not ask for the same resolution again.
     */
    suspend fun resolve(
        items: List<IResolveDex>,
        onProgress: suspend (LocalDexProgress) -> Unit,
    ): LocalDexResolutionResult = resolutionMutex.withLock {
        withContext(Dispatchers.IO) {
            val roots = items.distinct()
            val finished = mutableSetOf<IResolveDex>()
            val (result, elapsed) = measureTimedValue {
                val failures = mutableMapOf<IResolveDex, LocalDexFailure>()
                try {
                    if (roots.isNotEmpty()) {
                        // Metadata reads (*Data replacement) need the DexSQL dataset even when a
                        // feature set has no SQL query delegate.
                        val dexSql = DexSqlBridge.openHost()
                        try {
                            resolveDexBatch(
                                roots,
                                DexHostMetadata.currentAndroidHost(),
                                WORKER_COUNT,
                                dexSql,
                            ) { event ->
                            val item = event.item
                            val displayName = (item as BaseFeature).technicalPath
                            when (event) {
                                is DexResolutionEvent.Start -> onProgress(LocalDexProgress.Start(displayName))
                                is DexResolutionEvent.Finished -> {
                                    currentCoroutineContext().ensureActive()
                                    val error = event.error ?: try {
                                        DexCacheManager.saveItemCache(item)
                                        null
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        error
                                    }
                                    if (error == null) {
                                        onProgress(LocalDexProgress.Complete(displayName))
                                    } else {
                                        WeLogger.e(TAG, "failed to resolve or save: $displayName", error)
                                        DexCacheManager.saveItemFailure(item, error)
                                        failures[item] = LocalDexFailure(displayName, error)
                                        onProgress(LocalDexProgress.Failed(displayName, error))
                                    }
                                    finished += item
                                }
                            }
                            }
                        } finally {
                            dexSql?.close()
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordUnfinishedFailures(roots - finished, error)
                    throw error
                }
                LocalDexResolutionResult(roots.mapNotNull(failures::get))
            }
            WeLogger.i(TAG, "resolving all local Dex items took $elapsed " +
                "(workers=${minOf(WORKER_COUNT, roots.size)}, items=${roots.size}, failures=${result.failures.size})")
            result
        }
    }

    private fun recordUnfinishedFailures(items: Collection<IResolveDex>, error: Exception) {
        items.forEach { item ->
            runCatching { DexCacheManager.saveItemFailure(item, error) }
                .onFailure { WeLogger.e(TAG, "failed to record Dex failure for ${(item as BaseFeature).technicalPath}", it) }
        }
    }
}
