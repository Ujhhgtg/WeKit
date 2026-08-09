package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withThreadLock

internal sealed interface OriginRequestTerminal<out T> {
    data class Completed<T>(val result: Result<T>) : OriginRequestTerminal<T>

    data object Superseded : OriginRequestTerminal<Nothing>
}

/** Delivers the terminal owned by one origin request at most once. */
internal class OriginTerminalDelivery<T>(
    private val owner: (OriginRequestTerminal<T>) -> Unit,
) {
    private var delivered = false

    fun deliver(terminal: OriginRequestTerminal<T>): Boolean {
        synchronized(this) {
            if (delivered) return false
            delivered = true
        }
        owner(terminal)
        return true
    }
}

/**
 * Linearizes request mutation with the final Main delivery without running the owner under a lock.
 * External mutators wait for the callback to finish; callback-thread mutation remains reentrant.
 */
internal class OriginRequestBoundary {
    private val lock = ReentrantLock()
    private val deliveryFinished = lock.newCondition()
    private var deliveryThread: Thread? = null
    private var deliveryDepth = 0

    fun <R> mutate(action: () -> R): R = lock.withThreadLock {
        awaitExternalDelivery()
        action()
    }

    fun <T> deliverCurrent(
        delivery: OriginTerminalDelivery<T>,
        terminal: OriginRequestTerminal<T>,
        isCurrent: () -> Boolean,
    ): Boolean {
        val thread = Thread.currentThread()
        var deliveryStarted = false
        return try {
            val checkedTerminal = lock.withThreadLock {
                awaitExternalDelivery()
                deliveryThread = thread
                deliveryDepth++
                deliveryStarted = true
                if (isCurrent()) terminal else OriginRequestTerminal.Superseded
            }
            delivery.deliver(checkedTerminal)
        } finally {
            if (deliveryStarted) {
                lock.withThreadLock {
                    check(deliveryThread === thread)
                    deliveryDepth--
                    if (deliveryDepth == 0) {
                        deliveryThread = null
                        deliveryFinished.signalAll()
                    }
                }
            }
        }
    }

    private fun awaitExternalDelivery() {
        val thread = Thread.currentThread()
        while (deliveryThread != null && deliveryThread !== thread) {
            deliveryFinished.awaitUninterruptibly()
        }
    }
}

/** Computes one typed origin terminal across the worker-side staleness checkpoints. */
internal class OriginRequestExecution<T, S>(
    private val isCurrent: () -> Boolean,
    private val lifecycleMutex: Mutex,
) {
    suspend fun execute(
        reconcile: suspend () -> OriginRequestTerminal<T>,
        snapshot: () -> S,
        publish: (Result<T>, S) -> Boolean,
    ): OriginRequestTerminal<T> {
        if (!isCurrent()) return OriginRequestTerminal.Superseded // Pre-queue.
        val reconciled = lifecycleMutex.withLock {
            if (!isCurrent()) return@withLock OriginRequestTerminal.Superseded
            reconcile()
        }
        val completed = when (reconciled) {
            is OriginRequestTerminal.Completed -> reconciled
            OriginRequestTerminal.Superseded -> return OriginRequestTerminal.Superseded
        }
        if (!isCurrent()) return OriginRequestTerminal.Superseded // Post-reconcile.
        if (!isCurrent()) return OriginRequestTerminal.Superseded // Pre-snapshot.
        val status = snapshot()
        if (!isCurrent()) return OriginRequestTerminal.Superseded // Pre-publish.
        if (!publish(completed.result, status)) return OriginRequestTerminal.Superseded
        return completed
    }
}

/**
 * Serializes ownership of the process-global native handle by configuration generation.
 * Native operations execute while holding this monitor, so a stale cleanup cannot race a new start.
 */
internal class TunnelNativeLease {
    private var currentGeneration = 0L
    private var ownerGeneration: Long? = null
    private var activeRequestGeneration: Long? = null
    private var networkEpoch = 0L
    private var nativeSessionEpoch = 0L
    private var verifiableNativeSessionEpoch: Long? = null

    @Synchronized
    fun advance(generation: Long): Boolean {
        if (generation < currentGeneration) return false
        if (generation > currentGeneration) {
            if (ownerGeneration == currentGeneration) ownerGeneration = generation
            activeRequestGeneration = null
        }
        currentGeneration = generation
        return true
    }

    @Synchronized
    fun activateRequest(generation: Long, preserveNativeSession: Boolean = false): Boolean {
        if (currentGeneration != generation) return false
        activeRequestGeneration = generation
        if (!preserveNativeSession) verifiableNativeSessionEpoch = null
        networkEpoch++
        return true
    }

    @Synchronized
    fun clearRequest(generation: Long): Boolean {
        if (currentGeneration != generation || activeRequestGeneration != generation) return false
        activeRequestGeneration = null
        networkEpoch++
        return true
    }

    /** Invalidates all verification work synchronously, before callback teardown is dispatched. */
    @Synchronized
    fun invalidateNetwork(): Long? {
        networkEpoch++
        verifiableNativeSessionEpoch = null
        return activeRequestGeneration?.takeIf { it == currentGeneration }
    }

    @Synchronized
    fun startIfCurrent(generation: Long, start: () -> Boolean): Boolean {
        if (currentGeneration != generation || ownerGeneration != null) return false
        if (!start()) return false
        ownerGeneration = generation
        nativeSessionEpoch++
        verifiableNativeSessionEpoch = nativeSessionEpoch
        return true
    }

    @Synchronized
    fun stopIfOwner(generation: Long, stop: () -> Unit): Boolean {
        if (currentGeneration != generation || ownerGeneration != generation) return false
        ownerGeneration = null
        nativeSessionEpoch++
        verifiableNativeSessionEpoch = null
        stop()
        return true
    }

    /** Stops whichever older owner preceded [generation], but only while that generation is current. */
    @Synchronized
    fun stopForReplacement(generation: Long, stop: () -> Unit): Boolean {
        if (currentGeneration != generation) return false
        verifiableNativeSessionEpoch = null
        if (ownerGeneration != null) {
            ownerGeneration = null
            nativeSessionEpoch++
            stop()
        }
        return true
    }

    @Synchronized
    fun captureVerification(generation: Long): TunnelVerificationTicket? {
        if (
            currentGeneration != generation || activeRequestGeneration != generation ||
            ownerGeneration != generation || verifiableNativeSessionEpoch != nativeSessionEpoch
        ) {
            return null
        }
        return TunnelVerificationTicket(generation, networkEpoch, nativeSessionEpoch)
    }

    @Synchronized
    fun isVerificationCurrent(ticket: TunnelVerificationTicket): Boolean =
        verificationMatches(ticket)

    @Synchronized
    fun runIfVerificationCurrent(ticket: TunnelVerificationTicket, action: () -> Unit): Boolean {
        if (!verificationMatches(ticket)) return false
        action()
        return true
    }

    /**
     * Commits verified state under the same monitor used by network invalidation. The repeated
     * checks document each security-sensitive boundary and also protect against reentrant actions.
     */
    @Synchronized
    fun commitVerification(
        ticket: TunnelVerificationTicket,
        writeCredential: (() -> Boolean)?,
        clearPendingToken: (() -> Unit)?,
        publishConnected: () -> Unit,
    ): TunnelVerificationCommit {
        if (!verificationMatches(ticket)) return TunnelVerificationCommit.STALE
        if (writeCredential != null) {
            if (!verificationMatches(ticket)) return TunnelVerificationCommit.STALE
            if (!writeCredential()) return TunnelVerificationCommit.CREDENTIAL_FAILURE
            if (!verificationMatches(ticket)) return TunnelVerificationCommit.STALE
            clearPendingToken!!()
        }
        if (!verificationMatches(ticket)) return TunnelVerificationCommit.STALE
        publishConnected()
        return TunnelVerificationCommit.COMMITTED
    }

    private fun verificationMatches(ticket: TunnelVerificationTicket): Boolean =
        currentGeneration == ticket.generation &&
            activeRequestGeneration == ticket.generation &&
            ownerGeneration == ticket.generation &&
            networkEpoch == ticket.networkEpoch &&
            nativeSessionEpoch == ticket.nativeSessionEpoch &&
            verifiableNativeSessionEpoch == ticket.nativeSessionEpoch

    @Synchronized
    fun ownerGeneration(): Long? = ownerGeneration
}

internal data class TunnelVerificationTicket(
    val generation: Long,
    val networkEpoch: Long,
    val nativeSessionEpoch: Long,
)

internal enum class TunnelVerificationCommit {
    COMMITTED,
    STALE,
    CREDENTIAL_FAILURE,
}

/** Canonical identity used by runtime replacement decisions; TOKEN hostnames compare semantically. */
internal data class TunnelRuntimeIdentity(
    val mode: ReadReceiptsTunnelMode,
    val hostname: String?,
) {
    companion object {
        fun create(mode: ReadReceiptsTunnelMode, hostname: String): TunnelRuntimeIdentity? =
            if (mode == ReadReceiptsTunnelMode.TOKEN) {
                ReadReceiptsTunnelService.canonicalPublicRoot(hostname)?.let {
                    TunnelRuntimeIdentity(mode, it)
                }
            } else {
                TunnelRuntimeIdentity(mode, null)
            }
    }
}

internal fun tunnelRuntimeChanged(
    previousMode: ReadReceiptsTunnelMode,
    previousHostname: String,
    candidateMode: ReadReceiptsTunnelMode,
    candidateHostname: String,
): Boolean = TunnelRuntimeIdentity.create(previousMode, previousHostname) !=
    TunnelRuntimeIdentity.create(candidateMode, candidateHostname)

internal data class StopRegistration(
    val generation: Long,
    val shouldSend: Boolean,
)

internal data class StopDrain(
    val matched: Boolean,
    val callbacks: List<() -> Unit> = emptyList(),
)

/** Collects concurrent stop callers and lets exactly one terminal path drain their callbacks. */
internal class TunnelStopCompletion {
    private data class Pending(
        val generation: Long,
        val callbacks: MutableList<() -> Unit>,
    )

    private var pending: Pending? = null
    private var completedGeneration: Long? = null

    @Synchronized
    fun register(
        callback: (() -> Unit)?,
        generationFactory: () -> Long,
    ): StopRegistration {
        pending?.let { current ->
            if (callback != null) current.callbacks += callback
            return StopRegistration(current.generation, shouldSend = false)
        }
        val generation = generationFactory()
        pending = Pending(
            generation,
            mutableListOf<() -> Unit>().apply { if (callback != null) add(callback) },
        )
        return StopRegistration(generation, shouldSend = true)
    }

    @Synchronized
    fun complete(generation: Long): StopDrain {
        val current = pending ?: return StopDrain(matched = completedGeneration == generation)
        if (current.generation != generation) return StopDrain(matched = false)
        pending = null
        completedGeneration = generation
        return StopDrain(matched = true, callbacks = current.callbacks.toList())
    }

    @Synchronized
    fun pendingGeneration(): Long? = pending?.generation
}

/** Coalesces one higher-layer operation while preserving every caller's result callback. */
internal class CoalescedResultCallbacks<T> {
    private var callbacks: MutableList<(Result<T>) -> Unit>? = null

    @Synchronized
    fun register(callback: ((Result<T>) -> Unit)?): Boolean {
        val current = callbacks
        if (current != null) {
            if (callback != null) current += callback
            return false
        }
        callbacks = mutableListOf<(Result<T>) -> Unit>().apply {
            if (callback != null) add(callback)
        }
        return true
    }

    fun complete(result: Result<T>): Int {
        val completed = synchronized(this) {
            val current = callbacks ?: return 0
            callbacks = null
            current.toList()
        }
        completed.forEach { callback -> callback(result) }
        return completed.size
    }
}

/** Prevents late ACK/timeout events from completing or clearing a replacement START command. */
internal class TunnelHandoffGate {
    private var pendingGeneration: Long? = null

    @Synchronized
    fun begin(generation: Long): Long? = pendingGeneration.also {
        pendingGeneration = generation
    }

    /** Lets synchronous rollback allocate its generation before the replacement is numbered. */
    fun beginAfterSuperseding(
        pendingGeneration: () -> Long?,
        supersede: (Long) -> Unit,
        generationFactory: () -> Long,
    ): Long {
        while (true) {
            val pending = pendingGeneration() ?: break
            supersede(pending)
        }
        return generationFactory().also(::begin)
    }

    @Synchronized
    fun complete(generation: Long): Boolean = clearIfCurrent(generation)

    @Synchronized
    fun fail(generation: Long): Boolean = clearIfCurrent(generation)

    @Synchronized
    fun pendingGeneration(): Long? = pendingGeneration

    private fun clearIfCurrent(generation: Long): Boolean {
        if (pendingGeneration != generation) return false
        pendingGeneration = null
        return true
    }
}
