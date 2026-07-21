package dev.ujhhgtg.wekit.features.items.chat.panel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

internal const val PANEL_BULK_DOWNLOAD_CONCURRENCY = 4
internal const val PANEL_BULK_CONVERSION_CONCURRENCY = 2

/** Runs independent work on a fixed worker pool and serializes its progress callbacks. */
internal suspend fun <T, R> Iterable<T>.parallelForEachWithProgress(
    maxConcurrency: Int,
    transform: suspend (T) -> R,
    onItemComplete: suspend (completed: Int, total: Int, item: T, result: R) -> Unit,
) {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    val items = toList()
    if (items.isEmpty()) return

    val nextIndex = AtomicInteger()
    val progressMutex = Mutex()
    var completed = 0
    coroutineScope {
        List(minOf(maxConcurrency, items.size)) {
            launch {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val index = nextIndex.getAndIncrement()
                    if (index >= items.size) break
                    val item = items[index]
                    val result = transform(item)
                    currentCoroutineContext().ensureActive()
                    progressMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        completed++
                        onItemComplete(completed, items.size, item, result)
                    }
                }
            }
        }.joinAll()
    }
}
