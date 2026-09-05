package dev.ujhhgtg.wekit.dexkit.resolution

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One execution per owner, including recursively discovered dependencies, for one batch. */
class ResolutionCoordinator<K : Any>(
    roots: Collection<K>,
    private val describe: (K) -> String,
    private val checkActive: () -> Unit = {},
) {
    private class State {
        var executor: Thread? = null
        var finished = false
        var failure: Throwable? = null
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val states = roots.associateWith { State() }.toMutableMap()
    private val waiting = mutableMapOf<Thread, K>()

    fun isOwnedByCurrentThread(key: K): Boolean = lock.withLock {
        states[key]?.executor === Thread.currentThread()
    }

    fun resolve(key: K, canReuse: () -> Boolean, execute: () -> Unit) {
        val thread = Thread.currentThread()
        val state = lock.withLock {
            checkActive()
            states.getOrPut(key) { State().apply { finished = canReuse() } }.also { state ->
                while (state.executor != null) {
                    check(state.executor !== thread) { "Circular Dex resolver dependency: ${describe(key)}" }
                    waiting[thread] = key
                    try {
                        checkNoWaitCycle(thread, key)
                        // Native queries are synchronous. Periodic cancellation checks let waiters
                        // leave even while the executing query has not returned to signal them yet.
                        changed.await(100, TimeUnit.MILLISECONDS)
                        checkActive()
                    } finally {
                        waiting.remove(thread)
                    }
                }
                state.failure?.let { throw it }
                if (state.finished) return
                checkActive()
                state.executor = thread
            }
        }
        var failure: Throwable? = null
        try {
            checkActive()
            execute()
            checkActive()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            lock.withLock {
                state.failure = failure
                state.finished = true
                state.executor = null
                changed.signalAll()
            }
        }
    }

    private fun checkNoWaitCycle(thread: Thread, key: K) {
        val chain = mutableListOf(key)
        var executor = states.getValue(key).executor
        while (executor != null) {
            check(executor !== thread) {
                "Concurrent Dex resolver wait cycle: " +
                    (chain + key).joinToString(" -> ", transform = describe)
            }
            val next = waiting[executor] ?: return
            chain += next
            executor = states.getValue(next).executor
        }
    }
}
