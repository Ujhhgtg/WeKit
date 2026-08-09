package dev.ujhhgtg.wekit.features.items.chat

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
