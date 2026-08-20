package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicBoolean

internal class HomeSidePanelRequestPool<K, V>(
    private val ownerScope: CoroutineScope,
) {

    private class Entry<V>(
        val request: Deferred<V>,
        var subscribers: Int,
    )

    private val lock = Any()
    private val entries = mutableMapOf<K, Entry<V>>()
    private var closed = false

    fun subscribe(key: K, request: suspend () -> V): Subscription<V> {
        val entry = synchronized(lock) {
            check(!closed) { "Request pool is closed" }
            entries[key]?.also { it.subscribers++ } ?: Entry(
                request = ownerScope.async(start = CoroutineStart.LAZY) { request() },
                subscribers = 1,
            ).also { entries[key] = it }
        }
        entry.request.start()
        return Subscription(entry.request) { release(key, entry) }
    }

    suspend fun await(key: K, request: suspend () -> V): V =
        subscribe(key, request).await()

    fun close() {
        val requests = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.map(Entry<V>::request).also { entries.clear() }
        }
        requests.forEach(Deferred<V>::cancel)
    }

    private fun release(key: K, entry: Entry<V>) {
        val cancel = synchronized(lock) {
            if (entries[key] !== entry) return
            check(entry.subscribers > 0) { "Request subscription released more than once" }
            entry.subscribers--
            if (entry.subscribers == 0) {
                entries.remove(key)
                !entry.request.isCompleted
            } else {
                false
            }
        }
        if (cancel) entry.request.cancel()
    }

    internal class Subscription<V>(
        private val request: Deferred<V>,
        private val release: () -> Unit,
    ) {
        private val awaited = AtomicBoolean()

        suspend fun await(): V {
            check(awaited.compareAndSet(false, true)) { "A request subscription can only be awaited once" }
            return try {
                request.await()
            } finally {
                release()
            }
        }
    }
}
