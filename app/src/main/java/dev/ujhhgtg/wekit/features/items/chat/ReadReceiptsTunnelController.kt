package dev.ujhhgtg.wekit.features.items.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import dev.ujhhgtg.wekit.utils.HostInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** WeChat-process Binder client. The service remains the authoritative tunnel state owner. */
internal object ReadReceiptsTunnelController {
    private val generation = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val lastIssuedGeneration = AtomicLong()
    private val clientNonce = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP) }

    internal fun originAuthenticator(): String = clientNonce
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incoming = Messenger(IncomingHandler(Looper.getMainLooper()))
    private val handoffGate = TunnelHandoffGate()
    private val stopCompletion = TunnelStopCompletion()
    private val authOperations = ControllerAuthOperationQueue()
    private val authState = ControllerAuthStateStore()
    private val authRequestId = AtomicLong(
        SecureRandom().nextLong().ushr(1).coerceAtLeast(1L),
    )
    private val authGeneration = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val pendingAuthMessages = linkedMapOf<AuthOperationKey, PendingAuthMessage<*>>()
    private val pendingSelectReadiness = linkedMapOf<Long, PendingSelectReadiness>()

    @Volatile
    private var service: Messenger? = null

    @Volatile
    private var binding = false

    @Volatile
    private var bound = false

    @Volatile
    private var pendingStart: PendingStart? = null

    @Volatile
    private var pendingCommand: Message? = null

    private var awaitingAuthSnapshot = false

    @Volatile
    var status: ReadReceiptsTunnelStatus = ReadReceiptsTunnelStatus(
        ReadReceiptsTunnelState.STOPPED,
    )
        private set

    @Volatile
    var credentialExists: Boolean = false
        private set

    val browserLoginState: CloudflareLoginState
        get() = authState.currentSnapshot()?.loginState ?: stoppedBrowserLoginState()

    val browserAccountId: String
        get() = authState.currentSnapshot()?.accountId.orEmpty()

    val browserExistingTunnels: List<ExistingTunnel>
        get() = authState.currentSnapshot()?.tunnels ?: emptyList()

    val committedCredentialMetadata: CommittedTunnelCredentialMetadata?
        get() = authState.currentSnapshot()?.committedMetadata

    val browserMetadataRebindDecision: BrowserMetadataRebindDecision
        get() = authState.currentSnapshot()?.browserMetadataRebindDecision()
            ?: BrowserMetadataRebindDecision.Keep

    val browserLoginRestartRequired: Boolean
        get() = authState.currentSnapshot()?.restartRequired ?: false

    val credentialMetadataLoading: Boolean
        get() = authState.currentSnapshot()?.metadataLoading ?: true

    fun verifiedEndpoint(): String? = status
        .takeIf { it.state == ReadReceiptsTunnelState.CONNECTED }
        ?.publicUrl

    fun needsVisibleStart() {
        if (status.state == ReadReceiptsTunnelState.CONNECTED) return
        status = ReadReceiptsTunnelStatus(
            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
            errorCode = ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED,
        )
    }

    fun startVisible(
        mode: ReadReceiptsTunnelMode,
        originPort: Int,
        hostname: String,
        token: String?,
        onHandoff: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        val handoffDelivery = TunnelHandoffTerminalDelivery(onHandoff)
        handoffGate.drainPending(
            pendingGeneration = { pendingStart?.generation },
            supersede = { supersededGeneration ->
                supersedePendingStart(supersededGeneration)
            },
        )
        val nextGeneration = when (val admission = stopCompletion.startAdmission(::nextGeneration)) {
            is TunnelStartAdmission.Admitted -> admission.generation
            is TunnelStartAdmission.Rejected -> {
                handoffDelivery.complete(Result.failure(admission.failure))
                return
            }
        }
        val context = HostInfo.application
        handoffGate.begin(nextGeneration)
        status = ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING)
        val startIntent = serviceIntent(context).apply {
            action = ReadReceiptsTunnelService.ACTION_START
        }
        val started = runCatching { ContextCompat.startForegroundService(context, startIntent) }
        if (started.isFailure) {
            status = ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                errorCode = ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED,
            )
            handoffGate.fail(nextGeneration)
            handoffDelivery.complete(Result.failure(tunnelException(
                ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED,
                "foreground tunnel service startup was rejected",
                started.exceptionOrNull(),
            )))
            return
        }

        val command = Message.obtain(null, ReadReceiptsTunnelProtocol.START).apply {
            replyTo = incoming
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, nextGeneration)
                putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, clientNonce)
                putString(ReadReceiptsTunnelProtocol.KEY_MODE, mode.name)
                putString(
                    ReadReceiptsTunnelProtocol.KEY_ORIGIN,
                    "http://127.0.0.1:$originPort/",
                )
                putString(ReadReceiptsTunnelProtocol.KEY_HOSTNAME, hostname)
                if (token != null) putString(ReadReceiptsTunnelProtocol.KEY_TOKEN, token)
            }
        }
        pendingStart = PendingStart(nextGeneration, command, handoffDelivery)
        sendPendingOrBind(context)
        mainHandler.postDelayed(
            {
                failPendingStart(
                    nextGeneration,
                    tunnelException(
                        ReadReceiptsTunnelErrorCode.START_HANDOFF_TIMEOUT,
                        "tunnel service handoff timed out",
                    ),
                )
            },
            START_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    fun stop(onStopped: ((Result<Unit>) -> Unit)? = null) {
        handoffGate.drainPending(
            pendingGeneration = { pendingStart?.generation },
            supersede = ::supersedePendingStart,
        )
        val registration = stopCompletion.register(
            callback = onStopped,
            latestIssuedGeneration = generation.get(),
            generationFactory = ::nextGeneration,
        )
        if (!registration.shouldSend) return
        val nextGeneration = registration.generation
        status = ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPING)
        val command = Message.obtain(null, ReadReceiptsTunnelProtocol.STOP).apply {
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, nextGeneration)
            }
        }
        queueOrSend(HostInfo.application, command)
        mainHandler.postDelayed(
            {
                if (status.state != ReadReceiptsTunnelState.STOPPED) {
                    val drain = stopCompletion.completeTimeout(
                        generation = nextGeneration,
                        authoritativeGeneration = generation.get(),
                    )
                    if (!drain.matched) return@postDelayed
                    status = ReadReceiptsTunnelStatus(
                        ReadReceiptsTunnelState.FAILED,
                        errorCode = ReadReceiptsTunnelErrorCode.STOP_TIMEOUT,
                    )
                    val failure = Result.failure<Unit>(
                        tunnelException(
                            ReadReceiptsTunnelErrorCode.STOP_TIMEOUT,
                            "tunnel stop timed out",
                        ),
                    )
                    drain.callbacks.forEach { callback -> callback(failure) }
                }
            },
            STOP_COMPLETION_TIMEOUT_MILLIS,
        )
    }

    fun deleteCredential() {
        stopCompletion.runAdministrativeCommandIfIdle(
            hasPendingStart = { pendingStart != null },
            command = {
                val currentGeneration = generation.get()
                val command = Message.obtain(
                    null,
                    ReadReceiptsTunnelProtocol.DELETE_CREDENTIAL,
                ).apply {
                    data = Bundle().apply {
                        putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, currentGeneration)
                    }
                }
                queueOrSend(HostInfo.application, command)
            }
        )
    }

    fun refresh() {
        bind(HostInfo.application)
    }

    suspend fun beginBrowserLogin(): CloudflareLoginState {
        val generation = nextAuthGeneration()
        return executeAuthOperation(
            generation = generation,
            kind = AuthOperationKind.BEGIN,
            what = ReadReceiptsTunnelProtocol.BEGIN_LOGIN,
            beginGeneration = true,
        )
    }

    suspend fun listExistingTunnels(): List<ExistingTunnel> {
        val generation = requireExpectedAuthGeneration()
        return executeAuthOperation<List<ExistingTunnel>>(
            generation = generation,
            kind = AuthOperationKind.LIST,
            what = ReadReceiptsTunnelProtocol.LIST_TUNNELS,
        )
    }

    suspend fun selectExistingTunnel(
        id: String,
        canonicalRoot: String,
        fixedPort: Int,
    ): Result<Unit> = runCatching {
        if (!ExistingTunnel.isCanonicalId(id)) throw authException(
            ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
            "invalid tunnel ID",
        )
        if (
            ReadReceiptsTunnelService.canonicalPublicRoot(canonicalRoot) != canonicalRoot
        ) {
            throw authException(
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                "invalid tunnel hostname",
            )
        }
        if (fixedPort !in 1..65535) throw authException(
            ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
            "invalid loopback port",
        )
        val connectorGeneration = when (
            val admission = stopCompletion.startAdmission(::reserveConnectorGeneration)
        ) {
            is TunnelStartAdmission.Admitted -> admission.generation
            is TunnelStartAdmission.Rejected -> throw admission.failure
        }
        val generation = requireExpectedAuthGeneration()
        awaitSelectForegroundReady(connectorGeneration)
        executeAuthOperation<Unit>(
            generation = generation,
            kind = AuthOperationKind.SELECT,
            what = ReadReceiptsTunnelProtocol.SELECT_TUNNEL,
            extras = {
                putString(ReadReceiptsTunnelProtocol.KEY_TUNNEL_ID, id)
                putString(ReadReceiptsTunnelProtocol.KEY_HOSTNAME, canonicalRoot)
                putInt(ReadReceiptsTunnelProtocol.KEY_FIXED_ORIGIN_PORT, fixedPort)
                putLong(
                    ReadReceiptsTunnelProtocol.KEY_CONNECTOR_GENERATION,
                    connectorGeneration,
                )
            },
        )
    }

    suspend fun cancelBrowserLogin(): Result<Unit> = clearBrowserLogin(
        AuthOperationKind.CANCEL,
        ReadReceiptsTunnelProtocol.CANCEL_LOGIN,
    )

    suspend fun logoutBrowserLogin(): Result<Unit> = clearBrowserLogin(
        AuthOperationKind.LOGOUT,
        ReadReceiptsTunnelProtocol.LOGOUT,
    )

    private suspend fun clearBrowserLogin(
        kind: AuthOperationKind<Unit>,
        what: Int,
    ): Result<Unit> = runCatching {
        val generation = requireExpectedAuthGeneration()
        executeAuthOperation(
            generation = generation,
            kind = kind,
            what = what,
        )
    }

    private suspend fun <T> executeAuthOperation(
        generation: Long,
        kind: AuthOperationKind<T>,
        what: Int,
        beginGeneration: Boolean = false,
        extras: Bundle.() -> Unit = {},
    ): T = suspendCancellableCoroutine { continuation ->
        val keyReference = AtomicReference<AuthOperationKey?>()
        val submit = Runnable {
            if (!continuation.isActive) return@Runnable
            if (beginGeneration) {
                if (!authState.expectBegin(generation)) {
                    continuation.resumeWithException(authException(
                        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                        "browser login request is stale",
                    ))
                    return@Runnable
                }
                if (!authOperations.replaceGeneration(generation)) {
                    continuation.resumeWithException(authException(
                        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                        "browser login request is stale",
                    ))
                    return@Runnable
                }
            }
            val key = AuthOperationKey(generation, nextAuthRequestId())
            keyReference.set(key)
            val command = Message.obtain(null, what).apply {
                replyTo = incoming
                data = authIdentityBundle(key, kind).apply(extras)
            }
            val timeout = Runnable {
                if (!authOperations.timeout(key, kind)) return@Runnable
                pendingAuthMessages.remove(key)
            }
            val enqueued = authOperations.enqueue(key, kind) { terminal ->
                mainHandler.removeCallbacks(timeout)
                pendingAuthMessages.remove(key)
                deliverAuthTerminal(continuation, terminal)
            }
            if (!enqueued) {
                continuation.resumeWithException(authException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "browser auth request is stale",
                ))
                return@Runnable
            }
            pendingAuthMessages[key] = PendingAuthMessage(key, kind, command, timeout)
            mainHandler.postDelayed(timeout, AUTH_OPERATION_TIMEOUT_MILLIS)
            sendPendingOrBind(HostInfo.application)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) submit.run() else mainHandler.post(submit)
        continuation.invokeOnCancellation {
            mainHandler.post {
                val key = keyReference.get() ?: return@post
                @Suppress("UNCHECKED_CAST")
                cancelPendingAuth(key, kind as AuthOperationKind<Any?>)
            }
        }
    }

    private fun <T> deliverAuthTerminal(
        continuation: CancellableContinuation<T>,
        terminal: AuthOperationTerminal<T>,
    ) {
        if (!continuation.isActive) return
        when (terminal) {
            is AuthOperationTerminal.Completed -> continuation.resume(terminal.value)
            is AuthOperationTerminal.Failed ->
                continuation.resumeWithException(authException(
                    terminal.error.toTunnelErrorCode(),
                    "browser auth operation failed",
                ))
            AuthOperationTerminal.Superseded ->
                continuation.resumeWithException(authException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "browser auth request was superseded",
                ))
            AuthOperationTerminal.TimedOut ->
                continuation.resumeWithException(authException(
                    ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                    "browser auth request timed out",
                ))
            AuthOperationTerminal.Cancelled ->
                continuation.resumeWithException(authException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "browser auth request was cancelled",
                ))
        }
    }

    private fun cancelPendingAuth(key: AuthOperationKey, kind: AuthOperationKind<Any?>) {
        val pending = pendingAuthMessages[key] ?: return
        if (!authOperations.cancel(key, kind)) return
        mainHandler.removeCallbacks(pending.timeout)
        pendingAuthMessages.remove(key)
    }

    private suspend fun awaitSelectForegroundReady(connectorGeneration: Long) {
        suspendCancellableCoroutine { continuation ->
            val submit = Runnable {
                if (!continuation.isActive) return@Runnable
                val timeout = Runnable {
                    val pending = pendingSelectReadiness.remove(connectorGeneration)
                        ?: return@Runnable
                    if (pending.continuation.isActive) {
                        pending.continuation.resumeWithException(
                            authException(
                                ReadReceiptsTunnelErrorCode.START_HANDOFF_TIMEOUT,
                                "foreground tunnel service startup timed out",
                            ),
                        )
                    }
                }
                pendingSelectReadiness[connectorGeneration] = PendingSelectReadiness(
                    continuation,
                    timeout,
                )
                val started = runCatching {
                    ContextCompat.startForegroundService(
                        HostInfo.application,
                        serviceIntent(HostInfo.application).apply {
                            action = ReadReceiptsTunnelService.ACTION_START
                            putExtra(
                                ReadReceiptsTunnelService.EXTRA_SELECT_FOREGROUND_GENERATION,
                                connectorGeneration,
                            )
                        },
                    )
                }
                if (started.isFailure) {
                    pendingSelectReadiness.remove(connectorGeneration)
                    continuation.resumeWithException(
                        authException(
                            ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED,
                            "foreground tunnel service startup was rejected",
                            started.exceptionOrNull(),
                        ),
                    )
                    return@Runnable
                }
                mainHandler.postDelayed(timeout, SELECT_FOREGROUND_TIMEOUT_MILLIS)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) submit.run()
            else mainHandler.post(submit)
            continuation.invokeOnCancellation {
                mainHandler.post {
                    val pending = pendingSelectReadiness.remove(connectorGeneration)
                        ?: return@post
                    mainHandler.removeCallbacks(pending.timeout)
                }
            }
        }
    }

    private fun requireExpectedAuthGeneration(): Long =
        authState.lastSeenAuthGeneration().takeIf { it > 0 }
            ?: throw authException(
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                "browser login is not active",
            )

    private fun nextAuthGeneration(): Long = authGeneration.updateAndGet { current ->
        maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
    }

    private fun nextAuthRequestId(): Long = authRequestId.updateAndGet { current ->
        if (current == Long.MAX_VALUE) 1 else current + 1
    }

    private fun authException(
        errorCode: ReadReceiptsTunnelErrorCode,
        diagnostic: String,
        cause: Throwable? = null,
    ): BrowserLoginException = BrowserLoginException(
        errorCode,
        diagnostic.take(MAX_AUTH_ERROR_CHARS),
        cause,
    )

    private fun String.toTunnelErrorCode(): ReadReceiptsTunnelErrorCode =
        ReadReceiptsTunnelErrorCode.entries.firstOrNull { it.name == this }
            ?: ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE

    private fun tunnelException(
        errorCode: ReadReceiptsTunnelErrorCode,
        diagnostic: String,
        cause: Throwable? = null,
    ): ReadReceiptsTunnelException = ReadReceiptsTunnelException(
        errorCode,
        diagnostic,
        cause,
    )

    private fun <T> authIdentityBundle(
        key: AuthOperationKey,
        kind: AuthOperationKind<T>,
    ): Bundle = Bundle().apply {
        putLong(ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION, key.authGeneration)
        putLong(ReadReceiptsTunnelProtocol.KEY_AUTH_REQUEST_ID, key.requestId)
        putString(
            ReadReceiptsTunnelProtocol.KEY_AUTH_KIND,
            when (kind) {
                AuthOperationKind.BEGIN -> "BEGIN"
                AuthOperationKind.LIST -> "LIST"
                AuthOperationKind.SELECT -> "SELECT"
                AuthOperationKind.CANCEL -> "CANCEL"
                AuthOperationKind.LOGOUT -> "LOGOUT"
            },
        )
        putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, clientNonce)
    }

    fun openNotificationSettings(context: Context): Result<Unit> = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, MODULE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun queueOrSend(context: Context, command: Message) {
        pendingCommand = command
        sendPendingOrBind(context)
    }

    private fun sendPendingOrBind(context: Context) {
        if (!sendPending()) bind(context)
    }

    private fun sendPending(): Boolean {
        val target = service ?: return false
        val start = pendingStart
        if (start != null && !start.sent) {
            val sent = runCatching { target.send(start.command) }.isSuccess
            if (!sent) {
                onBinderDied()
                return false
            }
            start.sent = true
        }
        val command = pendingCommand
        if (command != null) {
            val sent = runCatching { target.send(command) }.isSuccess
            if (!sent) {
                onBinderDied()
                return false
            }
            pendingCommand = null
        }
        val unsentAuth = authOperations.unsentKeys()
        pendingAuthMessages.values
            .filter { it.key in unsentAuth }
            .forEach { pending ->
                val sent = runCatching { target.send(pending.command) }.isSuccess
                if (!sent) {
                    onBinderDied()
                    return false
                }
                check(authOperations.markSent(pending.key, pending.kind, target.binder))
            }
        return true
    }

    private fun bind(context: Context) {
        if (binding || bound) return
        binding = true
        val succeeded = runCatching {
            context.bindService(serviceIntent(context), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!succeeded) {
            binding = false
            status = ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                errorCode = ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
            )
            failPendingStart(tunnelException(
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                "could not bind tunnel service",
            ))
            stopCompletion.pendingGeneration()?.let { stoppingGeneration ->
                completeStop(
                    stoppingGeneration,
                    Result.failure(tunnelException(
                        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                        "could not confirm tunnel stop after bind failure",
                    )),
                )
            }
            if (authOperations.unsentKeys().isNotEmpty()) {
                mainHandler.postDelayed({ bind(HostInfo.application) }, REBIND_DELAY_MILLIS)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            bound = true
            service = Messenger(binder)
            awaitingAuthSnapshot = true
            runCatching { binder.linkToDeath({ onBinderDied(binder) }, 0) }
            val register = Message.obtain(null, ReadReceiptsTunnelProtocol.REGISTER).apply {
                replyTo = incoming
                data = Bundle().apply {
                    putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, clientNonce)
                    authState.lastSeenAuthGeneration().takeIf { it > 0 }?.let {
                        putLong(ReadReceiptsTunnelProtocol.KEY_LAST_SEEN_AUTH_GENERATION, it)
                    }
                }
            }
            runCatching { service!!.send(register) }
                .onFailure { onBinderDied(binder) }
            sendPending()
        }

        override fun onServiceDisconnected(name: ComponentName) = onBinderDied()

        override fun onBindingDied(name: ComponentName) = onBinderDied()

        override fun onNullBinding(name: ComponentName) = onBinderDied()
    }

    private fun onBinderDied(expectedBinder: IBinder? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleBinderDied(expectedBinder) }
        } else {
            handleBinderDied(expectedBinder)
        }
    }

    private fun handleBinderDied(expectedBinder: IBinder? = null) {
        val currentBinder = service?.binder
        if (expectedBinder != null && currentBinder !== expectedBinder) {
            authOperations.binderDied(
                expectedBinder,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE.name,
            )
            return
        }
        val deadBinder = currentBinder
        service = null
        binding = false
        bound = false
        awaitingAuthSnapshot = false
        if (deadBinder != null) {
            authOperations.binderDied(
                deadBinder,
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE.name,
            )
        }
        if (authOperations.unsentKeys().isNotEmpty()) {
            mainHandler.postDelayed({ bind(HostInfo.application) }, REBIND_DELAY_MILLIS)
        }
        failPendingStart(tunnelException(
            ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
            "tunnel service disconnected before handoff",
        ))
        val stoppingGeneration = stopCompletion.pendingGeneration()
        if (stoppingGeneration != null) {
            completeStop(
                stoppingGeneration,
                Result.failure(tunnelException(
                    ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                    "could not confirm tunnel stop after Binder teardown",
                )),
            )
            return
        }
        if (status.state !in setOf(
                ReadReceiptsTunnelState.STOPPED,
                ReadReceiptsTunnelState.FAILED,
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
            )
        ) {
            status = ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                errorCode = ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
            )
            mainHandler.postDelayed({ bind(HostInfo.application) }, REBIND_DELAY_MILLIS)
        }
    }

    private class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (
                message.data.getString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE) != clientNonce
            ) {
                return
            }
            if (message.what == ReadReceiptsTunnelProtocol.START_ACK) {
                handleStartAck(message.data)
                return
            }
            if (message.what == ReadReceiptsTunnelProtocol.AUTH_ACK) {
                handleAuthAck(message.data)
                return
            }
            if (message.what == ReadReceiptsTunnelProtocol.AUTH_TERMINAL) {
                handleAuthTerminal(message.data)
                return
            }
            if (message.what == ReadReceiptsTunnelProtocol.AUTH_SNAPSHOT) {
                handleAuthSnapshot(message.data)
                return
            }
            if (message.what == ReadReceiptsTunnelProtocol.SELECT_FOREGROUND_READY) {
                handleSelectForegroundReady(message.data)
                return
            }
            if (message.what != ReadReceiptsTunnelProtocol.STATUS) return
            val data = message.data
            val decoded = decodeReadReceiptsTunnelStatus(
                data.keySet().associateWith { key -> data.get(key) },
            ) ?: return
            if (decoded.clientNonce != clientNonce) return
            val incomingGeneration = decoded.generation
            if (incomingGeneration < lastIssuedGeneration.get()) return
            generation.updateAndGet { current -> maxOf(current, incomingGeneration) }
            lastIssuedGeneration.updateAndGet { current -> maxOf(current, incomingGeneration) }
            credentialExists = decoded.credentialExists
            status = decoded.status
            if (decoded.status.state == ReadReceiptsTunnelState.STOPPED) {
                val drain = stopCompletion.complete(incomingGeneration)
                if (!drain.matched) {
                    ReadReceipts.onTunnelServiceStopped()
                } else {
                    drain.callbacks.forEach { callback -> callback(Result.success(Unit)) }
                }
                maybeUnbindStoppedService()
            }
        }
    }

    private fun handleAuthAck(data: Bundle) {
        if (data.keySet() != ReadReceiptsTunnelProtocol.AUTH_OPERATION_KEYS +
            ReadReceiptsTunnelProtocol.KEY_ACCEPTED
        ) {
            return
        }
        val identity = parseAuthIdentity(data) ?: return
        if (data.strictBoolean(ReadReceiptsTunnelProtocol.KEY_ACCEPTED) == null) return
        val binder = service?.binder ?: return
        authOperations.acknowledge(identity.key, identity.kind, binder)
    }

    private fun handleAuthTerminal(data: Bundle) {
        val identity = parseAuthIdentity(data) ?: return
        val terminalName = data.strictString(ReadReceiptsTunnelProtocol.KEY_AUTH_TERMINAL)
            ?: return
        val binder = service?.binder ?: return
        when (identity.kind) {
            AuthOperationKind.BEGIN -> completeBeginTerminal(identity.key, binder, terminalName, data)
            AuthOperationKind.LIST -> completeListTerminal(identity.key, binder, terminalName, data)
            AuthOperationKind.SELECT -> completeUnitTerminal(
                identity.key,
                AuthOperationKind.SELECT,
                binder,
                terminalName,
                data,
            )
            AuthOperationKind.CANCEL -> completeUnitTerminal(
                identity.key,
                AuthOperationKind.CANCEL,
                binder,
                terminalName,
                data,
            )
            AuthOperationKind.LOGOUT -> completeUnitTerminal(
                identity.key,
                AuthOperationKind.LOGOUT,
                binder,
                terminalName,
                data,
            )
        }
    }

    private fun completeBeginTerminal(
        key: AuthOperationKey,
        binder: IBinder,
        terminalName: String,
        data: Bundle,
    ) {
        val terminal = when (terminalName) {
            "COMPLETED" -> {
                if (data.keySet() != AUTH_TERMINAL_BASE_KEYS + BEGIN_TERMINAL_RESULT_KEYS) return
                val state = data.strictString(ReadReceiptsTunnelProtocol.KEY_STATE)
                    ?.let { name -> ReadReceiptsTunnelState.entries.firstOrNull { it.name == name } }
                    ?.takeIf {
                        it == ReadReceiptsTunnelState.STARTING ||
                            it == ReadReceiptsTunnelState.CONNECTED
                    } ?: return
                val authorizationUrl = data.strictString(
                    ReadReceiptsTunnelProtocol.KEY_AUTHORIZATION_URL,
                ) ?: return
                if (!ReadReceiptsTunnelNativeParser.isPinnedAuthorizationUrl(authorizationUrl)) return
                AuthOperationTerminal.Completed(
                    CloudflareLoginState(authorizationUrl, state, null),
                )
            }
            else -> parseNonCompletedTerminal<CloudflareLoginState>(terminalName, data) ?: return
        }
        authOperations.complete(key, AuthOperationKind.BEGIN, binder, terminal)
    }

    private fun completeListTerminal(
        key: AuthOperationKey,
        binder: IBinder,
        terminalName: String,
        data: Bundle,
    ) {
        val terminal = when (terminalName) {
            "COMPLETED" -> {
                if (data.keySet() != AUTH_TERMINAL_BASE_KEYS + ReadReceiptsTunnelProtocol.KEY_TUNNELS) {
                    return
                }
                AuthOperationTerminal.Completed(parseTunnels(data) ?: return)
            }
            else -> parseNonCompletedTerminal<List<ExistingTunnel>>(terminalName, data) ?: return
        }
        authOperations.complete(key, AuthOperationKind.LIST, binder, terminal)
    }

    private fun completeUnitTerminal(
        key: AuthOperationKey,
        kind: AuthOperationKind<Unit>,
        binder: IBinder,
        terminalName: String,
        data: Bundle,
    ) {
        val terminal = when (terminalName) {
            "COMPLETED" -> {
                if (data.keySet() != AUTH_TERMINAL_BASE_KEYS) return
                AuthOperationTerminal.Completed(Unit)
            }
            else -> parseNonCompletedTerminal<Unit>(terminalName, data) ?: return
        }
        authOperations.complete(key, kind, binder, terminal) {
            if (terminal is AuthOperationTerminal.Completed) {
                authState.completeSessionOperation(key.authGeneration)
            }
        }
    }

    private fun <T> parseNonCompletedTerminal(
        terminalName: String,
        data: Bundle,
    ): AuthOperationTerminal<T>? = when (terminalName) {
        "FAILED" -> {
            if (data.keySet() != AUTH_TERMINAL_BASE_KEYS + ReadReceiptsTunnelProtocol.KEY_ERROR) {
                null
            } else {
                data.strictString(ReadReceiptsTunnelProtocol.KEY_ERROR)
                    ?.takeIf { error ->
                        isBoundedAuthError(error) &&
                            ReadReceiptsTunnelErrorCode.entries.any { it.name == error }
                    }
                    ?.let { AuthOperationTerminal.Failed(it) }
            }
        }
        "SUPERSEDED" -> AuthOperationTerminal.Superseded
            .takeIf { data.keySet() == AUTH_TERMINAL_BASE_KEYS }
        "TIMED_OUT" -> AuthOperationTerminal.TimedOut
            .takeIf { data.keySet() == AUTH_TERMINAL_BASE_KEYS }
        "CANCELLED" -> AuthOperationTerminal.Cancelled
            .takeIf { data.keySet() == AUTH_TERMINAL_BASE_KEYS }
        else -> null
    }

    private fun handleAuthSnapshot(data: Bundle) {
        if (data.keySet() != AUTH_SNAPSHOT_KEYS) return
        val binder = service?.binder ?: return
        val revision = data.strictLong(ReadReceiptsTunnelProtocol.KEY_AUTH_SNAPSHOT_REVISION)
            ?.takeIf { it > 0 } ?: return
        val generation = data.strictLong(ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION)
            ?.takeIf { it >= 0 } ?: return
        val restartRequired = data.strictBoolean(
            ReadReceiptsTunnelProtocol.KEY_AUTH_RESTART_REQUIRED,
        ) ?: return
        val state = data.strictString(ReadReceiptsTunnelProtocol.KEY_STATE)
            ?.let { name -> ReadReceiptsTunnelState.entries.firstOrNull { it.name == name } }
            ?.takeIf {
                it == ReadReceiptsTunnelState.STOPPED ||
                    it == ReadReceiptsTunnelState.STARTING ||
                    it == ReadReceiptsTunnelState.CONNECTED ||
                    it == ReadReceiptsTunnelState.FAILED
            } ?: return
        val authorizationUrl = data.strictNullableString(
            ReadReceiptsTunnelProtocol.KEY_AUTHORIZATION_URL,
        ) ?: return
        if (
            authorizationUrl.value != null &&
            !ReadReceiptsTunnelNativeParser.isPinnedAuthorizationUrl(authorizationUrl.value)
        ) {
            return
        }
        val error = data.strictNullableString(ReadReceiptsTunnelProtocol.KEY_ERROR) ?: return
        if (
            error.value != null && (
                !isBoundedAuthError(error.value) ||
                    ReadReceiptsTunnelErrorCode.entries.none { it.name == error.value }
                )
        ) {
            return
        }
        val accountId = data.strictString(ReadReceiptsTunnelProtocol.KEY_ACCOUNT_ID) ?: return
        if (accountId.isNotEmpty() && !accountId.matches(AUTH_ACCOUNT_ID_PATTERN)) return
        val tunnels = parseTunnels(data) ?: return
        val metadataLoading = data.strictBoolean(
            ReadReceiptsTunnelProtocol.KEY_METADATA_LOADING,
        ) ?: return
        val metadata = parseCommittedMetadata(data) ?: return
        val snapshot = runCatching {
            ControllerAuthSnapshot(
                revision = revision,
                authGeneration = generation,
                restartRequired = restartRequired,
                loginState = CloudflareLoginState(authorizationUrl.value, state, error.value),
                accountId = accountId,
                tunnels = tunnels,
                metadataLoading = metadataLoading,
                committedMetadata = metadata.value,
            )
        }.getOrNull() ?: return
        if (!authState.accept(binder, snapshot) {
                snapshot.authGeneration == 0L ||
                    authOperations.adoptGeneration(snapshot.authGeneration)
            }
        ) {
            return
        }
        awaitingAuthSnapshot = false
        authGeneration.updateAndGet { current -> maxOf(current, snapshot.authGeneration) }
        maybeUnbindStoppedService()
    }

    private fun handleSelectForegroundReady(data: Bundle) {
        if (data.keySet() != SELECT_FOREGROUND_READY_KEYS) return
        val connectorGeneration = data.strictLong(
            ReadReceiptsTunnelProtocol.KEY_CONNECTOR_GENERATION,
        )?.takeIf { it > 0 } ?: return
        val pending = pendingSelectReadiness.remove(connectorGeneration) ?: return
        mainHandler.removeCallbacks(pending.timeout)
        if (pending.continuation.isActive) pending.continuation.resume(Unit)
    }

    private fun parseAuthIdentity(data: Bundle): ParsedAuthIdentity? {
        val nonce = data.strictString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE) ?: return null
        if (nonce != clientNonce) return null
        val generation = data.strictLong(ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION)
            ?.takeIf { it > 0 } ?: return null
        val requestId = data.strictLong(ReadReceiptsTunnelProtocol.KEY_AUTH_REQUEST_ID)
            ?.takeIf { it > 0 } ?: return null
        val kind = when (data.strictString(ReadReceiptsTunnelProtocol.KEY_AUTH_KIND)) {
            "BEGIN" -> AuthOperationKind.BEGIN
            "LIST" -> AuthOperationKind.LIST
            "SELECT" -> AuthOperationKind.SELECT
            "CANCEL" -> AuthOperationKind.CANCEL
            "LOGOUT" -> AuthOperationKind.LOGOUT
            else -> return null
        }
        return ParsedAuthIdentity(AuthOperationKey(generation, requestId), kind)
    }

    private fun parseTunnels(data: Bundle): List<ExistingTunnel>? {
        val values = data.get(ReadReceiptsTunnelProtocol.KEY_TUNNELS) as? ArrayList<*> ?: return null
        val tunnels = values.map { value ->
            val tunnel = value as? Bundle ?: return null
            if (tunnel.keySet() != TUNNEL_KEYS) return null
            val id = tunnel.strictString(ReadReceiptsTunnelProtocol.KEY_TUNNEL_ID) ?: return null
            val name = tunnel.strictString(ReadReceiptsTunnelProtocol.KEY_TUNNEL_NAME) ?: return null
            val hostnames = (tunnel.get(ReadReceiptsTunnelProtocol.KEY_HOSTNAMES) as? ArrayList<*>)
                ?.map { it as? String ?: return null } ?: return null
            ExistingTunnel.create(id, name, hostnames)?.also {
                if (it.id != id || it.name != name || it.hostnames != hostnames) return null
            } ?: return null
        }
        if (!AuthSnapshotBounds.isValid(stoppedBrowserLoginState(), "", tunnels, null)) return null
        return Collections.unmodifiableList(ArrayList(tunnels))
    }

    private fun parseCommittedMetadata(data: Bundle): NullableValue<CommittedTunnelCredentialMetadata>?
    {
        val sourceValue = data.strictNullableString(
            ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_SOURCE,
        ) ?: return null
        val accountId = data.strictNullableString(
            ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_ACCOUNT_ID,
        ) ?: return null
        val tunnelId = data.strictNullableString(
            ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_TUNNEL_ID,
        ) ?: return null
        val tunnelName = data.strictNullableString(
            ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_TUNNEL_NAME,
        ) ?: return null
        val hostname = data.strictNullableString(
            ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_HOSTNAME,
        ) ?: return null
        val port = data.strictInt(ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_ORIGIN_PORT) ?: return null
        if (sourceValue.value == null) {
            if (
                accountId.value != null || tunnelId.value != null || tunnelName.value != null ||
                hostname.value != null || port != 0
            ) {
                return null
            }
            return NullableValue(null)
        }
        val source = runCatching { TunnelCredentialSource.valueOf(sourceValue.value) }.getOrNull()
            ?: return null
        val metadata = CommittedTunnelCredentialMetadata(
            source = source,
            accountId = accountId.value ?: return null,
            tunnelId = tunnelId.value ?: return null,
            tunnelName = tunnelName.value ?: return null,
            canonicalHostname = hostname.value ?: return null,
            fixedOriginPort = port,
        )
        val structurallyValid = when (source) {
            TunnelCredentialSource.BROWSER_LOGIN ->
                ControllerAuthSnapshot(
                    revision = 1,
                    authGeneration = 0,
                    restartRequired = false,
                    loginState = stoppedBrowserLoginState(),
                    accountId = "",
                    tunnels = emptyList(),
                    metadataLoading = false,
                    committedMetadata = metadata,
                ).browserMetadataRebindDecision() is BrowserMetadataRebindDecision.Replace
            TunnelCredentialSource.TOKEN ->
                metadata.accountId.isEmpty() && metadata.tunnelId.isEmpty() &&
                    metadata.tunnelName.isEmpty() &&
                    (
                        metadata.canonicalHostname.isEmpty() && metadata.fixedOriginPort == 0 ||
                            ReadReceiptsTunnelService.canonicalPublicRoot(
                                metadata.canonicalHostname,
                            ) == metadata.canonicalHostname && metadata.fixedOriginPort in 1..65535
                    )
        }
        return NullableValue(metadata).takeIf { structurallyValid }
    }

    private fun Bundle.strictString(key: String): String? = get(key) as? String

    private fun Bundle.strictNullableString(key: String): NullableValue<String>? {
        if (!containsKey(key)) return null
        val value = get(key)
        if (value != null && value !is String) return null
        return NullableValue(value as String?)
    }

    private fun Bundle.strictLong(key: String): Long? = get(key) as? Long

    private fun Bundle.strictInt(key: String): Int? = get(key) as? Int

    private fun Bundle.strictBoolean(key: String): Boolean? = get(key) as? Boolean

    private fun isBoundedAuthError(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_AUTH_ERROR_CHARS &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_AUTH_ERROR_BYTES &&
            value.none(Char::isISOControl)

    private fun handleStartAck(data: Bundle) {
        val acknowledgedGeneration = data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION)
        val pending = pendingStart
        if (pending?.generation != acknowledgedGeneration) return
        val accepted = data.getBoolean(ReadReceiptsTunnelProtocol.KEY_ACCEPTED)
        if (data.getBoolean(ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS)) {
            status = ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                errorCode = ReadReceiptsTunnelErrorCode.NOTIFICATIONS_DISABLED,
                needsNotificationSettings = true,
            )
        }
        val completed = if (accepted) {
            handoffGate.complete(acknowledgedGeneration)
        } else {
            handoffGate.fail(acknowledgedGeneration)
        }
        if (!completed) return
        pending.command.data.remove(ReadReceiptsTunnelProtocol.KEY_TOKEN)
        pendingStart = null
        pending.completion.complete(
            if (accepted) {
                Result.success(Unit)
            } else {
                Result.failure(tunnelException(
                    status.errorCode ?: ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "tunnel service rejected start request",
                ))
            },
        )
    }

    private fun failPendingStart(error: Throwable) {
        val pending = pendingStart ?: return
        failPendingStart(pending.generation, error)
    }

    private fun failPendingStart(expectedGeneration: Long, error: Throwable) {
        val pending = pendingStart ?: return
        if (pending.generation != expectedGeneration || !handoffGate.fail(expectedGeneration)) return
        pending.command.data.remove(ReadReceiptsTunnelProtocol.KEY_TOKEN)
        pendingStart = null
        pending.completion.complete(Result.failure(error))
    }

    private fun supersedePendingStart() {
        val pending = pendingStart ?: return
        supersedePendingStart(pending.generation)
    }

    private fun supersedePendingStart(expectedGeneration: Long) {
        val pending = pendingStart ?: return
        if (pending.generation != expectedGeneration || !handoffGate.fail(expectedGeneration)) return
        pending.command.data.remove(ReadReceiptsTunnelProtocol.KEY_TOKEN)
        pendingStart = null
        pending.completion.supersede()
    }

    private fun completeStop(
        expectedGeneration: Long,
        result: Result<Unit>,
    ) {
        val drain = stopCompletion.complete(expectedGeneration)
        if (!drain.matched) return
        drain.callbacks.forEach { callback -> callback(result) }
    }

    private fun nextGeneration(): Long = generation.updateAndGet { current ->
        maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
    }.also(lastIssuedGeneration::set)

    private fun reserveConnectorGeneration(): Long = generation.updateAndGet { current ->
        maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
    }

    private fun maybeUnbindStoppedService() {
        val snapshot = authState.currentSnapshot()
        if (
            status.state == ReadReceiptsTunnelState.STOPPED &&
            !awaitingAuthSnapshot && pendingAuthMessages.isEmpty() &&
            snapshot != null && snapshot.authGeneration == 0L && !snapshot.metadataLoading
        ) {
            unbind()
        }
    }

    private fun unbind() {
        if (!bound) return
        runCatching { HostInfo.application.unbindService(connection) }
        bound = false
        service = null
    }

    private fun serviceIntent(context: Context): Intent = Intent().setComponent(
        ComponentName(MODULE_PACKAGE, SERVICE_CLASS),
    )

    private const val MODULE_PACKAGE = "dev.ujhhgtg.wekit"
    private const val SERVICE_CLASS =
        "dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsTunnelService"
    private const val REBIND_DELAY_MILLIS = 1_000L
    private const val START_HANDOFF_TIMEOUT_MILLIS = 10_000L
    private const val STOP_COMPLETION_TIMEOUT_MILLIS = 20_000L
    private const val AUTH_OPERATION_TIMEOUT_MILLIS = 35_000L
    private const val SELECT_FOREGROUND_TIMEOUT_MILLIS = 10_000L
    private const val MAX_AUTH_ERROR_CHARS = 256
    private const val MAX_AUTH_ERROR_BYTES = 512
    private val AUTH_ACCOUNT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val AUTH_TERMINAL_BASE_KEYS =
        ReadReceiptsTunnelProtocol.AUTH_OPERATION_KEYS +
            ReadReceiptsTunnelProtocol.KEY_AUTH_TERMINAL
    private val BEGIN_TERMINAL_RESULT_KEYS = setOf(
        ReadReceiptsTunnelProtocol.KEY_STATE,
        ReadReceiptsTunnelProtocol.KEY_AUTHORIZATION_URL,
    )
    private val TUNNEL_KEYS = setOf(
        ReadReceiptsTunnelProtocol.KEY_TUNNEL_ID,
        ReadReceiptsTunnelProtocol.KEY_TUNNEL_NAME,
        ReadReceiptsTunnelProtocol.KEY_HOSTNAMES,
    )
    private val AUTH_SNAPSHOT_KEYS = setOf(
        ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE,
        ReadReceiptsTunnelProtocol.KEY_AUTH_SNAPSHOT_REVISION,
        ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION,
        ReadReceiptsTunnelProtocol.KEY_AUTH_RESTART_REQUIRED,
        ReadReceiptsTunnelProtocol.KEY_STATE,
        ReadReceiptsTunnelProtocol.KEY_AUTHORIZATION_URL,
        ReadReceiptsTunnelProtocol.KEY_ERROR,
        ReadReceiptsTunnelProtocol.KEY_ACCOUNT_ID,
        ReadReceiptsTunnelProtocol.KEY_TUNNELS,
        ReadReceiptsTunnelProtocol.KEY_METADATA_LOADING,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_SOURCE,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_ACCOUNT_ID,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_TUNNEL_ID,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_TUNNEL_NAME,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_HOSTNAME,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_ORIGIN_PORT,
    )
    private val SELECT_FOREGROUND_READY_KEYS = setOf(
        ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE,
        ReadReceiptsTunnelProtocol.KEY_CONNECTOR_GENERATION,
    )

    private fun stoppedBrowserLoginState(): CloudflareLoginState = CloudflareLoginState(
        authorizationUrl = null,
        state = ReadReceiptsTunnelState.STOPPED,
        error = null,
    )

    private data class PendingAuthMessage<T>(
        val key: AuthOperationKey,
        val kind: AuthOperationKind<T>,
        val command: Message,
        val timeout: Runnable,
    )

    private data class PendingSelectReadiness(
        val continuation: CancellableContinuation<Unit>,
        val timeout: Runnable,
    )

    private data class ParsedAuthIdentity(
        val key: AuthOperationKey,
        val kind: AuthOperationKind<*>,
    )

    private data class NullableValue<T>(val value: T?)

    private data class PendingStart(
        val generation: Long,
        val command: Message,
        val completion: TunnelHandoffTerminalDelivery,
        var sent: Boolean = false,
    )
}

internal class BrowserLoginException(
    val errorCode: ReadReceiptsTunnelErrorCode,
    diagnostic: String,
    cause: Throwable? = null,
) : IllegalStateException(diagnostic, cause)
