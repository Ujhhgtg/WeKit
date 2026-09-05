package dev.ujhhgtg.wekit.dexkit.resolution

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import kotlin.time.TimeSource

sealed interface DexResolutionEvent {
    val item: IResolveDex

    data class Start(override val item: IResolveDex) : DexResolutionEvent
    data class Finished(
        override val item: IResolveDex,
        val error: Exception?,
        val elapsedMillis: Long,
    ) : DexResolutionEvent
}

/** Shared by Android and desktop validation. The caller owns the bridge until this returns. */
suspend fun resolveDexBatch(
    items: List<IResolveDex>,
    dexKit: DexKitBridge,
    host: DexHostMetadata,
    workerCount: Int = 4,
    onEvent: suspend (DexResolutionEvent) -> Unit,
) = coroutineScope {
    require(workerCount > 0)
    val roots = items.distinct()
    if (roots.isEmpty()) return@coroutineScope
    val batchContext = coroutineContext
    val coordinator = DexResolutionContext.newCoordinator(roots) { batchContext.ensureActive() }
    val next = AtomicInteger()
    val workers = minOf(workerCount, roots.size)
    val events = Channel<DexResolutionEvent>(workers * 2)
    launch {
        try {
            coroutineScope {
                repeat(workers) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            ensureActive()
                            val index = next.getAndIncrement()
                            if (index >= roots.size) break
                            val item = roots[index]
                            events.send(DexResolutionEvent.Start(item))
                            val started = TimeSource.Monotonic.markNow()
                            val error = try {
                                item.resolveAllDex(dexKit, host, coordinator)
                                null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                error
                            }
                            ensureActive()
                            events.send(DexResolutionEvent.Finished(
                                item, error, started.elapsedNow().inWholeMilliseconds,
                            ))
                        }
                    }
                }
            }
        } finally {
            events.close()
        }
    }
    for (event in events) {
        ensureActive()
        onEvent(event)
    }
}
