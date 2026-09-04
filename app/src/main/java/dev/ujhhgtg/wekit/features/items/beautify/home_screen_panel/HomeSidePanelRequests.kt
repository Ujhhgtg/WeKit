package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class HomeSidePanelRequestPool<K, V>(
    private val ownerScope: CoroutineScope,
) {

    private sealed interface Entry<V>

    private class Active<V>(
        val request: Deferred<V>,
        var subscribers: Int,
    ) : Entry<V>

    private class Retiring<V>(
        val request: Deferred<V>,
        val completed: CompletableDeferred<Unit>,
    ) : Entry<V>

    private val lock = Any()
    private val entries = mutableMapOf<K, Entry<V>>()
    private var closed = false

    suspend fun subscribe(key: K, request: suspend () -> V): Subscription<V> {
        while (true) {
            var retirement: CompletableDeferred<Unit>? = null
            val active = synchronized(lock) {
                check(!closed) { "Request pool is closed" }
                when (val current = entries[key]) {
                    is Active -> current.also { it.subscribers++ }
                    is Retiring -> {
                        retirement = current.completed
                        null
                    }

                    null -> Active(
                        request = ownerScope.async(start = CoroutineStart.LAZY) { request() },
                        subscribers = 1,
                    ).also { entries[key] = it }
                }
            }
            if (active != null) {
                active.request.start()
                return Subscription(active.request) { release(key, active) }
            }
            retirement!!.await()
        }
    }

    suspend fun await(key: K, request: suspend () -> V): V =
        subscribe(key, request).await()

    fun close() {
        val retired = mutableListOf<CompletableDeferred<Unit>>()
        val requests = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.map { entry ->
                when (entry) {
                    is Active -> entry.request
                    is Retiring -> entry.request.also { retired += entry.completed }
                }
            }.also { entries.clear() }
        }
        requests.forEach(Deferred<V>::cancel)
        retired.forEach { it.complete(Unit) }
    }

    private fun release(key: K, entry: Active<V>) {
        val retiring = synchronized(lock) {
            if (entries[key] !== entry) return
            check(entry.subscribers > 0) { "Request subscription released more than once" }
            entry.subscribers--
            if (entry.subscribers == 0) {
                if (entry.request.isCompleted) {
                    entries.remove(key)
                    null
                } else {
                    Retiring(entry.request, CompletableDeferred()).also { entries[key] = it }
                }
            } else {
                null
            }
        }
        if (retiring == null) return
        entry.request.cancel()
        ownerScope.launch {
            try {
                entry.request.join()
            } finally {
                finishRetirement(key, retiring)
            }
        }
    }

    private fun finishRetirement(key: K, retiring: Retiring<V>) {
        synchronized(lock) {
            if (entries[key] === retiring) entries.remove(key)
        }
        retiring.completed.complete(Unit)
    }

    class Subscription<V>(
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
