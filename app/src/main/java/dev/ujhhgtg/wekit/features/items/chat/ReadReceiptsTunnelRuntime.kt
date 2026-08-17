package dev.ujhhgtg.wekit.features.items.chat

import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * WeChat-process owner of the embedded Cloudflare connector and retained run credential.
 *
 * Ported from `ReadReceiptsTunnelService` (module-process Android Service) with the standard
 * mechanical rewrites: no foreground service/notification machinery, authoritative status kept
 * in [authoritativeState] with [publish]'s generation guard, browser-auth admission serialized by
 * [authMutex] instead of the `ServiceAuthCoordinator` wire-state machine, and no Binder/Messenger
 * transport — status listeners are invoked in-process.
 */
internal object ReadReceiptsTunnelRuntime {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nativeLease = TunnelNativeLease()
    private val handoffGate = TunnelHandoffGate()
    private val stopCompletion = TunnelStopCompletion()
    private val credentialStore by lazy {
        ReadReceiptsTunnelCredentialStore(File(HostInfo.application.filesDir, "wekit"))
    }
    private val connectivityManager by lazy {
        HostInfo.application.getSystemService(ConnectivityManager::class.java)
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** Per-process random connector authenticator, embedded as the loopback-origin userinfo. */
    private val clientNonce = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private val authoritativeState = AtomicReference(
        AuthoritativeTunnelState(
            generation = 0L,
            status = ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPED),
        ),
    )

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val generationCounter = AtomicLong(SystemClock.elapsedRealtimeNanos())

    /** Connector-generation source; every published generation was allocated from this counter. */
    val generation: Long
        get() = generationCounter.get()

    @Volatile
    private var lifecycleJob: Job? = null

    @Volatile
    private var activeRequest: TunnelRequest? = null

    @Volatile
    private var networkAvailable = true

    @Volatile
    private var networkCallbackRegistered = false

    private val networkLock = Any()
    private var currentDefaultNetwork: Network? = null

    /** Replaces the service's ServiceAuthCoordinator admission machinery: one auth op at a time. */
    private val authMutex = Mutex()

    @Volatile
    private var authSnapshot: ControllerAuthSnapshot? = null

    @Volatile
    private var authRestartRequired = false

    @Volatile
    private var nativeAuthGeneration = 0L

    private val authSessionGenerationCounter = AtomicLong(SystemClock.elapsedRealtimeNanos())

    /** Session generation published while a native browser-login session is live. */
    @Volatile
    private var authSessionGeneration = 0L

    @Volatile
    private var authLoginState: CloudflareLoginState? = null

    @Volatile
    private var authAccountId = ""

    @Volatile
    private var authTunnels: List<ExistingTunnel> = emptyList()

    @Volatile
    private var cachedCredentialExists = false

    @Volatile
    private var cachedCredentialMetadata: CommittedTunnelCredentialMetadata? = null

    @Volatile
    private var credentialMetadataLoadingInternal = true

    private var appliedCredentialRevision = 0L
    private var credentialFileRevision = 0L
    private val credentialFileLock = Any()
    private var authSnapshotRevision = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)

    private val authWatchdogLock = Any()
    private var authWatchdogOwner: Any? = null

    @Volatile
    private var pendingHandoff: PendingHandoff? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(networkLock) {
                currentDefaultNetwork = network
                networkAvailable = true
            }
            invalidateForNetworkChange(nativeLease.invalidateNetwork())
        }

        override fun onLost(network: Network) {
            synchronized(networkLock) {
                if (currentDefaultNetwork == network) {
                    currentDefaultNetwork = connectivityManager.activeNetwork
                }
                networkAvailable = connectivityManager.activeNetwork != null
            }
            // A default-network replacement may already have a non-null activeNetwork here. The
            // old route is still invalid and must lose its verified URL/native connection.
            invalidateForNetworkChange(nativeLease.invalidateNetwork())
        }
    }

    init {
        scope.launch {
            val update = loadCredentialMetadataOnIo()
            applyCredentialCacheUpdate(update)
        }
    }

    // ------------------------------------------------------------------ status facade

    fun readStatus(): ReadReceiptsTunnelStatus = authoritativeState.get().status

    fun credentialExists(): Boolean = cachedCredentialExists

    fun addStatusListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeStatusListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Same semantics as the old controller cache write: unless the authoritative status is
     * CONNECTED, surface NEEDS_USER_ACTION + VISIBLE_SETTINGS_REQUIRED to the user.
     */
    fun needsVisibleStart() {
        if (readStatus().state == ReadReceiptsTunnelState.CONNECTED) return
        publish(
            authoritativeState.get().generation,
            ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                errorCode = ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED,
            ),
        )
    }

    fun originAuthenticator(): String = clientNonce

    // ------------------------------------------------------------------ lifecycle

    /**
     * Start-admission skeleton ported from the controller's `startVisible` plus the service's
     * `handleStart` parsing (origin URL `http://127.0.0.1:$originPort/`, connector authenticator
     * as the Binder nonce). The handoff completes when `runTunnel`'s health-check precheck
     * passes; rejection paths fail it with the status error code the service published.
     */
    fun start(
        mode: ReadReceiptsTunnelMode,
        originPort: Int,
        hostname: String,
        token: String?,
        connectorAuthenticator: String,
        onHandoff: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        ensureNetworkCallbackRegistered()
        val handoffDelivery = TunnelHandoffTerminalDelivery(onHandoff)
        drainPendingHandoff()
        val requestedGeneration = when (
            val admission = stopCompletion.startAdmission(::nextGeneration)
        ) {
            is TunnelStartAdmission.Admitted -> admission.generation
            is TunnelStartAdmission.Rejected -> {
                handoffDelivery.complete(Result.failure(admission.failure))
                return
            }
        }
        handoffGate.begin(requestedGeneration)
        authoritativeState.set(
            AuthoritativeTunnelState(
                requestedGeneration,
                ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING),
            ),
        )
        pendingHandoff = PendingHandoff(
            requestedGeneration,
            handoffDelivery,
            scope.launch {
                delay(START_HANDOFF_TIMEOUT_MILLIS)
                failStartHandoff(
                    requestedGeneration,
                    tunnelException(
                        ReadReceiptsTunnelErrorCode.START_HANDOFF_TIMEOUT,
                        "tunnel handoff timed out",
                    ),
                )
            },
        )
        handleStart(
            requestedGeneration,
            mode,
            "http://127.0.0.1:$originPort/",
            hostname,
            token,
            connectorAuthenticator,
        )
    }

    /** Mirrors the controller's stop semantics, executed directly in-process. */
    fun stop(onStopped: ((Result<Unit>) -> Unit)? = null) {
        drainPendingHandoff()
        val registration = stopCompletion.register(
            callback = onStopped,
            latestIssuedGeneration = generationCounter.get(),
            generationFactory = ::nextGeneration,
        )
        if (!registration.shouldSend) return
        val nextGeneration = registration.generation
        stopTunnel(nextGeneration)
        scope.launch {
            delay(STOP_COMPLETION_TIMEOUT_MILLIS)
            if (readStatus().state != ReadReceiptsTunnelState.STOPPED) {
                val drain = stopCompletion.completeTimeout(
                    generation = nextGeneration,
                    authoritativeGeneration = authoritativeState.get().generation,
                )
                if (!drain.matched) return@launch
                publish(
                    nextGeneration,
                    ReadReceiptsTunnelStatus(
                        ReadReceiptsTunnelState.FAILED,
                        errorCode = ReadReceiptsTunnelErrorCode.STOP_TIMEOUT,
                    ),
                )
                val failure = Result.failure<Unit>(
                    tunnelException(
                        ReadReceiptsTunnelErrorCode.STOP_TIMEOUT,
                        "tunnel stop timed out",
                    ),
                )
                drain.callbacks.forEach { callback -> callback(failure) }
            }
        }
    }

    fun deleteCredential() {
        stopCompletion.runAdministrativeCommandIfIdle(
            hasPendingStart = { pendingHandoff != null },
            command = { deleteCredentialAt(generationCounter.get()) },
        )
    }

    private fun handleStart(
        requestedGeneration: Long,
        mode: ReadReceiptsTunnelMode,
        origin: String,
        hostname: String,
        suppliedToken: String?,
        nonce: String,
    ) {
        if (!isConnectorAuthenticator(nonce)) {
            rejectStart(
                requestedGeneration,
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
            )
            return
        }
        if (mode == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
            handleBrowserStart(
                requestedGeneration,
                nonce,
                origin,
                hostname,
                suppliedToken,
            )
            return
        }
        if (
            !nativeLease.advance(requestedGeneration) {
                activeRequest = null
                authoritativeState.set(
                    AuthoritativeTunnelState(
                        requestedGeneration,
                        ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING),
                    ),
                )
            }
        ) {
            failStartHandoff(
                requestedGeneration,
                tunnelException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "tunnel start was superseded",
                ),
            )
            return
        }
        val publicRoot = if (mode == ReadReceiptsTunnelMode.TOKEN) {
            ReadReceiptsTunnelHostnames.normalizePublicRoot(hostname) ?: run {
                rejectStart(
                    requestedGeneration,
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                )
                return
            }
        } else {
            null
        }
        if (
            suppliedToken != null &&
            (suppliedToken.length > MAX_TOKEN_CHARS || suppliedToken.isBlank())
        ) {
            rejectStart(
                requestedGeneration,
                ReadReceiptsTunnelErrorCode.TOKEN_INVALID,
            )
            return
        }
        if (
            mode == ReadReceiptsTunnelMode.TOKEN &&
            suppliedToken == null &&
            !cachedCredentialExists
        ) {
            publish(
                requestedGeneration,
                ReadReceiptsTunnelStatus(
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    errorCode = ReadReceiptsTunnelErrorCode.TOKEN_REQUIRED,
                ),
            )
            activeRequest = null
            replaceLifecycle(requestedGeneration, null)
            failStartHandoff(
                requestedGeneration,
                tunnelException(
                    ReadReceiptsTunnelErrorCode.TOKEN_REQUIRED,
                    "tunnel start was rejected",
                ),
            )
            return
        }

        val request = TunnelRequest(
            requestedGeneration,
            mode,
            origin,
            publicRoot,
            nonce,
            suppliedToken,
        )
        activeRequest = request
        check(nativeLease.activateRequest(requestedGeneration))
        replaceLifecycle(requestedGeneration, request)
        // The connector/public-health outcome remains asynchronous authoritative status; the
        // handoff completes inside runTunnel once the health precheck passes.
    }

    private fun handleBrowserStart(
        requestedGeneration: Long,
        nonce: String,
        origin: String,
        hostname: String,
        suppliedToken: String?,
    ) {
        val originRoot = ReadReceiptsTunnelHostnames.normalizeLoopbackRoot(origin)
        val publicRoot = ReadReceiptsTunnelHostnames.normalizePublicRoot(hostname)
        if (
            suppliedToken != null || originRoot == null || publicRoot == null ||
            ReadReceiptsTunnelHostnames.canonicalPublicRoot(hostname) != hostname
        ) {
            publishBrowserNeedsUserAction(
                requestedGeneration,
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
            )
            failStartHandoff(
                requestedGeneration,
                tunnelException(
                    ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                    "tunnel start was rejected",
                ),
            )
            return
        }
        scope.launch {
            val payload = readCredentialOnIo().getOrNull()
            val decision = payload?.let {
                decideCredentialStartup(
                    it,
                    ReadReceiptsTunnelMode.BROWSER_LOGIN,
                    hostname,
                    originRoot.port,
                )
            } ?: TunnelCredentialStartupDecision.NEEDS_USER_ACTION
            if (decision != TunnelCredentialStartupDecision.START) {
                publishBrowserNeedsUserAction(
                    requestedGeneration,
                    ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                )
                failStartHandoff(
                    requestedGeneration,
                    tunnelException(
                        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                        "tunnel start was rejected",
                    ),
                )
                return@launch
            }
            checkNotNull(payload)
            if (
                !nativeLease.advance(requestedGeneration) {
                    activeRequest = null
                    authoritativeState.set(
                        AuthoritativeTunnelState(
                            requestedGeneration,
                            ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING),
                        ),
                    )
                }
            ) {
                failStartHandoff(
                    requestedGeneration,
                    tunnelException(
                        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                        "tunnel start was superseded",
                    ),
                )
                return@launch
            }
            val request = TunnelRequest(
                requestedGeneration,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                origin,
                publicRoot,
                nonce,
                pendingToken = null,
                browserCredential = payload,
            )
            activeRequest = request
            check(nativeLease.activateRequest(requestedGeneration))
            replaceLifecycle(requestedGeneration, request)
        }
    }

    private fun publishBrowserNeedsUserAction(
        generation: Long,
        errorCode: ReadReceiptsTunnelErrorCode,
    ) {
        // A rejected request never entered the native lease. Keep an existing connector's
        // generation authoritative so its monitor can continue publishing current status.
        if (activeRequest != null) return
        val current = authoritativeState.get()
        if (generation < current.generation) return
        authoritativeState.set(
            AuthoritativeTunnelState(
                generation,
                ReadReceiptsTunnelStatus(
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    errorCode = errorCode,
                ),
            ),
        )
        listeners.forEach { it() }
    }

    private fun rejectStart(
        requestedGeneration: Long,
        errorCode: ReadReceiptsTunnelErrorCode,
    ) {
        activeRequest = null
        replaceLifecycle(requestedGeneration, null)
        publishFailure(requestedGeneration, errorCode)
        failStartHandoff(
            requestedGeneration,
            tunnelException(errorCode, "tunnel start was rejected"),
        )
    }

    /** Captures the one real predecessor exactly once; this job is the sole lifecycle successor. */
    private fun replaceLifecycle(generation: Long, request: TunnelRequest?) {
        val previous = lifecycleJob
        lifecycleJob = scope.launch {
            try {
                previous?.cancel()
                previous?.join()
                nativeLease.stopForReplacement(generation) {
                    ReadReceiptsTunnelNative.stop().getOrThrow()
                }
                if (request != null && activeRequest?.generation == generation) runTunnel(request)
            } finally {
                if (request?.browserCredential != null) {
                    try {
                        nativeLease.stopIfOwner(request.generation) {
                            ReadReceiptsTunnelNative.stop().getOrThrow()
                        }
                    } finally {
                        withContext(NonCancellable) {
                            request.browserCredential = null
                            if (activeRequest === request) {
                                activeRequest = null
                                nativeLease.clearRequest(request.generation)
                                val current = authoritativeState.get()
                                if (
                                    current.generation == request.generation &&
                                    current.status.state != ReadReceiptsTunnelState.FAILED &&
                                    current.status.state != ReadReceiptsTunnelState.NEEDS_USER_ACTION
                                ) {
                                    publishFailure(
                                        request.generation,
                                        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun replaceLifecycleForSelect(
        request: TunnelRequest,
        reservation: TunnelCandidateReservation,
        firstVerification: CompletableDeferred<SelectCandidateOutcome>,
    ): Job {
        val previous = lifecycleJob
        lateinit var candidate: Job
        candidate = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.cancel()
                previous?.join()
                if (
                    !nativeLease.stopForReplacement(request.generation) {
                        ReadReceiptsTunnelNative.stop().getOrThrow()
                    }
                ) {
                    return@launch
                }
                runTunnel(request, reservation, firstVerification)
            } finally {
                try {
                    if (request.browserCredentialNeedsCommit) {
                        try {
                            nativeLease.stopIfOwner(request.generation) {
                                ReadReceiptsTunnelNative.stop().getOrThrow()
                            }
                        } finally {
                            withContext(NonCancellable) {
                                request.browserCredential = null
                                request.browserCredentialNeedsCommit = false
                                if (
                                    activeRequest === request &&
                                    activeRequest?.generation == request.generation
                                ) {
                                    activeRequest = null
                                    nativeLease.clearRequest(request.generation)
                                    val current = authoritativeState.get()
                                    if (
                                        current.generation == request.generation &&
                                        current.status.state != ReadReceiptsTunnelState.FAILED &&
                                        current.status.state !=
                                        ReadReceiptsTunnelState.NEEDS_USER_ACTION
                                    ) {
                                        publishFailure(
                                            request.generation,
                                            ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    if (!firstVerification.isCompleted) {
                        firstVerification.complete(SelectCandidateOutcome.STALE)
                    }
                }
            }
        }
        lifecycleJob = candidate
        candidate.start()
        return candidate
    }

    private suspend fun runTunnel(
        request: TunnelRequest,
        initialReservation: TunnelCandidateReservation? = null,
        firstVerification: CompletableDeferred<SelectCandidateOutcome>? = null,
    ) {
        publish(request.generation, ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING))
        val originRoot = ReadReceiptsTunnelHostnames.normalizeLoopbackRoot(request.origin)
        if (originRoot == null || !checkHealth(originRoot)) {
            publishFailure(
                request.generation,
                ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED,
            )
            if (firstVerification == null) {
                failStartHandoff(
                    request.generation,
                    tunnelException(
                        ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED,
                        "tunnel start was rejected",
                    ),
                )
            } else {
                firstVerification.complete(
                    SelectCandidateOutcome.Failed(
                        SelectCandidateFailure(SelectFailureSource.HealthVerification(false)),
                    ),
                )
            }
            return
        }
        // The health-check precheck decided START acceptance; complete the handoff now.
        if (firstVerification == null) completeStartHandoff(request.generation)

        var reservation = initialReservation
        if (reservation != null && !nativeLease.activateReservedRequest(reservation)) return

        var attempt = 0
        while (scope.isActive && activeRequest?.generation == request.generation) {
            if (reservation != null && !nativeLease.isReservationCurrent(reservation)) return
            while (!networkAvailable && activeRequest?.generation == request.generation) {
                if (reservation != null && !nativeLease.isReservationCurrent(reservation)) return
                publish(
                    request.generation,
                    ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.RECONNECTING),
                )
                delay(NETWORK_POLL_MILLIS)
            }
            currentCoroutineContext().ensureActive()

            val token = when (request.mode) {
                ReadReceiptsTunnelMode.QUICK -> null
                ReadReceiptsTunnelMode.TOKEN -> request.pendingToken ?: readCredentialOnIo()
                    .getOrNull()
                    ?.takeIf { it.source == TunnelCredentialSource.TOKEN }
                    ?.runToken ?: run {
                    publish(
                        request.generation,
                        ReadReceiptsTunnelStatus(
                            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                            errorCode = ReadReceiptsTunnelErrorCode.TOKEN_INVALID,
                        ),
                    )
                    firstVerification?.complete(
                        SelectCandidateOutcome.Failed(
                            SelectCandidateFailure(
                                SelectFailureSource.ConnectorTerminal(
                                    ReadReceiptsTunnelErrorCode.TOKEN_INVALID,
                                ),
                            ),
                        ),
                    )
                    return
                }
                ReadReceiptsTunnelMode.BROWSER_LOGIN ->
                    request.browserCredential?.runToken ?: readCredentialOnIo()
                        .getOrNull()
                        ?.takeIf {
                            decideCredentialStartup(
                                it,
                                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                                request.publicRoot.toString().trimEnd('/'),
                                originRoot.port,
                            ) == TunnelCredentialStartupDecision.START
                        }
                        ?.runToken ?: run {
                        publish(
                            request.generation,
                            ReadReceiptsTunnelStatus(
                                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                                errorCode =
                                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                            ),
                        )
                        firstVerification?.complete(
                            SelectCandidateOutcome.Failed(
                                SelectCandidateFailure(
                                    SelectFailureSource.ConnectorTerminal(
                                        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                                    ),
                                ),
                            ),
                        )
                        return
                    }
            }
            val start = {
                val startResult = when (request.mode) {
                    ReadReceiptsTunnelMode.QUICK ->
                        ReadReceiptsTunnelNative.startQuick(
                            request.origin,
                            request.connectorAuthenticator,
                        )
                    ReadReceiptsTunnelMode.TOKEN ->
                        ReadReceiptsTunnelNative.startToken(
                            token!!,
                            request.origin,
                            request.connectorAuthenticator,
                        )
                    ReadReceiptsTunnelMode.BROWSER_LOGIN ->
                        ReadReceiptsTunnelNative.startToken(
                            token!!,
                            request.origin,
                            request.connectorAuthenticator,
                        )
                }
                startResult.isSuccess
            }
            val started = reservation?.let {
                nativeLease.startReservedIfCurrent(it, start)
            } ?: nativeLease.startIfCurrent(request.generation, start)
            if (!started) {
                if (activeRequest?.generation != request.generation) return
                if (reservation != null && !nativeLease.isReservationCurrent(reservation)) return
                val errorCode = when (request.mode) {
                    ReadReceiptsTunnelMode.QUICK ->
                        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE
                    ReadReceiptsTunnelMode.TOKEN -> ReadReceiptsTunnelErrorCode.TOKEN_INVALID
                    ReadReceiptsTunnelMode.BROWSER_LOGIN ->
                        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID
                }
                publishFailure(
                    request.generation,
                    errorCode,
                )
                firstVerification?.complete(
                    SelectCandidateOutcome.Failed(
                        SelectCandidateFailure(
                            SelectFailureSource.ConnectorTerminal(errorCode),
                        ),
                    ),
                )
                return
            }

            var terminalErrorCode: ReadReceiptsTunnelErrorCode? = null
            var verifiedRoot: HttpUrl? = null
            var verifiedNetworkEpoch: Long? = null
            var lastPublicHealthAt = 0L
            var publicHealthAttempts = 0
            var publicHealthTerminal = false
            while (
                activeRequest?.generation == request.generation &&
                currentCoroutineContext().isActive
            ) {
                val native = ReadReceiptsTunnelNative.status()
                when (native.state) {
                    ReadReceiptsTunnelState.CONNECTED -> {
                        val verification = reservation?.let {
                            nativeLease.captureReservedVerification(it)
                        } ?: nativeLease.captureVerification(request.generation)
                        if (verification == null) {
                            if (reservation != null) return
                            delay(NATIVE_STATUS_POLL_MILLIS)
                            continue
                        }
                        val candidate = request.publicRoot
                            ?: ReadReceiptsTunnelHostnames.normalizePublicRoot(
                                native.publicUrl.orEmpty(),
                            )
                        if (candidate == null) {
                            terminalErrorCode = ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED
                            break
                        }
                        val needsHealthCheck = verifiedRoot != candidate ||
                            verifiedNetworkEpoch != verification.networkEpoch ||
                            SystemClock.elapsedRealtime() - lastPublicHealthAt >=
                            PUBLIC_HEALTH_RECHECK_MILLIS
                        if (!needsHealthCheck || checkHealth(candidate)) {
                            val pendingToken = request.pendingToken
                            val browserCredential = request.browserCredential
                            when (
                                val commitResult = nativeLease.commitVerification(
                                    verification,
                                    writeCredential = when {
                                        request.browserCredentialNeedsCommit -> ({
                                            val gate = checkNotNull(request.selectCommitGate)
                                            gate.tryCommit() &&
                                                writeCredentialOnIo(checkNotNull(browserCredential))
                                        })
                                        pendingToken != null -> ({
                                            val payload = TunnelCredentialPayload.create(
                                                runToken = pendingToken,
                                                source = TunnelCredentialSource.TOKEN,
                                                canonicalHostname =
                                                candidate.toString().trimEnd('/'),
                                                fixedOriginPort = originRoot.port,
                                            )
                                            payload != null && writeCredentialOnIo(payload)
                                        })
                                        else -> null
                                    },
                                    clearPendingToken = when {
                                        request.browserCredentialNeedsCommit -> ({
                                            request.browserCredential = null
                                            request.browserCredentialNeedsCommit = false
                                        })
                                        pendingToken != null -> ({ request.pendingToken = null })
                                        else -> null
                                    },
                                    publishConnected = {
                                        verifiedRoot = candidate
                                        verifiedNetworkEpoch = verification.networkEpoch
                                        if (needsHealthCheck) {
                                            lastPublicHealthAt = SystemClock.elapsedRealtime()
                                        }
                                        publicHealthAttempts = 0
                                        attempt = 0
                                        publish(
                                            request.generation,
                                            ReadReceiptsTunnelStatus(
                                                ReadReceiptsTunnelState.CONNECTED,
                                                publicUrl = candidate.toString().trimEnd('/'),
                                            ),
                                        )
                                    },
                                )
                            ) {
                                TunnelVerificationCommit.CREDENTIAL_FAILURE -> {
                                    if (request.selectCommitGate?.isCommitClaimed() == false) return
                                    nativeLease.runIfVerificationCurrent(verification) {
                                        publish(
                                            request.generation,
                                            ReadReceiptsTunnelStatus(
                                                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                                                errorCode =
                                                ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED,
                                            ),
                                        )
                                    }
                                    nativeLease.stopIfOwner(request.generation) {
                                        ReadReceiptsTunnelNative.stop().getOrThrow()
                                    }
                                    firstVerification?.complete(
                                        SelectCandidateOutcome.Failed(
                                            SelectCandidateFailure(
                                                SelectFailureSource.CredentialCommit(commitResult),
                                            ),
                                        ),
                                    )
                                    return
                                }
                                TunnelVerificationCommit.STALE -> {
                                    if (reservation != null) return
                                    delay(NATIVE_STATUS_POLL_MILLIS)
                                    continue
                                }
                                TunnelVerificationCommit.COMMITTED -> {
                                    if (!request.browserCredentialNeedsCommit) {
                                        request.browserCredential = null
                                    }
                                    if (reservation != null) {
                                        reservation = null
                                        firstVerification?.complete(
                                            SelectCandidateOutcome.COMMITTED,
                                        )
                                    }
                                }
                            }
                        } else {
                            if (
                                !nativeLease.runIfVerificationCurrent(verification) {
                                    verifiedRoot = null
                                    verifiedNetworkEpoch = null
                                    publicHealthAttempts++
                                    publish(
                                        request.generation,
                                        ReadReceiptsTunnelStatus(
                                            ReadReceiptsTunnelState.RECONNECTING,
                                        ),
                                    )
                                }
                            ) {
                                delay(NATIVE_STATUS_POLL_MILLIS)
                                continue
                            }
                            if (publicHealthAttempts >= MAX_PUBLIC_HEALTH_ATTEMPTS) {
                                terminalErrorCode =
                                    ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED
                                publicHealthTerminal = true
                                break
                            }
                        }
                    }
                    ReadReceiptsTunnelState.RECONNECTING,
                    ReadReceiptsTunnelState.STARTING,
                    -> {
                        verifiedRoot = null
                        verifiedNetworkEpoch = null
                        lastPublicHealthAt = 0L
                        publicHealthAttempts = 0
                        publish(
                            request.generation,
                            native.copy(publicUrl = null, errorCode = null),
                        )
                    }
                    ReadReceiptsTunnelState.FAILED -> {
                        terminalErrorCode = native.errorCode
                            ?: ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE
                        break
                    }
                    ReadReceiptsTunnelState.STOPPED -> {
                        terminalErrorCode = ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE
                        break
                    }
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION -> {
                        publish(request.generation, native.copy(publicUrl = null))
                        return
                    }
                    ReadReceiptsTunnelState.STOPPING -> Unit
                }
                delay(NATIVE_STATUS_POLL_MILLIS)
            }
            nativeLease.stopIfOwner(request.generation) {
                ReadReceiptsTunnelNative.stop().getOrThrow()
            }
            if (
                activeRequest?.generation != request.generation ||
                !currentCoroutineContext().isActive
            ) {
                return
            }
            if (publicHealthTerminal) {
                publishFailure(request.generation, checkNotNull(terminalErrorCode))
                firstVerification?.complete(
                    SelectCandidateOutcome.Failed(
                        SelectCandidateFailure(SelectFailureSource.HealthVerification(false)),
                    ),
                )
                return
            }

            attempt++
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                publishFailure(
                    request.generation,
                    terminalErrorCode ?: ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                )
                firstVerification?.complete(
                    SelectCandidateOutcome.Failed(
                        SelectCandidateFailure(
                            SelectFailureSource.ConnectorTerminal(
                                terminalErrorCode
                                    ?: ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                            ),
                        ),
                    ),
                )
                return
            }
            publish(
                request.generation,
                ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.RECONNECTING),
            )
            delay(RECONNECT_DELAYS_MILLIS[(attempt - 1).coerceAtMost(RECONNECT_DELAYS_MILLIS.lastIndex)])
        }
    }

    private fun stopTunnel(requestedGeneration: Long) {
        if (
            !nativeLease.advance(requestedGeneration) {
                activeRequest = null
                authoritativeState.set(
                    AuthoritativeTunnelState(
                        requestedGeneration,
                        ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPING),
                    ),
                )
            }
        ) {
            return
        }
        val stoppedGeneration = requestedGeneration
        val previous = lifecycleJob
        lifecycleJob = scope.launch {
            publish(stoppedGeneration, ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPING))
            previous?.cancel()
            previous?.join()
            nativeLease.stopForReplacement(stoppedGeneration) {
                ReadReceiptsTunnelNative.stop().getOrThrow()
            }
            publish(stoppedGeneration, ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPED))
        }
    }

    private fun deleteCredentialAt(requestedGeneration: Long) {
        scope.launch {
            var stoppedCredentialBackedRequest = false
            var capturedState: TunnelNativeSessionState? = null
            var update: CredentialCacheUpdate? = null
            val accepted = nativeLease.withCurrentGeneration(requestedGeneration) { sessionState ->
                val activeMode = activeRequest?.mode
                if (
                    activeMode in setOf(
                        ReadReceiptsTunnelMode.TOKEN,
                        ReadReceiptsTunnelMode.BROWSER_LOGIN,
                    )
                ) {
                    // Re-entering the lease transitions to STOPPING now; its lifecycle cannot
                    // acquire the lease and stop the connector until the payload clear finishes.
                    stopTunnel(requestedGeneration)
                    stoppedCredentialBackedRequest = true
                }
                update = clearCredentialOnIo()
                capturedState = sessionState
            }
            if (!accepted) return@launch
            applyCredentialCacheUpdate(update!!)
            if (!stoppedCredentialBackedRequest) {
                publish(
                    requestedGeneration,
                    readStatus().forAdministrativePublish(capturedState!!),
                )
            }
        }
    }

    private fun invalidateForNetworkChange(ticket: TunnelNetworkInvalidationTicket?) {
        if (ticket == null) return
        scope.launch {
            nativeLease.stopInvalidatedSession(
                ticket,
                stop = { ReadReceiptsTunnelNative.stop().getOrThrow() },
                publishReconnecting = { stoppedGeneration ->
                    publish(
                        stoppedGeneration,
                        ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.RECONNECTING),
                    )
                },
            )
        }
    }

    private fun ensureNetworkCallbackRegistered() {
        if (networkCallbackRegistered) return
        synchronized(networkLock) {
            if (networkCallbackRegistered) return
            runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            currentDefaultNetwork = connectivityManager.activeNetwork
            networkAvailable = currentDefaultNetwork != null
            networkCallbackRegistered = true
        }
    }

    // ------------------------------------------------------------------ handoff delivery

    private fun drainPendingHandoff() {
        handoffGate.drainPending(
            pendingGeneration = { pendingHandoff?.generation },
            supersede = { supersededGeneration ->
                supersedePendingHandoff(supersededGeneration)
            },
        )
    }

    private fun supersedePendingHandoff(expectedGeneration: Long) {
        val pending = pendingHandoff ?: return
        if (pending.generation != expectedGeneration || !handoffGate.fail(expectedGeneration)) return
        pendingHandoff = null
        pending.timeoutJob.cancel()
        pending.delivery.supersede()
    }

    private fun completeStartHandoff(generation: Long) {
        if (!handoffGate.complete(generation)) return
        val pending = pendingHandoff ?: return
        if (pending.generation != generation) return
        pendingHandoff = null
        pending.timeoutJob.cancel()
        pending.delivery.complete(Result.success(Unit))
    }

    private fun failStartHandoff(generation: Long, error: Throwable) {
        if (!handoffGate.fail(generation)) return
        val pending = pendingHandoff ?: return
        if (pending.generation != generation) return
        pendingHandoff = null
        pending.timeoutJob.cancel()
        pending.delivery.complete(Result.failure(error))
    }

    // ------------------------------------------------------------------ browser auth operations

    val browserLoginState: CloudflareLoginState
        get() = authSnapshot?.loginState ?: stoppedBrowserLoginState()

    val browserAccountId: String
        get() = authSnapshot?.accountId.orEmpty()

    val browserExistingTunnels: List<ExistingTunnel>
        get() = authSnapshot?.tunnels ?: emptyList()

    val browserLoginRestartRequired: Boolean
        get() = authSnapshot?.restartRequired ?: false

    fun committedCredentialMetadata(): CommittedTunnelCredentialMetadata? =
        authSnapshot?.committedMetadata

    val browserMetadataRebindDecision: BrowserMetadataRebindDecision
        get() = authSnapshot?.browserMetadataRebindDecision()
            ?: BrowserMetadataRebindDecision.Keep

    val credentialMetadataLoading: Boolean
        get() = authSnapshot?.metadataLoading ?: true

    /**
     * Port of the service's `beginLogin`: replaces any live browser session, calls
     * `beginLogin` natively, then polls (`startAuthPolling`/`applyAuthPollResult`) until the
     * login connects, fails, or the bounded poll budget / overall timeout is exhausted.
     */
    suspend fun beginBrowserLogin(): CloudflareLoginState = authMutex.withLock {
        ReadReceiptsTunnelNative.cancelLogin()
        nativeAuthGeneration = 0
        clearTransientAuthState()
        authRestartRequired = false
        val result = ReadReceiptsTunnelNative.beginLogin()
        val native = result.getOrNull()
        if (native == null) {
            ReadReceiptsTunnelNative.cancelLogin()
            finishBrokenBegin()
            // planBeginNativeResult: a failed begin result maps to BROWSER_CREDENTIAL_INVALID.
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                "browser auth operation failed",
            )
        }
        if (
            native.loginState.state != ReadReceiptsTunnelState.STARTING &&
            native.loginState.state != ReadReceiptsTunnelState.CONNECTED
        ) {
            ReadReceiptsTunnelNative.cancelLogin()
            finishBrokenBegin()
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                "browser auth operation failed",
            )
        }
        val semanticLoginState = native.loginState.forServiceTransport()
        nativeAuthGeneration = native.generation
        authSessionGeneration = nextAuthSessionGeneration()
        authLoginState = semanticLoginState
        authAccountId = native.accountId
        authTunnels = emptyList()
        authRestartRequired = false
        publishAuthSnapshot()
        if (semanticLoginState.state == ReadReceiptsTunnelState.CONNECTED) {
            return@withLock semanticLoginState
        }
        val expectedNativeGeneration = native.generation
        withTimeoutOrNull(AUTH_OPERATION_TIMEOUT_MILLIS) {
            repeat(AUTH_LOGIN_POLL_LIMIT) {
                delay(NATIVE_STATUS_POLL_MILLIS)
                val polled = ReadReceiptsTunnelNative.loginStatus().getOrNull()
                if (
                    polled == null ||
                    polled.generation != expectedNativeGeneration ||
                    nativeAuthGeneration != expectedNativeGeneration
                ) {
                    brokenSessionTeardown()
                    throw browserLoginException(
                        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                        "browser auth operation failed",
                    )
                }
                val polledState = polled.loginState.forServiceTransport()
                authLoginState = polledState
                authAccountId = polled.accountId
                when (polled.loginState.state) {
                    ReadReceiptsTunnelState.CONNECTED -> {
                        publishAuthSnapshot()
                        return@withTimeoutOrNull
                    }
                    ReadReceiptsTunnelState.STARTING -> publishAuthSnapshot()
                    else -> {
                        // Preserve the login failure for the UI; a restart is required.
                        // scheduleBrokenAuthTeardown(preserveLoginFailure = true) also cancels
                        // the native login before clearing the native generation.
                        ReadReceiptsTunnelNative.cancelLogin()
                        nativeAuthGeneration = 0
                        authAccountId = ""
                        authTunnels = emptyList()
                        authRestartRequired = true
                        publishAuthSnapshot()
                        throw browserLoginException(
                            ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                            "browser auth operation failed",
                        )
                    }
                }
            }
        }
        val finalLogin = checkNotNull(authLoginState)
        if (finalLogin.state != ReadReceiptsTunnelState.CONNECTED) {
            // Poll budget / overall timeout exhausted while still WAITING (STARTING): the
            // service's startAuthPolling tail tore the session down (native cancel, transient
            // state cleared, restartRequired = true). Match it so browserLoginRestartRequired
            // becomes true and the UI prompts a restart. The caller still receives the STARTING
            // state: the service's begin op had already completed with STARTING before its
            // async teardown ran — it did not throw to the caller.
            brokenSessionTeardown()
        }
        finalLogin
    }

    /** Port of the service's `listTunnels`/`finishList` under exclusive auth admission. */
    suspend fun listExistingTunnels(): List<ExistingTunnel> = authMutex.withLock {
        if (authLoginState == null || nativeAuthGeneration == 0L) {
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                "browser login is not active",
            )
        }
        val timedOut = AtomicBoolean(false)
        val watchdog = beginAuthWatchdog {
            timedOut.set(true)
            brokenSessionTeardown()
        }
        try {
            val result = ReadReceiptsTunnelNative.listExistingTunnels()
            if (timedOut.get()) {
                throw browserLoginException(
                    ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                    "browser auth request timed out",
                )
            }
            finishList(result)
        } finally {
            endAuthWatchdog(watchdog)
        }
    }

    private fun finishList(result: Result<NativeExistingTunnelList>): List<ExistingTunnel> {
        val native = result.getOrNull()
        val login = authLoginState
        val snapshotValid = native != null && login != null &&
            AuthSnapshotBounds.isValid(login, authAccountId, native.tunnels, cachedCredentialMetadata)
        if (native == null || login == null || native.generation != nativeAuthGeneration) {
            brokenSessionTeardown()
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                "browser auth operation failed",
            )
        }
        if (native.error != null || !snapshotValid) {
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                "browser auth operation failed",
            )
        }
        authTunnels = Collections.unmodifiableList(ArrayList(native.tunnels))
        publishAuthSnapshot()
        return authTunnels
    }

    /**
     * Port of the service's `selectTunnel` + candidate pipeline: connector-generation
     * reservation via `stopCompletion.startAdmission`, `SelectCommitGate` commit ordering, and
     * `runTunnel(request, initialReservation, firstVerification)` candidate semantics.
     */
    suspend fun selectExistingTunnel(
        id: String,
        canonicalRoot: String,
        fixedPort: Int,
    ): Result<Unit> = authMutex.withLock {
        if (!ExistingTunnel.isCanonicalId(id)) {
            return@withLock Result.failure(
                browserLoginException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "invalid tunnel ID",
                ),
            )
        }
        if (
            ReadReceiptsTunnelHostnames.canonicalPublicRoot(canonicalRoot) != canonicalRoot
        ) {
            return@withLock Result.failure(
                browserLoginException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "invalid tunnel hostname",
                ),
            )
        }
        if (fixedPort !in 1..65535) {
            return@withLock Result.failure(
                browserLoginException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "invalid loopback port",
                ),
            )
        }
        val connectorGeneration = when (
            val admission = stopCompletion.startAdmission(::reserveConnectorGeneration)
        ) {
            is TunnelStartAdmission.Admitted -> admission.generation
            is TunnelStartAdmission.Rejected -> {
                return@withLock Result.failure(admission.failure)
            }
        }
        if (authLoginState == null || nativeAuthGeneration == 0L) {
            return@withLock Result.failure(
                browserLoginException(
                    ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                    "browser login is not active",
                ),
            )
        }
        val tunnel = authTunnels.firstOrNull { it.id == id }
        if (tunnel == null) {
            // finishSelectUnavailableCredential: the cached tunnel list no longer holds the ID.
            return@withLock Result.failure(
                browserLoginException(
                    ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                    "browser auth operation failed",
                ),
            )
        }
        val expectedNativeGeneration = nativeAuthGeneration
        val commitGate = SelectCommitGate()
        val timedOut = AtomicBoolean(false)
        val watchdog = beginAuthWatchdog {
            timedOut.set(true)
            commitGate.tryTerminal()
            brokenSessionTeardown()
        }
        try {
            val result = ReadReceiptsTunnelNative.selectExistingTunnelForService(
                id,
                canonicalRoot,
            )
            val prepared = prepareSelectedCandidate(
                DeferredAuthSelection(id, canonicalRoot, fixedPort, connectorGeneration),
                tunnel.name,
                expectedNativeGeneration,
                result,
                commitGate,
            )
            val outcome = prepared.firstVerification.await()
            if (outcome != SelectCandidateOutcome.COMMITTED) {
                prepared.candidate.join()
            }
            finishSelectOutcome(prepared, commitGate, connectorGeneration, outcome, timedOut.get())
        } catch (cancelled: CancellationException) {
            finishCancelledSelect(commitGate)
            throw cancelled
        } finally {
            endAuthWatchdog(watchdog)
        }
    }

    private fun prepareSelectedCandidate(
        selection: DeferredAuthSelection,
        tunnelName: String,
        expectedNativeGeneration: Long,
        result: Result<String>,
        commitGate: SelectCommitGate,
    ): PreparedSelectCandidate {
        val sessionAvailable = expectedNativeGeneration != 0L &&
            nativeAuthGeneration == expectedNativeGeneration
        if (!sessionAvailable) {
            brokenSessionTeardown()
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                "browser auth operation failed",
            )
        }
        val token = result.getOrNull()
        val payload = token?.let {
            TunnelCredentialPayload.create(
                runToken = it,
                source = TunnelCredentialSource.BROWSER_LOGIN,
                accountId = authAccountId,
                tunnelId = selection.tunnelId,
                tunnelName = tunnelName,
                canonicalHostname = selection.canonicalRoot,
                fixedOriginPort = selection.fixedOriginPort,
            )
        }
        if (payload == null) {
            // finishSelectNativeResult(credentialValid = false): preserve the auth session.
            check(commitGate.tryTerminal())
            throw browserLoginException(
                ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                "browser auth operation failed",
            )
        }
        val request = TunnelRequest(
            generation = selection.connectorGeneration,
            mode = ReadReceiptsTunnelMode.BROWSER_LOGIN,
            origin = "http://127.0.0.1:${selection.fixedOriginPort}/",
            publicRoot = selection.canonicalRoot.toHttpUrlOrNull()!!,
            connectorAuthenticator = clientNonce,
            pendingToken = null,
            browserCredential = payload,
            browserCredentialNeedsCommit = true,
            selectCommitGate = commitGate,
        )
        val reservation = nativeLease.advanceAndReserve(selection.connectorGeneration) {
            activeRequest = request
            authoritativeState.set(
                AuthoritativeTunnelState(
                    selection.connectorGeneration,
                    ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING),
                ),
            )
        }
        if (reservation == null) {
            check(commitGate.tryTerminal())
            throw ReadReceiptsTunnelException(
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                "select superseded",
            )
        }
        val firstVerification = CompletableDeferred<SelectCandidateOutcome>()
        val candidate = replaceLifecycleForSelect(
            request,
            reservation,
            firstVerification,
        )
        return PreparedSelectCandidate(firstVerification, candidate)
    }

    private fun finishSelectOutcome(
        prepared: PreparedSelectCandidate,
        commitGate: SelectCommitGate,
        connectorGeneration: Long,
        outcome: SelectCandidateOutcome,
        timedOut: Boolean,
    ): Result<Unit> = when (outcome) {
        SelectCandidateOutcome.COMMITTED -> {
            finishCommittedSelect()
            Result.success(Unit)
        }
        SelectCandidateOutcome.STALE -> {
            if (!commitGate.tryTerminal()) {
                return Result.failure(
                    ReadReceiptsTunnelException(
                        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                        "select superseded",
                    ),
                )
            }
            if (activeRequest?.generation == connectorGeneration) {
                activeRequest = null
                nativeLease.clearRequest(connectorGeneration)
                publishFailure(
                    connectorGeneration,
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                )
            }
            Result.failure(
                ReadReceiptsTunnelException(
                    ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
                    "select superseded",
                ),
            )
        }
        is SelectCandidateOutcome.Failed -> {
            if (!commitGate.isCommitClaimed() && !commitGate.tryTerminal()) {
                // The watchdog already claimed the terminal: this is a timeout outcome.
                return Result.failure(
                    browserLoginException(
                        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                        "browser auth request timed out",
                    ),
                )
            }
            val errorCode = selectFailureErrorCode(outcome.failure)
            publishSelectCandidateFailure(connectorGeneration, errorCode)
            if (timedOut) {
                Result.failure(
                    browserLoginException(
                        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE,
                        "browser auth request timed out",
                    ),
                )
            } else {
                Result.failure(
                    browserLoginException(errorCode, "browser auth operation failed"),
                )
            }
        }
    }

    /** Error-code mapping ported from `ServiceAuthCoordinator.planSelect*` classification. */
    private fun selectFailureErrorCode(
        failure: SelectCandidateFailure,
    ): ReadReceiptsTunnelErrorCode = when (val source = failure.source) {
        is SelectFailureSource.CredentialCommit -> ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED
        is SelectFailureSource.HealthVerification -> ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED
        is SelectFailureSource.ConnectorTerminal -> when (source.errorCode) {
            ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED ->
                ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED
            ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED ->
                ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED
            ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
            ReadReceiptsTunnelErrorCode.TOKEN_INVALID,
            -> ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID
            ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE ->
                ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE
            else -> ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE
        }
    }

    /** Port of `scheduleSelectCandidateFailure`'s semantic-failure publish decision. */
    private fun publishSelectCandidateFailure(
        generation: Long,
        errorCode: ReadReceiptsTunnelErrorCode,
    ) {
        val current = authoritativeState.get()
        val shouldPublishSemanticFailure = when (current.status.state) {
            ReadReceiptsTunnelState.STARTING,
            ReadReceiptsTunnelState.RECONNECTING,
            ReadReceiptsTunnelState.STOPPING,
            -> true
            ReadReceiptsTunnelState.FAILED -> current.status.errorCode ==
                ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE
            else -> false
        }
        if (current.generation == generation && shouldPublishSemanticFailure) {
            publishFailure(generation, errorCode)
        }
    }

    private fun finishCommittedSelect() {
        val cleaned = ReadReceiptsTunnelNative.cancelLogin().isSuccess
        nativeAuthGeneration = 0
        clearTransientAuthState()
        authRestartRequired = !cleaned
        publishAuthSnapshot()
    }

    private fun finishCancelledSelect(commitGate: SelectCommitGate) {
        if (!commitGate.tryTerminal()) return
        // planSelectCancellation: COROUTINE_CANCELLED -> CANCEL_AUTH_AND_RESTART_REQUIRED.
        brokenSessionTeardown()
    }

    /** Port of the service's `clearLogin` for both CANCEL and LOGOUT. */
    suspend fun cancelBrowserLogin(): Result<Unit> = clearBrowserLogin()

    suspend fun logoutBrowserLogin(): Result<Unit> = clearBrowserLogin()

    private suspend fun clearBrowserLogin(): Result<Unit> = authMutex.withLock {
        if (authLoginState == null || nativeAuthGeneration == 0L) {
            return@withLock Result.failure(
                browserLoginException(
                    ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID,
                    "browser login is not active",
                ),
            )
        }
        val cleaned = ReadReceiptsTunnelNative.cancelLogin().isSuccess
        nativeAuthGeneration = 0
        clearTransientAuthState()
        authRestartRequired = !cleaned
        publishAuthSnapshot()
        Result.success(Unit)
    }

    // ------------------------------------------------------------------ auth watchdog + snapshot

    /**
     * Watchdog for auth operations whose blocking JNI call must be cancellable from another
     * coroutine (list/select), mirroring the service's per-operation watchdogs.
     */
    private fun beginAuthWatchdog(onTimeout: () -> Unit): Any {
        val owner = Any()
        synchronized(authWatchdogLock) { authWatchdogOwner = owner }
        scope.launch {
            delay(AUTH_OPERATION_TIMEOUT_MILLIS)
            val current = synchronized(authWatchdogLock) { authWatchdogOwner }
            if (current === owner) onTimeout()
        }
        return owner
    }

    private fun endAuthWatchdog(owner: Any) {
        synchronized(authWatchdogLock) {
            if (authWatchdogOwner === owner) authWatchdogOwner = null
        }
    }

    /** Port of `scheduleBrokenAuthTeardown`: session broken, restart required. */
    private fun brokenSessionTeardown() {
        ReadReceiptsTunnelNative.cancelLogin()
        nativeAuthGeneration = 0
        clearTransientAuthState()
        authRestartRequired = true
        publishAuthSnapshot()
    }

    /** Port of `finishBrokenBegin` as a plain failure path. */
    private fun finishBrokenBegin() {
        nativeAuthGeneration = 0
        clearTransientAuthState()
        authRestartRequired = true
        publishAuthSnapshot()
    }

    private fun clearTransientAuthState() {
        authLoginState = null
        authAccountId = ""
        authTunnels = emptyList()
    }

    /** Replaces `broadcastAuthSnapshot` + `sendAuthSnapshot`: publish in-process, if valid. */
    @Synchronized
    private fun publishAuthSnapshot() {
        val login = authLoginState ?: stoppedBrowserLoginState()
        if (!AuthSnapshotBounds.isValid(login, authAccountId, authTunnels, cachedCredentialMetadata)) {
            return
        }
        val publishedAuthGeneration = if (nativeAuthGeneration != 0L) {
            authSessionGeneration
        } else {
            0L
        }
        val snapshot = runCatching {
            ControllerAuthSnapshot(
                revision = ++authSnapshotRevision,
                authGeneration = publishedAuthGeneration,
                restartRequired = authRestartRequired,
                loginState = login,
                accountId = authAccountId,
                tunnels = authTunnels,
                metadataLoading = credentialMetadataLoadingInternal,
                committedMetadata = cachedCredentialMetadata,
            )
        }.getOrNull() ?: return
        authSnapshot = snapshot
        listeners.forEach { it() }
    }

    private fun stoppedBrowserLoginState(): CloudflareLoginState = CloudflareLoginState(
        authorizationUrl = null,
        state = ReadReceiptsTunnelState.STOPPED,
        error = null,
    )

    private fun CloudflareLoginState.forServiceTransport(): CloudflareLoginState {
        if (error == null) return this
        WeLogger.w(
            TAG,
            "redacted browser-login diagnostic (chars=${error.length}, bytes=${error.toByteArray().size})",
        )
        return copy(error = ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID.name)
    }

    // ------------------------------------------------------------------ credential IO

    private fun loadCredentialMetadataOnIo(): CredentialCacheUpdate =
        synchronized(credentialFileLock) {
            val metadata = if (credentialStore.exists()) {
                credentialStore.readMetadata().getOrNull()
            } else {
                null
            }
            CredentialCacheUpdate(++credentialFileRevision, metadata)
        }

    private fun readCredentialOnIo(): Result<TunnelCredentialPayload> {
        var update: CredentialCacheUpdate? = null
        val result = synchronized(credentialFileLock) {
            credentialStore.read().also {
                if (it.isFailure) {
                    update = CredentialCacheUpdate(++credentialFileRevision, null)
                }
            }
        }
        update?.let(::postCredentialCacheUpdate)
        return result
    }

    private fun writeCredentialOnIo(payload: TunnelCredentialPayload): Boolean {
        var update: CredentialCacheUpdate? = null
        val succeeded = synchronized(credentialFileLock) {
            credentialStore.write(payload).isSuccess.also { success ->
                if (success) {
                    update = CredentialCacheUpdate(
                        ++credentialFileRevision,
                        payload.committedMetadata(),
                    )
                }
            }
        }
        update?.let(::postCredentialCacheUpdate)
        return succeeded
    }

    private fun clearCredentialOnIo(): CredentialCacheUpdate =
        synchronized(credentialFileLock) {
            credentialStore.clear()
            CredentialCacheUpdate(++credentialFileRevision, null)
        }

    private fun postCredentialCacheUpdate(update: CredentialCacheUpdate) {
        applyCredentialCacheUpdate(update)
    }

    @Synchronized
    private fun applyCredentialCacheUpdate(update: CredentialCacheUpdate) {
        if (update.revision < appliedCredentialRevision) return
        appliedCredentialRevision = update.revision
        cachedCredentialMetadata = update.metadata
        cachedCredentialExists = update.metadata != null
        credentialMetadataLoadingInternal = false
        publishAuthSnapshot()
        listeners.forEach { it() }
    }

    // ------------------------------------------------------------------ HTTP + publish

    private suspend fun checkHealth(root: HttpUrl): Boolean {
        val url = root.newBuilder().addPathSegment("health").build()
        val request = Request.Builder().url(url).get().build()
        return execute(request).fold(
            onSuccess = { response ->
                response.use {
                    it.code == 204 && it.body.source().exhausted()
                }
            },
            onFailure = { false },
        )
    }

    private suspend fun execute(request: Request): Result<Response> =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            })
        }

    private fun publishFailure(
        expectedGeneration: Long,
        errorCode: ReadReceiptsTunnelErrorCode,
    ) {
        publish(
            expectedGeneration,
            ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.FAILED,
                errorCode = errorCode,
            ),
        )
    }

    private fun publish(expectedGeneration: Long, value: ReadReceiptsTunnelStatus) {
        val sanitized = value.copy(publicUrl = value.publicUrl?.take(MAX_URL_CHARS))
        while (true) {
            val current = authoritativeState.get()
            if (current.generation != expectedGeneration) return
            if (
                authoritativeState.compareAndSet(
                    current,
                    AuthoritativeTunnelState(expectedGeneration, sanitized),
                )
            ) {
                break
            }
        }
        listeners.forEach { it() }
        if (sanitized.state == ReadReceiptsTunnelState.STOPPED) {
            // Exactly the controller's IncomingHandler STOPPED branch.
            val drain = stopCompletion.complete(expectedGeneration)
            if (drain.matched) {
                drain.callbacks.forEach { callback -> callback(Result.success(Unit)) }
            } else {
                ReadReceipts.onTunnelServiceStopped()
            }
        }
    }

    private fun isConnectorAuthenticator(value: String): Boolean =
        value.length == 32 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/'
        }

    private fun browserLoginException(
        errorCode: ReadReceiptsTunnelErrorCode,
        diagnostic: String,
    ): BrowserLoginException = BrowserLoginException(
        errorCode,
        diagnostic.take(MAX_AUTH_ERROR_CHARS),
    )

    private fun tunnelException(
        errorCode: ReadReceiptsTunnelErrorCode,
        diagnostic: String,
    ): ReadReceiptsTunnelException = ReadReceiptsTunnelException(errorCode, diagnostic)

    private fun nextGeneration(): Long = generationCounter.updateAndGet { current ->
        maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
    }

    private fun reserveConnectorGeneration(): Long = generationCounter.updateAndGet { current ->
        maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
    }

    private fun nextAuthSessionGeneration(): Long =
        authSessionGenerationCounter.updateAndGet { current ->
            maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
        }

    // ------------------------------------------------------------------ nested types

    private data class TunnelRequest(
        val generation: Long,
        val mode: ReadReceiptsTunnelMode,
        val origin: String,
        val publicRoot: HttpUrl?,
        val connectorAuthenticator: String,
        var pendingToken: String?,
        var browserCredential: TunnelCredentialPayload? = null,
        var browserCredentialNeedsCommit: Boolean = false,
        val selectCommitGate: SelectCommitGate? = null,
    )

    private data class PreparedSelectCandidate(
        val firstVerification: CompletableDeferred<SelectCandidateOutcome>,
        val candidate: Job,
    )

    private sealed interface SelectCandidateOutcome {
        data object COMMITTED : SelectCandidateOutcome

        data object STALE : SelectCandidateOutcome

        data class Failed(
            val failure: SelectCandidateFailure,
        ) : SelectCandidateOutcome
    }

    private sealed interface SelectFailureSource {
        data class CredentialCommit(val result: TunnelVerificationCommit) : SelectFailureSource

        data class HealthVerification(val healthy: Boolean) : SelectFailureSource

        data class ConnectorTerminal(val errorCode: ReadReceiptsTunnelErrorCode) :
            SelectFailureSource
    }

    private data class SelectCandidateFailure(val source: SelectFailureSource)

    private data class DeferredAuthSelection(
        val tunnelId: String,
        val canonicalRoot: String,
        val fixedOriginPort: Int,
        val connectorGeneration: Long,
    )

    private data class CredentialCacheUpdate(
        val revision: Long,
        val metadata: CommittedTunnelCredentialMetadata?,
    )

    private data class AuthoritativeTunnelState(
        val generation: Long,
        val status: ReadReceiptsTunnelStatus,
    )

    private data class PendingHandoff(
        val generation: Long,
        val delivery: TunnelHandoffTerminalDelivery,
        val timeoutJob: Job,
    )

    private const val TAG = "ReadReceiptsTunnelRuntime"
    private const val START_HANDOFF_TIMEOUT_MILLIS = 10_000L
    private const val STOP_COMPLETION_TIMEOUT_MILLIS = 20_000L
    private const val NATIVE_STATUS_POLL_MILLIS = 500L
    private const val NETWORK_POLL_MILLIS = 1_000L
    private const val MAX_RECONNECT_ATTEMPTS = 5
    private const val MAX_PUBLIC_HEALTH_ATTEMPTS = 12
    private const val PUBLIC_HEALTH_RECHECK_MILLIS = 15_000L
    private const val MAX_TOKEN_CHARS = 16 * 1024
    private const val MAX_URL_CHARS = 2048
    private const val AUTH_OPERATION_TIMEOUT_MILLIS = 30_000L
    private const val AUTH_LOGIN_POLL_LIMIT = 1_200
    private const val MAX_AUTH_ERROR_CHARS = 256
    private val RECONNECT_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)
}
