package dev.ujhhgtg.wekit.agent.trigger

import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Per-event-trigger buffer implementing the three flush rules (§ event buffer):
 *  1. **debounce** — each new event (re)starts a [debounceMillis] silence timer; flush when it elapses.
 *  2. **cap** — flush immediately once [maxEvents] have accumulated (don't keep waiting).
 *  3. **max-wait** — flush unconditionally [maxWaitMillis] after the FIRST buffered event, even if
 *     events keep arriving (bounds worst-case latency under a steady stream).
 *
 * Events are appended via [offer] (cheap, non-suspending from the DB-listener thread's perspective —
 * it launches into [scope]). When a flush fires, [onFlush] is invoked with the drained, time-ordered
 * events. All state is guarded by a [Mutex]; timers are cancellable [Job]s in [scope].
 *
 * SCHEDULE triggers do not use this — they fire immediately through the dispatcher.
 */
class TriggerBuffer(
    private val triggerId: String,
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val maxEvents: Int,
    private val maxWaitMillis: Long,
    private val onFlush: suspend (List<TriggeredEvent>) -> Unit,
) {
    private val mutex = Mutex()
    private val pending = ArrayList<TriggeredEvent>()
    private var debounceJob: Job? = null
    private var maxWaitJob: Job? = null

    /** Adds an event to the buffer, (re)arming the debounce timer and, on the first event, the max-wait timer. */
    fun offer(event: TriggeredEvent) {
        scope.launch {
            mutex.withLock {
                pending.add(event)

                // cap rule: flush now if we've hit the ceiling.
                if (pending.size >= maxEvents) {
                    flushLocked("cap")
                    return@launch
                }

                // debounce rule: restart the silence timer.
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debounceMillis.milliseconds)
                    mutex.withLock { flushLocked("debounce") }
                }

                // max-wait rule: arm once, on the first buffered event.
                if (maxWaitJob == null) {
                    maxWaitJob = scope.launch {
                        delay(maxWaitMillis.milliseconds)
                        mutex.withLock { flushLocked("max-wait") }
                    }
                }
            }
        }
    }

    /** Drains and dispatches the buffer. Caller must hold [mutex]. */
    private suspend fun flushLocked(reason: String) {
        if (pending.isEmpty()) return
        val batch = pending.sortedBy { it.at }
        pending.clear()
        debounceJob?.cancel(); debounceJob = null
        maxWaitJob?.cancel(); maxWaitJob = null
        WeLogger.i(TAG, "flushing trigger=$triggerId reason=$reason count=${batch.size}")
        // Dispatch on a DETACHED coroutine: the debounce / max-wait timers invoke flushLocked from
        // within their own Job, and the cancel() calls above cancel that very Job — so running the
        // suspend onFlush inline would immediately hit the cancelled state (JobCancellationException).
        // Launching on [scope] decouples the dispatch from the timer job's lifecycle.
        scope.launch {
            runCatching { onFlush(batch) }
                .onFailure { WeLogger.e(TAG, "onFlush failed for trigger=$triggerId", it) }
        }
    }

    /** Cancels any pending timers and drops buffered events (trigger disabled/deleted/reloaded). */
    fun cancel() {
        debounceJob?.cancel(); debounceJob = null
        maxWaitJob?.cancel(); maxWaitJob = null
        scope.launch { mutex.withLock { pending.clear() } }
    }

    private companion object {
        const val TAG = "TriggerBuffer"
    }
}
