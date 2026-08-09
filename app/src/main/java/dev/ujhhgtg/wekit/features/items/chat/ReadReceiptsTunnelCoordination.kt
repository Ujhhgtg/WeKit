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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
    fun advance(generation: Long, transition: () -> Unit): Boolean {
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
        var generation: Long,
        val callbacks: MutableList<() -> Unit>,
    )

    private var pending: Pending? = null
    private var completedGeneration: Long? = null

    @Synchronized
    fun register(
        callback: (() -> Unit)?,
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
            mutableListOf<() -> Unit>().apply { if (callback != null) add(callback) },
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
    fun hasPendingStop(): Boolean = pending != null

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

    fun replaceGeneration(generation: Long): Boolean {
        require(generation > 0)
        val deliveries = synchronized(this) {
            val current = currentGeneration
            if (current != null && generation <= current) return false
            currentGeneration = generation
            pending.values.map { it to AuthOperationTerminal.Superseded }.also { pending.clear() }
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
            if (currentGeneration == null) currentGeneration = key.authGeneration
            if (key.authGeneration != currentGeneration || pending.containsKey(key)) {
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

/** Strict RFC JSON reader that rejects escaped-equivalent duplicate keys at every object depth. */
internal object StrictJsonReader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }
    private val jsonNumberPattern =
        Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun parse(text: String): JsonElement? {
        val start = text.skipJsonWhitespace(0)
        if (start == text.length) return null
        var end = text.length
        while (end > start && text[end - 1].isJsonWhitespace()) end--
        val validLexeme = when (text[start]) {
            '{', '[', '"' -> true
            't' -> end - start == 4 && text.regionMatches(start, "true", 0, 4)
            'f' -> end - start == 5 && text.regionMatches(start, "false", 0, 5)
            'n' -> end - start == 4 && text.regionMatches(start, "null", 0, 4)
            '-', in '0'..'9' -> jsonNumberPattern.matches(text.substring(start, end))
            else -> false
        }
        if (!validLexeme) return null
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        if (hasDuplicateObjectKeys(text)) return null
        return parsed
    }

    private fun hasDuplicateObjectKeys(text: String): Boolean = runCatching {
        DuplicateKeyScanner(text, json).scan()
    }.getOrElse { true }

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
        val parsed = StrictJsonReader.parse(text)
        if (parsed != null) {
            val objectValue = parsed as? JsonObject ?: return TunnelCredentialDecode.Invalid
            return decodeVersioned(objectValue)
        }
        if (text.firstOrNull { !it.isWhitespace() } == '{') return TunnelCredentialDecode.Invalid
        return TunnelCredentialPayload.create(
            runToken = text,
            source = TunnelCredentialSource.TOKEN,
        )?.let { TunnelCredentialDecode.Decoded(it, migratedLegacy = true) }
            ?: TunnelCredentialDecode.Invalid
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
    requestedHostname: String,
    requestedOriginPort: Int,
): TunnelCredentialStartupDecision {
    if (payload.source == TunnelCredentialSource.TOKEN) return TunnelCredentialStartupDecision.START
    val canonicalRequested = ReadReceiptsTunnelService.canonicalPublicRoot(requestedHostname)
    return if (
        canonicalRequested == payload.canonicalHostname &&
        requestedOriginPort == payload.fixedOriginPort
    ) {
        TunnelCredentialStartupDecision.START
    } else {
        TunnelCredentialStartupDecision.NEEDS_USER_ACTION
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
