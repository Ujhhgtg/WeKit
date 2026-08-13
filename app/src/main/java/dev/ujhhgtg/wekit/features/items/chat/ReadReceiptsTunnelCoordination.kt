package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
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

/** Keeps visible-tunnel replacement distinct from genuine handoff completion. */
internal class TunnelHandoffTerminalDelivery(
    owner: (OriginRequestTerminal<Unit>) -> Unit,
) {
    private val delivery = OriginTerminalDelivery(owner)

    fun complete(result: Result<Unit>): Boolean =
        delivery.deliver(OriginRequestTerminal.Completed(result))

    fun supersede(): Boolean = delivery.deliver(OriginRequestTerminal.Superseded)
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
    fun advance(generation: Long, transition: () -> Unit): Boolean =
        advanceLocked(generation, transition)

    @Synchronized
    fun advanceAndReserve(
        generation: Long,
        transition: () -> Unit,
    ): TunnelCandidateReservation? {
        if (!advanceLocked(generation, transition)) return null
        return TunnelCandidateReservation(generation, networkEpoch)
    }

    private fun advanceLocked(generation: Long, transition: () -> Unit): Boolean {
        if (generation < currentGeneration) return false
        if (generation > currentGeneration) {
            if (ownerGeneration == currentGeneration) ownerGeneration = generation
        }
        activeRequestGeneration = null
        networkEpoch++
        currentGeneration = generation
        transition()
        return true
    }

    @Synchronized
    fun isReservationCurrent(reservation: TunnelCandidateReservation): Boolean =
        reservationMatches(reservation)

    @Synchronized
    fun activateReservedRequest(reservation: TunnelCandidateReservation): Boolean {
        if (!reservationMatches(reservation)) return false
        activeRequestGeneration = reservation.generation
        verifiableNativeSessionEpoch = null
        return true
    }

    @Synchronized
    fun activateRequest(generation: Long): Boolean {
        if (currentGeneration != generation) return false
        activeRequestGeneration = generation
        verifiableNativeSessionEpoch = null
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
    fun invalidateNetwork(): TunnelNetworkInvalidationTicket? {
        networkEpoch++
        verifiableNativeSessionEpoch = null
        val owner = ownerGeneration ?: return null
        return TunnelNetworkInvalidationTicket(owner, nativeSessionEpoch)
    }

    /** Stops the invalidated native session even if its request generation was transferred. */
    @Synchronized
    fun stopInvalidatedSession(
        ticket: TunnelNetworkInvalidationTicket,
        stop: () -> Unit,
        publishReconnecting: (Long) -> Unit,
    ): Long? {
        if (
            ownerGeneration == null || nativeSessionEpoch != ticket.nativeSessionEpoch ||
            verifiableNativeSessionEpoch != null
        ) {
            return null
        }
        val stoppedGeneration = activeRequestGeneration ?: ownerGeneration!!
        ownerGeneration = null
        nativeSessionEpoch++
        try {
            stop()
        } finally {
            publishReconnecting(stoppedGeneration)
        }
        return stoppedGeneration
    }

    /** Runs an idempotent administrative action without allocating a configuration generation. */
    @Synchronized
    fun withCurrentGeneration(
        generation: Long,
        action: (TunnelNativeSessionState) -> Unit,
    ): Boolean {
        if (currentGeneration != generation) return false
        val ownerActive = ownerGeneration == generation && activeRequestGeneration == generation
        action(
            TunnelNativeSessionState(
                ownerActive = ownerActive,
                verifiable = ownerActive && verifiableNativeSessionEpoch == nativeSessionEpoch,
            ),
        )
        return true
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
    fun startReservedIfCurrent(
        reservation: TunnelCandidateReservation,
        start: () -> Boolean,
    ): Boolean {
        if (
            !reservationMatches(reservation) ||
            activeRequestGeneration != reservation.generation ||
            ownerGeneration != null
        ) {
            return false
        }
        if (!start()) return false
        ownerGeneration = reservation.generation
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
    fun captureReservedVerification(
        reservation: TunnelCandidateReservation,
    ): TunnelVerificationTicket? {
        if (
            !reservationMatches(reservation) ||
            activeRequestGeneration != reservation.generation ||
            ownerGeneration != reservation.generation ||
            verifiableNativeSessionEpoch != nativeSessionEpoch
        ) {
            return null
        }
        return TunnelVerificationTicket(
            reservation.generation,
            reservation.networkEpoch,
            nativeSessionEpoch,
        )
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

    private fun reservationMatches(reservation: TunnelCandidateReservation): Boolean =
        currentGeneration == reservation.generation && networkEpoch == reservation.networkEpoch

    @Synchronized
    fun ownerGeneration(): Long? = ownerGeneration
}

internal data class TunnelCandidateReservation(
    val generation: Long,
    val networkEpoch: Long,
)

internal data class TunnelVerificationTicket(
    val generation: Long,
    val networkEpoch: Long,
    val nativeSessionEpoch: Long,
)

internal data class TunnelNetworkInvalidationTicket(
    val invalidatedOwnerGeneration: Long,
    val nativeSessionEpoch: Long,
)

internal data class TunnelNativeSessionState(
    val ownerActive: Boolean,
    val verifiable: Boolean,
)

internal fun ReadReceiptsTunnelStatus.forAdministrativePublish(
    sessionState: TunnelNativeSessionState,
): ReadReceiptsTunnelStatus {
    if (sessionState.ownerActive && sessionState.verifiable) return this
    if (state == ReadReceiptsTunnelState.CONNECTED) {
        return ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.RECONNECTING)
    }
    return copy(publicUrl = null)
}

internal data class DecodedReadReceiptsTunnelStatus(
    val generation: Long,
    val status: ReadReceiptsTunnelStatus,
    val credentialExists: Boolean,
    val clientNonce: String,
)

/** Strict status decoder shared by the Binder adapter and desktop coordination tests. */
internal fun decodeReadReceiptsTunnelStatus(
    values: Map<String, Any?>,
): DecodedReadReceiptsTunnelStatus? = runCatching {
    require(values.keys == ReadReceiptsTunnelProtocol.STATUS_KEYS)
    val generation = (values[ReadReceiptsTunnelProtocol.KEY_GENERATION] as? Long)
        ?.takeIf { it >= 0 } ?: error("invalid tunnel generation")
    val stateName = values[ReadReceiptsTunnelProtocol.KEY_STATE] as? String
        ?: error("invalid tunnel state")
    val state = ReadReceiptsTunnelState.entries.firstOrNull { it.name == stateName }
        ?: error("unknown tunnel state")
    val publicUrl = values.strictNullableTunnelStatusString(
        ReadReceiptsTunnelProtocol.KEY_PUBLIC_URL,
    )
    require(publicUrl == null || publicUrl.length <= 2048)
    val errorName = values.strictNullableTunnelStatusString(
        ReadReceiptsTunnelProtocol.KEY_ERROR_CODE,
    )
    val errorCode = errorName?.let { wireName ->
        ReadReceiptsTunnelErrorCode.entries.firstOrNull { it.name == wireName }
            ?: error("unknown tunnel error code")
    }
    val credentialExists = values[ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_EXISTS] as? Boolean
        ?: error("invalid credential flag")
    val needsNotificationSettings =
        values[ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS] as? Boolean
            ?: error("invalid notification-settings flag")
    val clientNonce = values[ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE] as? String
        ?: error("invalid client nonce")
    require(clientNonce.length in 16..128 && clientNonce.all { it.code in 0x20..0x7e })
    when (state) {
        ReadReceiptsTunnelState.FAILED,
        ReadReceiptsTunnelState.NEEDS_USER_ACTION,
        -> require(errorCode != null)

        else -> require(errorCode == null)
    }
    if (state == ReadReceiptsTunnelState.CONNECTED) {
        require(publicUrl != null && canonicalTunnelPublicRoot(publicUrl) == publicUrl)
    } else {
        require(publicUrl == null)
    }
    require(
        needsNotificationSettings == (
            state == ReadReceiptsTunnelState.NEEDS_USER_ACTION &&
                errorCode == ReadReceiptsTunnelErrorCode.NOTIFICATIONS_DISABLED
            ),
    )
    DecodedReadReceiptsTunnelStatus(
        generation,
        ReadReceiptsTunnelStatus(
            state,
            publicUrl,
            errorCode,
            needsNotificationSettings,
        ),
        credentialExists,
        clientNonce,
    )
}.getOrNull()

private fun Map<String, Any?>.strictNullableTunnelStatusString(key: String): String? {
    require(containsKey(key))
    val value = get(key)
    require(value == null || value is String)
    return value
}

internal fun normalizeTunnelPublicRoot(value: String): HttpUrl? {
    if (value.isBlank() || value != value.trim() || value.any(Char::isWhitespace)) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (
        url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.query != null || url.fragment != null ||
        url.encodedPath != "/" || url.host.length > 253 || !url.host.contains('.') ||
        url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }
    ) {
        return null
    }
    return url
}

internal fun canonicalTunnelPublicRoot(value: String): String? =
    normalizeTunnelPublicRoot(value)?.toString()?.trimEnd('/')

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
    val callbacks: List<(Result<Unit>) -> Unit> = emptyList(),
)

internal sealed interface TunnelStartAdmission {
    data object Allowed : TunnelStartAdmission

    data class Rejected(val failure: ReadReceiptsTunnelException) : TunnelStartAdmission
}

/** Collects concurrent stop callers and lets exactly one terminal path drain their callbacks. */
internal class TunnelStopCompletion {
    private data class Pending(
        var generation: Long,
        val callbacks: MutableList<(Result<Unit>) -> Unit>,
    )

    private var pending: Pending? = null
    private var completedGeneration: Long? = null

    @Synchronized
    fun register(
        callback: ((Result<Unit>) -> Unit)?,
        latestIssuedGeneration: Long = Long.MIN_VALUE,
        generationFactory: () -> Long,
    ): StopRegistration {
        pending?.let { current ->
            if (callback != null) current.callbacks += callback
            if (current.generation < latestIssuedGeneration) {
                val upgradedGeneration = generationFactory()
                check(upgradedGeneration > latestIssuedGeneration)
                current.generation = upgradedGeneration
                return StopRegistration(upgradedGeneration, shouldSend = true)
            }
            return StopRegistration(current.generation, shouldSend = false)
        }
        val generation = generationFactory()
        pending = Pending(
            generation,
            mutableListOf<(Result<Unit>) -> Unit>().apply {
                if (callback != null) add(callback)
            },
        )
        return StopRegistration(generation, shouldSend = true)
    }

    @Synchronized
    fun complete(generation: Long): StopDrain = completeLocked(generation)

    @Synchronized
    fun completeTimeout(generation: Long, authoritativeGeneration: Long): StopDrain {
        if (generation != authoritativeGeneration) return StopDrain(matched = false)
        return completeLocked(generation)
    }

    private fun completeLocked(generation: Long): StopDrain {
        val current = pending ?: return StopDrain(matched = completedGeneration == generation)
        if (current.generation != generation) return StopDrain(matched = false)
        pending = null
        completedGeneration = generation
        return StopDrain(matched = true, callbacks = current.callbacks.toList())
    }

    @Synchronized
    fun pendingGeneration(): Long? = pending?.generation

    @Synchronized
    fun startAdmission(): TunnelStartAdmission = if (pending == null) {
        TunnelStartAdmission.Allowed
    } else {
        TunnelStartAdmission.Rejected(
            ReadReceiptsTunnelException(
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                "tunnel start is unavailable while stop is pending",
            ),
        )
    }

    /** Prevents a single-slot administrative command from replacing pending START/STOP work. */
    @Synchronized
    fun runAdministrativeCommandIfIdle(
        hasPendingStart: () -> Boolean,
        command: () -> Unit,
    ): Boolean {
        if (pending != null || hasPendingStart()) return false
        command()
        return true
    }
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
        drainPending(pendingGeneration, supersede)
        return generationFactory().also(::begin)
    }

    fun drainPending(
        pendingGeneration: () -> Long?,
        supersede: (Long) -> Unit,
    ) {
        while (true) {
            val pending = pendingGeneration() ?: break
            supersede(pending)
        }
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

internal sealed class AuthOperationKind<T> private constructor() {
    data object BEGIN : AuthOperationKind<CloudflareLoginState>()

    data object LIST : AuthOperationKind<List<ExistingTunnel>>()

    data object SELECT : AuthOperationKind<Unit>()

    data object CANCEL : AuthOperationKind<Unit>()

    data object LOGOUT : AuthOperationKind<Unit>()
}

internal data class AuthOperationKey(
    val authGeneration: Long,
    val requestId: Long,
) {
    init {
        require(authGeneration > 0)
        require(requestId > 0)
    }
}

internal sealed interface AuthOperationTerminal<out T> {
    data class Completed<T>(val value: T) : AuthOperationTerminal<T>

    data class Failed(val error: String) : AuthOperationTerminal<Nothing> {
        init {
            require(error.isNotEmpty() && error.length <= MAX_ERROR_CHARS)
        }

        private companion object {
            const val MAX_ERROR_CHARS = 256
        }
    }

    data object Superseded : AuthOperationTerminal<Nothing>

    data object TimedOut : AuthOperationTerminal<Nothing>

    data object Cancelled : AuthOperationTerminal<Nothing>
}

/** Owns auth result slots independently from connector START/STOP bookkeeping. */
internal class AuthOperationRegistry {
    private class Entry(
        val kind: AuthOperationKind<*>,
        val deliver: (AuthOperationTerminal<*>) -> Unit,
    )

    private val pending = linkedMapOf<AuthOperationKey, Entry>()
    private var currentGeneration: Long? = null
    private var preparedGeneration: Long? = null

    fun replaceGeneration(generation: Long): Boolean {
        require(generation > 0)
        val deliveries = synchronized(this) {
            val latest = maxOf(currentGeneration ?: 0, preparedGeneration ?: 0)
            if (generation <= latest) return false
            currentGeneration = generation
            preparedGeneration = null
            pending.values.map { it to AuthOperationTerminal.Superseded }.also { pending.clear() }
        }
        deliverAll(deliveries)
        return true
    }

    /** Adopts service-owned state only while no local request can be superseded. */
    @Synchronized
    fun adoptGeneration(generation: Long): Boolean {
        require(generation > 0)
        val current = currentGeneration
        if (current == generation && preparedGeneration == null) return true
        if (pending.isNotEmpty() || preparedGeneration != null) return false
        if (current != null && generation < current) return false
        currentGeneration = generation
        return true
    }

    @Synchronized
    fun prepareGeneration(generation: Long): Boolean {
        require(generation > 0)
        val latest = maxOf(currentGeneration ?: 0, preparedGeneration ?: 0)
        if (generation <= latest) return false
        preparedGeneration = generation
        return true
    }

    fun finishPreparedGeneration(generation: Long): Boolean {
        val deliveries = synchronized(this) {
            if (preparedGeneration != generation) return false
            preparedGeneration = null
            currentGeneration = generation
            pending.entries
                .filter { it.key.authGeneration != generation }
                .map { it.value to AuthOperationTerminal.Superseded }
                .also { pending.keys.removeAll { it.authGeneration != generation } }
        }
        deliverAll(deliveries)
        return true
    }

    fun <T> register(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        callback: (AuthOperationTerminal<T>) -> Unit,
    ): Boolean {
        var accepted = true
        synchronized(this) {
            if (currentGeneration == null && preparedGeneration == null) {
                currentGeneration = key.authGeneration
            }
            val acceptsGeneration =
                key.authGeneration == currentGeneration || key.authGeneration == preparedGeneration
            if (!acceptsGeneration || pending.containsKey(key)) {
                accepted = false
            } else {
                pending[key] = Entry(kind) { terminal ->
                    @Suppress("UNCHECKED_CAST")
                    callback(terminal as AuthOperationTerminal<T>)
                }
            }
        }
        if (!accepted) {
            callback(AuthOperationTerminal.Superseded)
            return false
        }
        return true
    }

    fun <T> complete(
        key: AuthOperationKey,
        expectedKind: AuthOperationKind<T>,
        terminal: AuthOperationTerminal<T>,
    ): Boolean {
        val delivery = synchronized(this) {
            val entry = pending[key] ?: return false
            if (entry.kind !== expectedKind) return false
            pending.remove(key)
            entry to terminal
        }
        deliverAll(listOf(delivery))
        return true
    }

    fun <T> timeout(key: AuthOperationKey, expectedKind: AuthOperationKind<T>): Boolean =
        complete(key, expectedKind, AuthOperationTerminal.TimedOut)

    fun <T> cancel(key: AuthOperationKey, expectedKind: AuthOperationKind<T>): Boolean =
        complete(key, expectedKind, AuthOperationTerminal.Cancelled)

    fun <T> completeAndCancelGeneration(
        key: AuthOperationKey,
        expectedKind: AuthOperationKind<T>,
        terminal: AuthOperationTerminal<T>,
    ): Boolean {
        val deliveries = synchronized(this) {
            val target = pending[key] ?: return false
            if (target.kind !== expectedKind) return false
            val siblings = pending.entries
                .filter { it.key != key && it.key.authGeneration == key.authGeneration }
                .map { it.value to AuthOperationTerminal.Cancelled }
            pending.keys.removeAll { it.authGeneration == key.authGeneration }
            listOf(target to terminal) + siblings
        }
        deliverAll(deliveries)
        return true
    }

    fun cancelGeneration(generation: Long): Int {
        val deliveries = synchronized(this) {
            pending.entries
                .filter { it.key.authGeneration == generation }
                .map { it.value to AuthOperationTerminal.Cancelled }
                .also { pending.keys.removeAll { it.authGeneration == generation } }
        }
        deliverAll(deliveries)
        return deliveries.size
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun pendingKeys(): Set<AuthOperationKey> = pending.keys.toSet()

    @Synchronized
    fun pendingKind(key: AuthOperationKey): AuthOperationKind<*>? = pending[key]?.kind

    private fun deliverAll(deliveries: List<Pair<Entry, AuthOperationTerminal<*>>>) {
        var firstFailure: Throwable? = null
        deliveries.forEach { (entry, terminal) ->
            try {
                entry.deliver(terminal)
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else if (firstFailure !== failure) {
                    runCatching { firstFailure.addSuppressed(failure) }
                }
            }
        }
        firstFailure?.let { throw it }
    }
}

internal enum class ServiceAuthRejectReason {
    STALE_GENERATION,
    DUPLICATE_REQUEST,
    SESSION_UNAVAILABLE,
    INVALID_KIND,
}

internal sealed interface ServiceAuthAdmission {
    data object Accepted : ServiceAuthAdmission

    data class Rejected(val reason: ServiceAuthRejectReason) : ServiceAuthAdmission
}

internal enum class ServiceAuthSessionPhase {
    IDLE,
    REPLACING,
    WAITING,
    AUTHORIZED,
    CANCELLING,
    RESTART_REQUIRED,
}

internal enum class ServiceAuthOperationPhase {
    NATIVE_BLOCKING,
    SELECT_VALIDATING,
    CLEANING,
}

internal enum class ServiceAuthFailure {
    API_RETURNED,
    TIMEOUT,
    COROUTINE_CANCELLED,
    STORAGE,
    SESSION_BROKEN,
}

internal enum class ServiceAuthFailureSource(
    val operationKind: AuthOperationKind<*>,
    val failure: ServiceAuthFailure,
    val errorCode: ReadReceiptsTunnelErrorCode,
) {
    BEGIN_CLEANUP_FAILED(
        AuthOperationKind.BEGIN,
        ServiceAuthFailure.SESSION_BROKEN,
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
    ),
    BEGIN_REJECTED(
        AuthOperationKind.BEGIN,
        ServiceAuthFailure.SESSION_BROKEN,
        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
    ),
    LIST_TIMEOUT(
        AuthOperationKind.LIST,
        ServiceAuthFailure.TIMEOUT,
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
    ),
    LIST_SESSION_LOST(
        AuthOperationKind.LIST,
        ServiceAuthFailure.SESSION_BROKEN,
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
    ),
    LIST_REJECTED(
        AuthOperationKind.LIST,
        ServiceAuthFailure.API_RETURNED,
        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
    ),
    LIST_CANCELLED(
        AuthOperationKind.LIST,
        ServiceAuthFailure.COROUTINE_CANCELLED,
        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
    ),
    SELECT_TIMEOUT(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.TIMEOUT,
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
    ),
    SELECT_SESSION_LOST(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.SESSION_BROKEN,
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
    ),
    SELECT_REJECTED(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.API_RETURNED,
        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
    ),
    SELECT_CREDENTIAL_SAVE_FAILED(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.STORAGE,
        ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED,
    ),
    SELECT_HEALTH_CHECK_FAILED(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.API_RETURNED,
        ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED,
    ),
    SELECT_CANCELLED(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.COROUTINE_CANCELLED,
        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
    ),
    SELECT_UNEXPECTED(
        AuthOperationKind.SELECT,
        ServiceAuthFailure.API_RETURNED,
        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
    ),
}

internal fun serviceAuthAdmissionTerminal(
    reason: ServiceAuthRejectReason,
): AuthOperationTerminal<Nothing> = when (reason) {
    ServiceAuthRejectReason.STALE_GENERATION,
    ServiceAuthRejectReason.DUPLICATE_REQUEST,
    -> AuthOperationTerminal.Superseded
    ServiceAuthRejectReason.SESSION_UNAVAILABLE -> AuthOperationTerminal.Failed(
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE.name,
    )
    ServiceAuthRejectReason.INVALID_KIND -> AuthOperationTerminal.Failed(
        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE.name,
    )
}

internal enum class ServiceAuthCleanupAction {
    PRESERVE_AUTH,
    STOP_CANDIDATE_AND_PRESERVE_AUTH,
    CANCEL_AUTH_AND_RESTART_REQUIRED,
}

internal data class ServiceAuthSnapshot(
    val authGeneration: Long,
    val phase: ServiceAuthSessionPhase,
)

internal class ServiceAuthFailurePlan<T> internal constructor(
    internal val key: AuthOperationKey,
    internal val kind: AuthOperationKind<T>,
    internal val terminal: AuthOperationTerminal<T>,
    val action: ServiceAuthCleanupAction,
    val errorCode: ReadReceiptsTunnelErrorCode,
)

internal class ServiceAuthTerminalPlan<T> internal constructor(
    internal val key: AuthOperationKey,
    internal val kind: AuthOperationKind<T>,
    internal val terminal: AuthOperationTerminal<T>,
)

internal class SelectCommitGate {
    private var claim = Claim.PENDING

    @Synchronized
    fun tryCommit(): Boolean = tryClaim(Claim.COMMIT)

    @Synchronized
    fun tryTerminal(): Boolean = tryClaim(Claim.TERMINAL)

    @Synchronized
    fun isCommitClaimed(): Boolean = claim == Claim.COMMIT

    private fun tryClaim(candidate: Claim): Boolean {
        if (claim != Claim.PENDING) return false
        claim = candidate
        return true
    }

    private enum class Claim {
        PENDING,
        COMMIT,
        TERMINAL,
    }
}

internal class ServiceAuthSessionClearPlan<T> internal constructor(
    internal val key: AuthOperationKey,
    internal val kind: AuthOperationKind<T>,
    internal val terminal: AuthOperationTerminal<T>,
)

internal class ServiceAuthSessionTeardownPlan internal constructor(
    internal val authGeneration: Long,
)

/**
 * Main-authority admission and phase state for auth commands.
 * [AuthOperationRegistry] remains the sole owner of terminal delivery.
 */
internal class ServiceAuthCoordinator {
    private val operations = AuthOperationRegistry()
    private val operationPhases = mutableMapOf<AuthOperationKey, ServiceAuthOperationPhase>()
    private val ackKinds = mutableMapOf<AuthOperationKey, AuthOperationKind<*>>()
    private val seenRequests = mutableSetOf<AuthOperationKey>()
    private val plannedFailures = mutableMapOf<AuthOperationKey, ServiceAuthFailurePlan<*>>()
    private val plannedTerminals = mutableMapOf<AuthOperationKey, ServiceAuthTerminalPlan<*>>()
    private var plannedSessionClear: ServiceAuthSessionClearPlan<*>? = null
    private var plannedSessionTeardown: ServiceAuthSessionTeardownPlan? = null
    private var lastAcceptedGeneration = 0L
    private var activeGeneration = 0L
    private var sessionPhase = ServiceAuthSessionPhase.IDLE

    fun begin(
        key: AuthOperationKey,
        callback: (AuthOperationTerminal<CloudflareLoginState>) -> Unit,
    ): ServiceAuthAdmission {
        if (key.authGeneration <= lastAcceptedGeneration) {
            return ServiceAuthAdmission.Rejected(ServiceAuthRejectReason.STALE_GENERATION)
        }
        if (
            sessionPhase == ServiceAuthSessionPhase.REPLACING ||
            sessionPhase == ServiceAuthSessionPhase.CANCELLING
        ) {
            return ServiceAuthAdmission.Rejected(ServiceAuthRejectReason.SESSION_UNAVAILABLE)
        }

        lastAcceptedGeneration = key.authGeneration
        sessionPhase = ServiceAuthSessionPhase.REPLACING
        operationPhases.replaceAll { _, _ -> ServiceAuthOperationPhase.CLEANING }
        ackKinds.clear()
        seenRequests.clear()
        plannedFailures.clear()
        plannedTerminals.clear()
        plannedSessionClear = null
        plannedSessionTeardown = null
        check(operations.prepareGeneration(key.authGeneration))
        check(operations.register(key, AuthOperationKind.BEGIN, callback))
        operationPhases[key] = ServiceAuthOperationPhase.NATIVE_BLOCKING
        ackKinds[key] = AuthOperationKind.BEGIN
        seenRequests += key
        return ServiceAuthAdmission.Accepted
    }

    fun <T> admit(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        phase: ServiceAuthOperationPhase,
        callback: (AuthOperationTerminal<T>) -> Unit,
    ): ServiceAuthAdmission {
        if (key.authGeneration != activeGeneration) {
            return ServiceAuthAdmission.Rejected(ServiceAuthRejectReason.STALE_GENERATION)
        }
        if (kind === AuthOperationKind.BEGIN) {
            return ServiceAuthAdmission.Rejected(ServiceAuthRejectReason.INVALID_KIND)
        }
        val acceptsSession = when (kind) {
            AuthOperationKind.CANCEL,
            AuthOperationKind.LOGOUT,
            -> sessionPhase == ServiceAuthSessionPhase.WAITING ||
                sessionPhase == ServiceAuthSessionPhase.AUTHORIZED

            else -> sessionPhase == ServiceAuthSessionPhase.AUTHORIZED
        }
        if (!acceptsSession) {
            return ServiceAuthAdmission.Rejected(ServiceAuthRejectReason.SESSION_UNAVAILABLE)
        }
        if (key in seenRequests) {
            return ServiceAuthAdmission.Rejected(ServiceAuthRejectReason.DUPLICATE_REQUEST)
        }
        check(operations.register(key, kind, callback))
        operationPhases[key] = phase
        ackKinds[key] = kind
        seenRequests += key
        return ServiceAuthAdmission.Accepted
    }

    fun <T> claimAck(key: AuthOperationKey, kind: AuthOperationKind<T>): Boolean {
        if (operations.pendingKind(key) !== kind || ackKinds[key] !== kind) return false
        ackKinds.remove(key)
        return true
    }

    fun finishBeginBarrier(key: AuthOperationKey): Boolean {
        if (
            key.authGeneration != lastAcceptedGeneration ||
            sessionPhase != ServiceAuthSessionPhase.REPLACING ||
            operations.pendingKind(key) !== AuthOperationKind.BEGIN
        ) {
            return false
        }
        operationPhases.keys.removeAll { it.authGeneration != key.authGeneration }
        ackKinds.keys.removeAll { it.authGeneration != key.authGeneration }
        activeGeneration = key.authGeneration
        sessionPhase = ServiceAuthSessionPhase.WAITING
        return operations.finishPreparedGeneration(key.authGeneration)
    }

    fun markAuthorized(authGeneration: Long): Boolean {
        if (
            authGeneration != activeGeneration ||
            sessionPhase != ServiceAuthSessionPhase.WAITING
        ) {
            return false
        }
        sessionPhase = ServiceAuthSessionPhase.AUTHORIZED
        return true
    }

    fun markSelectValidating(key: AuthOperationKey): Boolean {
        if (
            key.authGeneration != activeGeneration ||
            sessionPhase != ServiceAuthSessionPhase.AUTHORIZED ||
            operations.pendingKind(key) !== AuthOperationKind.SELECT ||
            operationPhases[key] != ServiceAuthOperationPhase.NATIVE_BLOCKING
        ) {
            return false
        }
        operationPhases[key] = ServiceAuthOperationPhase.SELECT_VALIDATING
        return true
    }

    fun <T> canPublish(key: AuthOperationKey, kind: AuthOperationKind<T>): Boolean =
        key.authGeneration == activeGeneration &&
            operations.pendingKind(key) === kind &&
            operationPhases[key] != ServiceAuthOperationPhase.CLEANING &&
            sessionPhase != ServiceAuthSessionPhase.CANCELLING &&
            !(kind === AuthOperationKind.BEGIN && sessionPhase == ServiceAuthSessionPhase.REPLACING)

    fun <T> complete(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        terminal: AuthOperationTerminal<T>,
    ): Boolean {
        if (operationPhases[key] == ServiceAuthOperationPhase.CLEANING) return false
        val completed = operations.complete(key, kind, terminal)
        if (completed) {
            operationPhases.remove(key)
            ackKinds.remove(key)
        }
        return completed
    }

    fun <T> planFailure(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        source: ServiceAuthFailureSource,
    ): ServiceAuthFailurePlan<T>? {
        require(source.operationKind === kind)
        return planFailure(
            key,
            kind,
            source.failure,
            source.errorCode.name,
            source.errorCode,
        )
    }

    private fun <T> planFailure(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        failure: ServiceAuthFailure,
        message: String,
        errorCode: ReadReceiptsTunnelErrorCode,
    ): ServiceAuthFailurePlan<T>? {
        if (
            key.authGeneration != activeGeneration ||
            operations.pendingKind(key) !== kind ||
            operationPhases[key] == ServiceAuthOperationPhase.CLEANING
        ) {
            return null
        }

        val operationPhase = operationPhases[key] ?: return null
        val cleanup = when {
            operationPhase == ServiceAuthOperationPhase.SELECT_VALIDATING ->
                ServiceAuthCleanupAction.STOP_CANDIDATE_AND_PRESERVE_AUTH

            failure == ServiceAuthFailure.TIMEOUT ||
                failure == ServiceAuthFailure.COROUTINE_CANCELLED ||
                failure == ServiceAuthFailure.SESSION_BROKEN ->
                ServiceAuthCleanupAction.CANCEL_AUTH_AND_RESTART_REQUIRED

            else -> ServiceAuthCleanupAction.PRESERVE_AUTH
        }
        if (cleanup == ServiceAuthCleanupAction.CANCEL_AUTH_AND_RESTART_REQUIRED) {
            sessionPhase = ServiceAuthSessionPhase.CANCELLING
            operationPhases.replaceAll { operationKey, phase ->
                if (operationKey.authGeneration == key.authGeneration) {
                    ServiceAuthOperationPhase.CLEANING
                } else {
                    phase
                }
            }
        } else {
            operationPhases[key] = ServiceAuthOperationPhase.CLEANING
        }

        val terminal: AuthOperationTerminal<T> = when (failure) {
            ServiceAuthFailure.TIMEOUT -> AuthOperationTerminal.TimedOut
            ServiceAuthFailure.COROUTINE_CANCELLED -> AuthOperationTerminal.Cancelled
            ServiceAuthFailure.API_RETURNED,
            ServiceAuthFailure.STORAGE,
            ServiceAuthFailure.SESSION_BROKEN,
            -> AuthOperationTerminal.Failed(message)
        }
        return ServiceAuthFailurePlan(key, kind, terminal, cleanup, errorCode).also {
            plannedFailures[key] = it
        }
    }

    fun <T> planTerminal(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        terminal: AuthOperationTerminal<T>,
    ): ServiceAuthTerminalPlan<T>? {
        if (
            key.authGeneration != activeGeneration ||
            operations.pendingKind(key) !== kind ||
            operationPhases[key] == ServiceAuthOperationPhase.CLEANING
        ) {
            return null
        }
        operationPhases[key] = ServiceAuthOperationPhase.CLEANING
        return ServiceAuthTerminalPlan(key, kind, terminal).also {
            plannedTerminals[key] = it
        }
    }

    fun <T> finishTerminal(plan: ServiceAuthTerminalPlan<T>): Boolean {
        if (plannedTerminals[plan.key] !== plan) return false
        plannedTerminals.remove(plan.key)
        operationPhases.remove(plan.key)
        ackKinds.remove(plan.key)
        return operations.complete(plan.key, plan.kind, plan.terminal)
    }

    fun <T> finishFailure(plan: ServiceAuthFailurePlan<T>): Boolean {
        if (plannedFailures[plan.key] !== plan) return false
        plannedFailures.remove(plan.key)

        if (plan.action == ServiceAuthCleanupAction.CANCEL_AUTH_AND_RESTART_REQUIRED) {
            if (
                activeGeneration != plan.key.authGeneration ||
                sessionPhase != ServiceAuthSessionPhase.CANCELLING
            ) {
                return false
            }
            activeGeneration = 0
            sessionPhase = ServiceAuthSessionPhase.RESTART_REQUIRED
            plannedFailures.keys.removeAll { it.authGeneration == plan.key.authGeneration }
            plannedTerminals.keys.removeAll { it.authGeneration == plan.key.authGeneration }
            operationPhases.keys.removeAll { it.authGeneration == plan.key.authGeneration }
            ackKinds.keys.removeAll { it.authGeneration == plan.key.authGeneration }
            return operations.completeAndCancelGeneration(plan.key, plan.kind, plan.terminal)
        }

        operationPhases.remove(plan.key)
        ackKinds.remove(plan.key)
        return operations.complete(plan.key, plan.kind, plan.terminal)
    }

    fun <T> planSessionClear(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        ownerTerminal: AuthOperationTerminal<T>,
    ): ServiceAuthSessionClearPlan<T>? {
        val clearsSession = when (kind) {
            AuthOperationKind.SELECT -> sessionPhase == ServiceAuthSessionPhase.AUTHORIZED
            AuthOperationKind.CANCEL,
            AuthOperationKind.LOGOUT,
            -> sessionPhase == ServiceAuthSessionPhase.WAITING ||
                sessionPhase == ServiceAuthSessionPhase.AUTHORIZED

            else -> false
        }
        if (
            key.authGeneration != activeGeneration ||
            operations.pendingKind(key) !== kind ||
            operationPhases[key] == ServiceAuthOperationPhase.CLEANING ||
            !clearsSession ||
            plannedSessionClear != null
        ) {
            return null
        }
        operationPhases.replaceAll { operationKey, phase ->
            if (operationKey.authGeneration == key.authGeneration) {
                ServiceAuthOperationPhase.CLEANING
            } else {
                phase
            }
        }
        sessionPhase = ServiceAuthSessionPhase.CANCELLING
        return ServiceAuthSessionClearPlan(key, kind, ownerTerminal).also {
            plannedSessionClear = it
        }
    }

    fun <T> finishSessionClear(
        plan: ServiceAuthSessionClearPlan<T>,
        restartRequired: Boolean,
    ): Boolean {
        if (
            plannedSessionClear !== plan ||
            activeGeneration != plan.key.authGeneration ||
            sessionPhase != ServiceAuthSessionPhase.CANCELLING
        ) {
            return false
        }
        plannedSessionClear = null
        activeGeneration = 0
        sessionPhase = if (restartRequired) {
            ServiceAuthSessionPhase.RESTART_REQUIRED
        } else {
            ServiceAuthSessionPhase.IDLE
        }
        plannedFailures.keys.removeAll { it.authGeneration == plan.key.authGeneration }
        plannedTerminals.keys.removeAll { it.authGeneration == plan.key.authGeneration }
        operationPhases.keys.removeAll { it.authGeneration == plan.key.authGeneration }
        ackKinds.keys.removeAll { it.authGeneration == plan.key.authGeneration }
        return operations.completeAndCancelGeneration(plan.key, plan.kind, plan.terminal)
    }

    fun planSessionTeardown(expectedGeneration: Long): ServiceAuthSessionTeardownPlan? {
        val hasLiveSession =
            sessionPhase == ServiceAuthSessionPhase.WAITING ||
                sessionPhase == ServiceAuthSessionPhase.AUTHORIZED
        if (
            expectedGeneration <= 0 ||
            activeGeneration != expectedGeneration ||
            !hasLiveSession ||
            plannedSessionClear != null ||
            plannedSessionTeardown != null
        ) {
            return null
        }
        operationPhases.replaceAll { operationKey, phase ->
            if (operationKey.authGeneration == expectedGeneration) {
                ServiceAuthOperationPhase.CLEANING
            } else {
                phase
            }
        }
        sessionPhase = ServiceAuthSessionPhase.CANCELLING
        return ServiceAuthSessionTeardownPlan(expectedGeneration).also {
            plannedSessionTeardown = it
        }
    }

    fun finishSessionTeardown(
        plan: ServiceAuthSessionTeardownPlan,
        restartRequired: Boolean,
    ): Boolean {
        if (
            plannedSessionTeardown !== plan ||
            activeGeneration != plan.authGeneration ||
            sessionPhase != ServiceAuthSessionPhase.CANCELLING
        ) {
            return false
        }
        plannedSessionTeardown = null
        activeGeneration = 0
        sessionPhase = if (restartRequired) {
            ServiceAuthSessionPhase.RESTART_REQUIRED
        } else {
            ServiceAuthSessionPhase.IDLE
        }
        plannedFailures.keys.removeAll { it.authGeneration == plan.authGeneration }
        plannedTerminals.keys.removeAll { it.authGeneration == plan.authGeneration }
        operationPhases.keys.removeAll { it.authGeneration == plan.authGeneration }
        ackKinds.keys.removeAll { it.authGeneration == plan.authGeneration }
        operations.cancelGeneration(plan.authGeneration)
        return true
    }

    /** Teardown-only clearing when no command owns a successful terminal. */
    fun clearSession(expectedGeneration: Long, restartRequired: Boolean): Boolean {
        if (expectedGeneration <= 0 || activeGeneration != expectedGeneration) return false
        activeGeneration = 0
        sessionPhase = if (restartRequired) {
            ServiceAuthSessionPhase.RESTART_REQUIRED
        } else {
            ServiceAuthSessionPhase.IDLE
        }
        plannedSessionClear = null
        plannedSessionTeardown = null
        plannedFailures.keys.removeAll { it.authGeneration == expectedGeneration }
        plannedTerminals.keys.removeAll { it.authGeneration == expectedGeneration }
        operationPhases.keys.removeAll { it.authGeneration == expectedGeneration }
        ackKinds.keys.removeAll { it.authGeneration == expectedGeneration }
        operations.cancelGeneration(expectedGeneration)
        return true
    }

    fun snapshot(): ServiceAuthSnapshot = ServiceAuthSnapshot(activeGeneration, sessionPhase)
}

/** Hard rejection budget applied before any auth snapshot is written to Bundle or Parcel. */
internal object AuthSnapshotBounds {
    private const val MAX_TUNNELS = 100
    private const val MAX_HOSTNAMES = 512
    private const val MAX_DYNAMIC_TEXT_BYTES = 128 * 1024

    fun isValid(
        loginState: CloudflareLoginState,
        accountId: String,
        tunnels: List<ExistingTunnel>,
        metadata: CommittedTunnelCredentialMetadata?,
    ): Boolean {
        if (tunnels.size > MAX_TUNNELS) return false
        var hostnameCount = 0
        var textBytes = 0

        fun include(value: String?): Boolean {
            if (value == null) return true
            textBytes += value.toByteArray(StandardCharsets.UTF_8).size
            return textBytes <= MAX_DYNAMIC_TEXT_BYTES
        }

        if (!include(loginState.authorizationUrl) || !include(loginState.error) || !include(accountId)) {
            return false
        }
        for (tunnel in tunnels) {
            hostnameCount += tunnel.hostnames.size
            if (hostnameCount > MAX_HOSTNAMES || !include(tunnel.id) || !include(tunnel.name)) return false
            for (hostname in tunnel.hostnames) {
                if (!include(hostname)) return false
            }
        }
        if (metadata != null) {
            if (
                !include(metadata.accountId) ||
                !include(metadata.tunnelId) ||
                !include(metadata.tunnelName) ||
                !include(metadata.canonicalHostname)
            ) {
                return false
            }
        }
        return true
    }
}

/**
 * Controller-side wire ownership around [AuthOperationRegistry]. A response can consume terminal
 * ownership only after the exact request was sent to, and acknowledged by, the same service
 * binder. Unsent requests deliberately survive binder replacement so the controller can rebind.
 */
internal class ControllerAuthOperationQueue {
    private class Entry(
        val kind: AuthOperationKind<*>,
        var binderOwner: Any? = null,
        var acknowledged: Boolean = false,
    )

    private val registry = AuthOperationRegistry()
    private val pending = linkedMapOf<AuthOperationKey, Entry>()
    private var currentGeneration: Long? = null

    fun replaceGeneration(generation: Long): Boolean {
        require(generation > 0)
        synchronized(this) {
            val current = currentGeneration
            if (current != null && generation <= current) return false
            currentGeneration = generation
            pending.clear()
        }
        return registry.replaceGeneration(generation)
    }

    /** Aligns with a live service snapshot without terminating any controller-owned request. */
    @Synchronized
    fun adoptGeneration(generation: Long): Boolean {
        require(generation > 0)
        val current = currentGeneration
        if (current == generation) return true
        if (pending.isNotEmpty() || current != null && generation < current) return false
        if (!registry.adoptGeneration(generation)) return false
        currentGeneration = generation
        return true
    }

    fun <T> enqueue(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        callback: (AuthOperationTerminal<T>) -> Unit,
    ): Boolean {
        synchronized(this) {
            if (key.authGeneration != currentGeneration || pending.containsKey(key)) return false
            pending[key] = Entry(kind)
        }
        if (registry.register(key, kind, callback)) return true
        synchronized(this) { pending.remove(key) }
        return false
    }

    @Synchronized
    fun markSent(key: AuthOperationKey, kind: AuthOperationKind<*>, binderOwner: Any): Boolean {
        val entry = pending[key] ?: return false
        if (entry.kind !== kind || entry.binderOwner != null) return false
        entry.binderOwner = binderOwner
        return true
    }

    @Synchronized
    fun acknowledge(key: AuthOperationKey, kind: AuthOperationKind<*>, binderOwner: Any): Boolean {
        val entry = pending[key] ?: return false
        if (
            entry.kind !== kind || entry.binderOwner !== binderOwner || entry.acknowledged
        ) {
            return false
        }
        entry.acknowledged = true
        return true
    }

    fun <T> complete(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
        binderOwner: Any,
        terminal: AuthOperationTerminal<T>,
        beforeDelivery: () -> Unit = {},
    ): Boolean {
        synchronized(this) {
            val entry = pending[key] ?: return false
            if (
                entry.kind !== kind || entry.binderOwner !== binderOwner || !entry.acknowledged
            ) {
                return false
            }
            pending.remove(key)
        }
        beforeDelivery()
        return registry.complete(key, kind, terminal)
    }

    fun <T> cancel(key: AuthOperationKey, kind: AuthOperationKind<T>): Boolean {
        synchronized(this) {
            val entry = pending[key] ?: return false
            if (entry.kind !== kind) return false
            pending.remove(key)
        }
        return registry.cancel(key, kind)
    }

    fun <T> timeout(key: AuthOperationKey, kind: AuthOperationKind<T>): Boolean {
        synchronized(this) {
            val entry = pending[key] ?: return false
            if (entry.kind !== kind) return false
            pending.remove(key)
        }
        return registry.timeout(key, kind)
    }

    fun binderDied(binderOwner: Any, error: String): Int {
        val dead = synchronized(this) {
            pending.entries
                .filter { it.value.binderOwner === binderOwner }
                .map { it.key to it.value.kind }
                .also { entries -> entries.forEach { pending.remove(it.first) } }
        }
        var firstFailure: Throwable? = null
        var completed = 0
        dead.forEach { (key, kind) ->
            try {
                if (completeFailed(key, kind, error)) completed++
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
                else if (firstFailure !== failure) runCatching { firstFailure.addSuppressed(failure) }
            }
        }
        firstFailure?.let { throw it }
        return completed
    }

    @Synchronized
    fun unsentKeys(): Set<AuthOperationKey> = pending
        .filterValues { it.binderOwner == null }
        .keys
        .toSet()

    @Suppress("UNCHECKED_CAST")
    private fun completeFailed(
        key: AuthOperationKey,
        kind: AuthOperationKind<*>,
        error: String,
    ): Boolean = registry.complete(
        key,
        kind as AuthOperationKind<Any?>,
        AuthOperationTerminal.Failed(error),
    )
}

internal class ControllerAuthSnapshot(
    val revision: Long,
    val authGeneration: Long,
    val restartRequired: Boolean,
    val loginState: CloudflareLoginState,
    val accountId: String,
    tunnels: List<ExistingTunnel>,
    val metadataLoading: Boolean,
    val committedMetadata: CommittedTunnelCredentialMetadata?,
) {
    val tunnels: List<ExistingTunnel> = Collections.unmodifiableList(ArrayList(tunnels))

    init {
        require(revision > 0)
        require(authGeneration >= 0)
        require(isStructurallyValid())
        require(
            AuthSnapshotBounds.isValid(loginState, accountId, this.tunnels, committedMetadata),
        )
    }

    private fun isStructurallyValid(): Boolean {
        if (restartRequired && authGeneration != 0L) return false
        val authorizationUrl = loginState.authorizationUrl
        if (
            authorizationUrl != null &&
            !ReadReceiptsTunnelNativeParser.isPinnedAuthorizationUrl(authorizationUrl)
        ) {
            return false
        }
        return when (loginState.state) {
            ReadReceiptsTunnelState.STOPPED ->
                authorizationUrl == null && loginState.error == null && accountId.isEmpty() &&
                    tunnels.isEmpty()

            ReadReceiptsTunnelState.STARTING ->
                authGeneration > 0 && !restartRequired && authorizationUrl != null &&
                    loginState.error == null && accountId.isEmpty() && tunnels.isEmpty()

            ReadReceiptsTunnelState.CONNECTED ->
                authGeneration > 0 && !restartRequired && authorizationUrl != null &&
                    loginState.error == null && ACCOUNT_ID_PATTERN.matches(accountId)

            ReadReceiptsTunnelState.FAILED ->
                authGeneration == 0L && restartRequired && authorizationUrl != null &&
                    loginState.error != null && accountId.isEmpty() && tunnels.isEmpty()

            ReadReceiptsTunnelState.RECONNECTING,
            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
            ReadReceiptsTunnelState.STOPPING,
            -> false
        }
    }

    fun browserMetadataRebindDecision(): BrowserMetadataRebindDecision {
        val metadata = committedMetadata
        if (
            metadataLoading || metadata == null ||
            metadata.source != TunnelCredentialSource.BROWSER_LOGIN ||
            !isCompleteBrowserMetadata(metadata)
        ) {
            return BrowserMetadataRebindDecision.Keep
        }
        return BrowserMetadataRebindDecision.Replace(
            CommittedBrowserTunnelMetadata(
                accountId = metadata.accountId,
                tunnelId = metadata.tunnelId,
                tunnelName = metadata.tunnelName,
                canonicalHostname = metadata.canonicalHostname,
                fixedOriginPort = metadata.fixedOriginPort,
            ),
        )
    }

    private fun isCompleteBrowserMetadata(
        metadata: CommittedTunnelCredentialMetadata,
    ): Boolean =
        metadata.accountId.matches(Regex("^[A-Za-z0-9_-]{1,32}$")) &&
            ExistingTunnel.isCanonicalId(metadata.tunnelId) &&
            metadata.tunnelName.isNotEmpty() &&
            metadata.tunnelName == metadata.tunnelName.trim() &&
            metadata.tunnelName.toByteArray(StandardCharsets.UTF_8).size <= 128 &&
            metadata.tunnelName.none(Char::isISOControl) &&
            ReadReceiptsTunnelService.canonicalPublicRoot(metadata.canonicalHostname) ==
            metadata.canonicalHostname &&
            metadata.fixedOriginPort in 1..65535

    private companion object {
        val ACCOUNT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
    }
}

/** Tracks a service process's snapshot revision without losing cross-process login expectation. */
internal class ControllerAuthStateStore {
    private var binderOwner: Any? = null
    private var revision = 0L
    private var expectedAuthGeneration = 0L
    private var snapshot: ControllerAuthSnapshot? = null

    @Synchronized
    fun expectBegin(generation: Long): Boolean {
        require(generation > 0)
        if (generation <= expectedAuthGeneration) return false
        expectedAuthGeneration = generation
        return true
    }

    @Synchronized
    fun clearExpectation(generation: Long): Boolean {
        if (generation <= 0 || expectedAuthGeneration != generation) return false
        expectedAuthGeneration = 0
        return true
    }

    /** Completes only the expectation still owned by this exact session generation. */
    fun completeSessionOperation(generation: Long): Boolean = clearExpectation(generation)

    @Synchronized
    fun accept(
        owner: Any,
        incoming: ControllerAuthSnapshot,
        beforeCommit: () -> Boolean = { true },
    ): Boolean {
        val ownerChanged = binderOwner !== owner
        val currentRevision = if (ownerChanged) 0L else revision
        if (incoming.revision <= currentRevision) return false
        if (
            incoming.authGeneration > 0 && expectedAuthGeneration > 0 &&
            incoming.authGeneration < expectedAuthGeneration
        ) {
            return false
        }
        if (!beforeCommit()) return false
        if (ownerChanged) binderOwner = owner
        revision = incoming.revision
        snapshot = incoming
        if (incoming.authGeneration > 0) expectedAuthGeneration = incoming.authGeneration
        return true
    }

    @Synchronized
    fun lastSeenAuthGeneration(): Long = expectedAuthGeneration

    @Synchronized
    fun currentSnapshot(): ControllerAuthSnapshot? = snapshot
}

/** Matches one foreground-service start to the SELECT command that caused it. */
internal class SelectForegroundReadiness {
    private var generation: Long? = null

    @Synchronized
    fun onStart(incomingGeneration: Long): Boolean {
        require(incomingGeneration > 0)
        val current = generation
        if (current != null && incomingGeneration < current) return false
        generation = incomingGeneration
        return true
    }

    @Synchronized
    fun claim(incomingGeneration: Long): Boolean {
        if (generation != incomingGeneration) return false
        generation = null
        return true
    }

    fun timeout(incomingGeneration: Long): Boolean = claim(incomingGeneration)
}

internal enum class TunnelCredentialSource {
    TOKEN,
    BROWSER_LOGIN,
}

/** Plaintext held only between Keystore decryption/encryption and the connector transaction. */
internal class TunnelCredentialPayload private constructor(
    val runToken: String,
    val source: TunnelCredentialSource,
    val accountId: String,
    val tunnelId: String,
    val tunnelName: String,
    val canonicalHostname: String,
    val fixedOriginPort: Int,
) {
    override fun toString(): String =
        "TunnelCredentialPayload(runToken=[redacted], source=$source, accountId=$accountId, " +
            "tunnelId=$tunnelId, tunnelName=$tunnelName, " +
            "canonicalHostname=$canonicalHostname, fixedOriginPort=$fixedOriginPort)"

    companion object {
        const val MAX_RUN_TOKEN_BYTES = 16 * 1024
        private const val MAX_ACCOUNT_ID_CHARS = 32
        private const val MAX_TUNNEL_NAME_BYTES = 128
        private val ACCOUNT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,$MAX_ACCOUNT_ID_CHARS}$")
        private val UUID_PATTERN =
            Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

        fun create(
            runToken: String,
            source: TunnelCredentialSource,
            accountId: String = "",
            tunnelId: String = "",
            tunnelName: String = "",
            canonicalHostname: String = "",
            fixedOriginPort: Int = 0,
        ): TunnelCredentialPayload? {
            val tokenBytes = runToken.toByteArray(StandardCharsets.UTF_8).size
            if (
                tokenBytes !in 1..MAX_RUN_TOKEN_BYTES || runToken != runToken.trim() ||
                runToken.any(Char::isISOControl)
            ) {
                return null
            }
            return when (source) {
                TunnelCredentialSource.TOKEN -> createToken(
                    runToken,
                    accountId,
                    tunnelId,
                    tunnelName,
                    canonicalHostname,
                    fixedOriginPort,
                )

                TunnelCredentialSource.BROWSER_LOGIN -> createBrowser(
                    runToken,
                    accountId,
                    tunnelId,
                    tunnelName,
                    canonicalHostname,
                    fixedOriginPort,
                )
            }
        }

        private fun createToken(
            runToken: String,
            accountId: String,
            tunnelId: String,
            tunnelName: String,
            hostname: String,
            port: Int,
        ): TunnelCredentialPayload? {
            if (accountId.isNotEmpty() || tunnelId.isNotEmpty() || tunnelName.isNotEmpty()) return null
            if (hostname.isEmpty() && port == 0) {
                return TunnelCredentialPayload(
                    runToken,
                    TunnelCredentialSource.TOKEN,
                    "",
                    "",
                    "",
                    "",
                    0,
                )
            }
            val canonical = canonicalHttpsRoot(hostname) ?: return null
            if (port !in 1..65535) return null
            return TunnelCredentialPayload(
                runToken,
                TunnelCredentialSource.TOKEN,
                "",
                "",
                "",
                canonical,
                port,
            )
        }

        private fun createBrowser(
            runToken: String,
            accountId: String,
            tunnelId: String,
            tunnelName: String,
            hostname: String,
            port: Int,
        ): TunnelCredentialPayload? {
            if (!ACCOUNT_ID_PATTERN.matches(accountId)) return null
            val canonicalTunnelId = canonicalUuid(tunnelId) ?: return null
            val canonicalTunnelName = tunnelName.trim()
            if (
                canonicalTunnelName.isEmpty() ||
                canonicalTunnelName.toByteArray(StandardCharsets.UTF_8).size > MAX_TUNNEL_NAME_BYTES ||
                canonicalTunnelName.any(Char::isISOControl)
            ) {
                return null
            }
            val canonical = canonicalHttpsRoot(hostname) ?: return null
            if (port !in 1..65535) return null
            return TunnelCredentialPayload(
                runToken,
                TunnelCredentialSource.BROWSER_LOGIN,
                accountId,
                canonicalTunnelId,
                canonicalTunnelName,
                canonical,
                port,
            )
        }

        private fun canonicalUuid(value: String): String? {
            if (!UUID_PATTERN.matches(value)) return null
            return runCatching {
                UUID.fromString(value).takeUnless { it == UUID(0, 0) }?.toString()
            }.getOrNull()
        }

        private fun canonicalHttpsRoot(value: String): String? =
            ReadReceiptsTunnelService.canonicalPublicRoot(value)
    }
}

internal sealed interface TunnelCredentialDecode {
    data class Decoded(
        val payload: TunnelCredentialPayload,
        val migratedLegacy: Boolean,
    ) : TunnelCredentialDecode

    data object Invalid : TunnelCredentialDecode
}

internal sealed interface StrictJsonRead {
    data object NotJson : StrictJsonRead

    data object InvalidJson : StrictJsonRead

    class Parsed(val value: JsonElement) : StrictJsonRead {
        override fun toString(): String = "StrictJsonRead.Parsed(value=[redacted])"
    }
}

/** Strict RFC JSON reader that rejects escaped-equivalent duplicate keys at every object depth. */
internal object StrictJsonReader {
    const val MAX_DEPTH = 64
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun read(text: String): StrictJsonRead {
        val start = text.skipJsonWhitespace(0)
        if (start == text.length) return StrictJsonRead.NotJson
        var end = text.length
        while (end > start && text[end - 1].isJsonWhitespace()) end--
        val validLexeme = when (text[start]) {
            '{', '[', '"' -> true
            't' -> end - start == 4 && text.regionMatches(start, "true", 0, 4)
            'f' -> end - start == 5 && text.regionMatches(start, "false", 0, 5)
            'n' -> end - start == 4 && text.regionMatches(start, "null", 0, 4)
            '-', in '0'..'9' -> text.isJsonNumber(start, end)
            else -> false
        }
        if (!validLexeme) return StrictJsonRead.NotJson
        if (!text.hasBoundedJsonDepth(start, end)) return StrictJsonRead.InvalidJson
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return StrictJsonRead.InvalidJson
        val duplicate = hasDuplicateObjectKeys(text) ?: return StrictJsonRead.InvalidJson
        return if (duplicate) StrictJsonRead.InvalidJson else StrictJsonRead.Parsed(parsed)
    }

    private fun hasDuplicateObjectKeys(text: String): Boolean? = runCatching {
        DuplicateKeyScanner(text, json).scan()
    }.getOrNull()

    /** Checks RFC 8259 number grammar directly over [start, end), without copying the lexeme. */
    private fun String.isJsonNumber(start: Int, end: Int): Boolean {
        var index = start
        if (this[index] == '-') {
            index++
            if (index == end) return false
        }
        when (this[index]) {
            '0' -> {
                index++
                if (index < end && this[index] in '0'..'9') return false
            }
            in '1'..'9' -> {
                index++
                while (index < end && this[index] in '0'..'9') index++
            }
            else -> return false
        }
        if (index < end && this[index] == '.') {
            index++
            if (index == end || this[index] !in '0'..'9') return false
            while (index < end && this[index] in '0'..'9') index++
        }
        if (index < end && (this[index] == 'e' || this[index] == 'E')) {
            index++
            if (index < end && (this[index] == '+' || this[index] == '-')) index++
            if (index == end || this[index] !in '0'..'9') return false
            while (index < end && this[index] in '0'..'9') index++
        }
        return index == end
    }

    /** Prevents the DOM parser and duplicate scanner from seeing adversarially deep structures. */
    private fun String.hasBoundedJsonDepth(start: Int, end: Int): Boolean {
        val containers = CharArray(MAX_DEPTH)
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until end) {
            val current = this[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (current) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                continue
            }
            when (current) {
                '"' -> inString = true
                '{', '[' -> {
                    if (depth == MAX_DEPTH) return false
                    containers[depth] = current
                    depth++
                }
                '}' -> {
                    if (depth == 0 || containers[depth - 1] != '{') return false
                    depth--
                }
                ']' -> {
                    if (depth == 0 || containers[depth - 1] != '[') return false
                    depth--
                }
            }
        }
        return depth == 0 && !inString && !escaped
    }

    private class DuplicateKeyScanner(
        private val text: String,
        private val json: Json,
    ) {
        private var index = 0

        fun scan(): Boolean {
            val duplicate = scanValue()
            index = text.skipJsonWhitespace(index)
            check(index == text.length)
            return duplicate
        }

        private fun scanValue(): Boolean {
            index = text.skipJsonWhitespace(index)
            return when (text[index]) {
                '{' -> scanObject()
                '[' -> scanArray()
                '"' -> {
                    index = text.jsonStringEnd(index)
                    false
                }
                else -> {
                    while (index < text.length && text[index] !in VALUE_DELIMITERS) index++
                    false
                }
            }
        }

        private fun scanObject(): Boolean {
            index++
            val keys = mutableSetOf<String>()
            var duplicate = false
            index = text.skipJsonWhitespace(index)
            if (text[index] == '}') {
                index++
                return false
            }
            while (true) {
                index = text.skipJsonWhitespace(index)
                val keyEnd = text.jsonStringEnd(index)
                val key = json.parseToJsonElement(text.substring(index, keyEnd))
                    .jsonPrimitive.content
                duplicate = !keys.add(key) || duplicate
                index = text.skipJsonWhitespace(keyEnd)
                check(text[index] == ':')
                index++
                duplicate = scanValue() || duplicate
                index = text.skipJsonWhitespace(index)
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return duplicate
                    }
                    else -> error("invalid object boundary")
                }
            }
        }

        private fun scanArray(): Boolean {
            index++
            var duplicate = false
            index = text.skipJsonWhitespace(index)
            if (text[index] == ']') {
                index++
                return false
            }
            while (true) {
                duplicate = scanValue() || duplicate
                index = text.skipJsonWhitespace(index)
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return duplicate
                    }
                    else -> error("invalid array boundary")
                }
            }
        }

        private companion object {
            val VALUE_DELIMITERS = setOf(' ', '\t', '\r', '\n', ',', ']', '}')
        }
    }

    private fun String.skipJsonWhitespace(start: Int): Int {
        var index = start
        while (index < length && this[index].isJsonWhitespace()) index++
        return index
    }

    private fun Char.isJsonWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private fun String.jsonStringEnd(start: Int): Int {
        check(start < length && this[start] == '"')
        var index = start + 1
        while (index < length) {
            when (this[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                else -> index++
            }
        }
        error("unterminated JSON string")
    }
}

internal object TunnelCredentialPayloadCodec {
    const val VERSION = 2
    const val MAX_BYTES = 32 * 1024
    private val fieldNames = setOf(
        "version",
        "runToken",
        "source",
        "accountId",
        "tunnelId",
        "tunnelName",
        "canonicalHostname",
        "fixedOriginPort",
    )

    fun encode(payload: TunnelCredentialPayload): ByteArray {
        val encoded = buildJsonObject {
            put("version", VERSION)
            put("runToken", payload.runToken)
            put("source", payload.source.name)
            put("accountId", payload.accountId)
            put("tunnelId", payload.tunnelId)
            put("tunnelName", payload.tunnelName)
            put("canonicalHostname", payload.canonicalHostname)
            put("fixedOriginPort", payload.fixedOriginPort)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        check(encoded.size <= MAX_BYTES)
        return encoded
    }

    fun decode(plaintext: ByteArray): TunnelCredentialDecode {
        if (plaintext.isEmpty() || plaintext.size > MAX_BYTES) return TunnelCredentialDecode.Invalid
        val text = decodeUtf8(plaintext) ?: return TunnelCredentialDecode.Invalid
        return when (val jsonRead = StrictJsonReader.read(text)) {
            is StrictJsonRead.Parsed -> {
                val objectValue = jsonRead.value as? JsonObject
                    ?: return TunnelCredentialDecode.Invalid
                decodeVersioned(objectValue)
            }
            StrictJsonRead.InvalidJson -> TunnelCredentialDecode.Invalid
            StrictJsonRead.NotJson -> TunnelCredentialPayload.create(
                runToken = text,
                source = TunnelCredentialSource.TOKEN,
            )?.let { TunnelCredentialDecode.Decoded(it, migratedLegacy = true) }
                ?: TunnelCredentialDecode.Invalid
        }
    }

    private fun decodeVersioned(value: JsonObject): TunnelCredentialDecode {
        if (value.keys != fieldNames) return TunnelCredentialDecode.Invalid
        val version = value.number("version") ?: return TunnelCredentialDecode.Invalid
        if (version != VERSION) return TunnelCredentialDecode.Invalid
        val source = value.string("source")?.let {
            runCatching { TunnelCredentialSource.valueOf(it) }.getOrNull()
        } ?: return TunnelCredentialDecode.Invalid
        val payload = TunnelCredentialPayload.create(
            runToken = value.string("runToken") ?: return TunnelCredentialDecode.Invalid,
            source = source,
            accountId = value.string("accountId") ?: return TunnelCredentialDecode.Invalid,
            tunnelId = value.string("tunnelId") ?: return TunnelCredentialDecode.Invalid,
            tunnelName = value.string("tunnelName") ?: return TunnelCredentialDecode.Invalid,
            canonicalHostname = value.string("canonicalHostname")
                ?: return TunnelCredentialDecode.Invalid,
            fixedOriginPort = value.number("fixedOriginPort")
                ?: return TunnelCredentialDecode.Invalid,
        ) ?: return TunnelCredentialDecode.Invalid
        return TunnelCredentialDecode.Decoded(payload, migratedLegacy = false)
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.number(name: String): Int? =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun decodeUtf8(value: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    }.getOrNull()
}

internal enum class TunnelCredentialStartupDecision {
    START,
    NEEDS_USER_ACTION,
}

internal fun decideCredentialStartup(
    payload: TunnelCredentialPayload,
    requestedMode: ReadReceiptsTunnelMode,
    requestedHostname: String,
    requestedOriginPort: Int,
): TunnelCredentialStartupDecision {
    return when (requestedMode) {
        ReadReceiptsTunnelMode.QUICK -> TunnelCredentialStartupDecision.NEEDS_USER_ACTION
        ReadReceiptsTunnelMode.TOKEN -> if (payload.source == TunnelCredentialSource.TOKEN) {
            TunnelCredentialStartupDecision.START
        } else {
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION
        }
        ReadReceiptsTunnelMode.BROWSER_LOGIN -> if (
            payload.source == TunnelCredentialSource.BROWSER_LOGIN &&
            ReadReceiptsTunnelService.canonicalPublicRoot(requestedHostname) == requestedHostname &&
            requestedHostname == payload.canonicalHostname &&
            requestedOriginPort == payload.fixedOriginPort
        ) {
            TunnelCredentialStartupDecision.START
        } else {
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION
        }
    }
}

internal sealed interface BrowserCredentialCommitDecision {
    data class Preserve(val authoritative: TunnelCredentialPayload?) : BrowserCredentialCommitDecision

    data class CommitCandidate(val payload: TunnelCredentialPayload) : BrowserCredentialCommitDecision
}

internal fun decideBrowserCredentialCommit(
    authoritative: TunnelCredentialPayload?,
    selectionSucceeded: Boolean,
    publicHealthVerified: Boolean,
    candidateFactory: () -> TunnelCredentialPayload?,
): BrowserCredentialCommitDecision {
    if (!selectionSucceeded || !publicHealthVerified) {
        return BrowserCredentialCommitDecision.Preserve(authoritative)
    }
    val candidate = candidateFactory()
        ?: return BrowserCredentialCommitDecision.Preserve(authoritative)
    if (candidate.source != TunnelCredentialSource.BROWSER_LOGIN) {
        return BrowserCredentialCommitDecision.Preserve(authoritative)
    }
    return BrowserCredentialCommitDecision.CommitCandidate(candidate)
}

internal data class CommittedBrowserTunnelMetadata(
    val accountId: String,
    val tunnelId: String,
    val tunnelName: String,
    val canonicalHostname: String,
    val fixedOriginPort: Int,
)

internal data class CommittedTunnelCredentialMetadata(
    val source: TunnelCredentialSource,
    val accountId: String,
    val tunnelId: String,
    val tunnelName: String,
    val canonicalHostname: String,
    val fixedOriginPort: Int,
)

internal fun TunnelCredentialPayload.committedMetadata(): CommittedTunnelCredentialMetadata =
    CommittedTunnelCredentialMetadata(
        source = source,
        accountId = accountId,
        tunnelId = tunnelId,
        tunnelName = tunnelName,
        canonicalHostname = canonicalHostname,
        fixedOriginPort = fixedOriginPort,
    )

internal sealed interface TunnelCredentialSnapshot {
    data class Authoritative(val payload: TunnelCredentialPayload) : TunnelCredentialSnapshot

    data object Stale : TunnelCredentialSnapshot

    data object Invalid : TunnelCredentialSnapshot

    data object None : TunnelCredentialSnapshot
}

internal sealed interface BrowserMetadataRebindDecision {
    data class Replace(val metadata: CommittedBrowserTunnelMetadata) : BrowserMetadataRebindDecision

    data object Keep : BrowserMetadataRebindDecision
}

internal fun decideBrowserMetadataRebind(
    snapshot: TunnelCredentialSnapshot,
): BrowserMetadataRebindDecision {
    val payload = (snapshot as? TunnelCredentialSnapshot.Authoritative)?.payload
        ?: return BrowserMetadataRebindDecision.Keep
    if (payload.source != TunnelCredentialSource.BROWSER_LOGIN) {
        return BrowserMetadataRebindDecision.Keep
    }
    return BrowserMetadataRebindDecision.Replace(
        CommittedBrowserTunnelMetadata(
            accountId = payload.accountId,
            tunnelId = payload.tunnelId,
            tunnelName = payload.tunnelName,
            canonicalHostname = payload.canonicalHostname,
            fixedOriginPort = payload.fixedOriginPort,
        ),
    )
}

internal fun applyBrowserMetadataRebind(
    current: CommittedBrowserTunnelMetadata?,
    decision: BrowserMetadataRebindDecision,
): CommittedBrowserTunnelMetadata? = when (decision) {
    BrowserMetadataRebindDecision.Keep -> current
    is BrowserMetadataRebindDecision.Replace -> decision.metadata
}
