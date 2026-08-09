package dev.ujhhgtg.wekit.features.items.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/** Module-process owner of the embedded Cloudflare connector and retained run credential. */
class ReadReceiptsTunnelService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val authControlScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listeners = ConcurrentHashMap<IBinder, StatusListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
    private val credentialStore by lazy { TunnelCredentialStore(this) }
    private val credentialFileLock = Any()
    private val nativeLease = TunnelNativeLease()
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val authoritativeState = AtomicReference(
        AuthoritativeTunnelState(
            generation = 0L,
            status = ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPED),
        ),
    )

    private val status: ReadReceiptsTunnelStatus
        get() = authoritativeState.get().status

    private val generation: Long
        get() = authoritativeState.get().generation

    @Volatile
    private var activeRequest: TunnelRequest? = null

    @Volatile
    private var networkAvailable = true

    private val networkLock = Any()
    private var currentDefaultNetwork: Network? = null

    private var lifecycleJob: Job? = null
    private var authorizationTimeout: Job? = null
    private var authorizedCommandSeen = false
    private var foregroundActive = false
    private val authCoordinator = ServiceAuthCoordinator()
    private val authOperationJobs = mutableMapOf<AuthOperationKey, AuthOperationJobs>()
    private var authCleanupJob: Job? = null
    private var authPollJob: Job? = null
    private var authLoginState: CloudflareLoginState? = null
    private var authAccountId = ""
    private var authTunnels: List<ExistingTunnel> = emptyList()
    private var authSnapshotRevision = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
    private var credentialMetadataLoading = true
    private var cachedCredentialMetadata: CommittedTunnelCredentialMetadata? = null

    @Volatile
    private var cachedCredentialExists = false
    private var appliedCredentialRevision = 0L
    private var credentialFileRevision = 0L

    @Volatile
    private var nativeAuthGeneration = 0L

    private val notificationStopNonce = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP) }

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        synchronized(networkLock) {
            currentDefaultNetwork = connectivityManager.activeNetwork
            networkAvailable = currentDefaultNetwork != null
        }
        scope.launch {
            val update = loadCredentialMetadataOnIo()
            withContext(Dispatchers.Main.immediate) {
                applyCredentialCacheUpdate(update)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(status))
        foregroundActive = true
        if (
            intent?.action == ACTION_STOP &&
            intent.getStringExtra(EXTRA_STOP_NONCE) == notificationStopNonce
        ) {
            stopTunnel(generation)
        } else if (!authorizedCommandSeen) {
            authorizationTimeout?.cancel()
            authorizationTimeout = scope.launch {
                delay(AUTHORIZATION_TIMEOUT_MILLIS)
                if (!authorizedCommandSeen) {
                    stopTunnel(generation)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        authorizationTimeout?.cancel()
        activeRequest = null
        lifecycleJob?.cancel()
        val (priorProcessTeardown, processTeardown) = registerProcessAuthTeardown()
        val authJobs = authOperationJobs.values.flatMap { jobs ->
            listOfNotNull(jobs.worker, jobs.watchdog)
        } + listOfNotNull(authPollJob, authCleanupJob)
        authControlScope.launch {
            try {
                priorProcessTeardown?.join()
                runCatching { ReadReceiptsTunnelNative.cancelLogin() }
                authJobs.forEach(Job::cancel)
                for (job in authJobs) job.join()
                runCatching { ReadReceiptsTunnelNative.cancelLogin() }
            } finally {
                processTeardown.complete(Unit)
                clearProcessAuthTeardown(processTeardown)
                authControlScope.cancel()
            }
        }
        authOperationJobs.clear()
        scope.cancel()
        ReadReceiptsTunnelNative.stop()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        httpClient.dispatcher.cancelAll()
        super.onDestroy()
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (!isAuthorizedUid(message.sendingUid)) return
            when (message.what) {
                ReadReceiptsTunnelProtocol.REGISTER -> runCatching {
                    register(message.replyTo, message.data)
                }
                ReadReceiptsTunnelProtocol.START -> {
                    markAuthorizedCommand()
                    handleStart(message.data, message.replyTo)
                }
                ReadReceiptsTunnelProtocol.STOP -> {
                    markAuthorizedCommand()
                    stopTunnel(message.data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION))
                }
                ReadReceiptsTunnelProtocol.DELETE_CREDENTIAL -> {
                    markAuthorizedCommand()
                    deleteCredential(
                        message.data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION),
                    )
                }
                ReadReceiptsTunnelProtocol.BEGIN_LOGIN ->
                    handleAuthMessage(message, ServiceAuthWireKind.BEGIN)
                ReadReceiptsTunnelProtocol.LIST_TUNNELS ->
                    handleAuthMessage(message, ServiceAuthWireKind.LIST)
                ReadReceiptsTunnelProtocol.SELECT_TUNNEL ->
                    handleAuthMessage(message, ServiceAuthWireKind.SELECT)
                ReadReceiptsTunnelProtocol.CANCEL_LOGIN ->
                    handleAuthMessage(message, ServiceAuthWireKind.CANCEL)
                ReadReceiptsTunnelProtocol.LOGOUT ->
                    handleAuthMessage(message, ServiceAuthWireKind.LOGOUT)
                else -> super.handleMessage(message)
            }
        }
    }

    private fun isAuthorizedUid(uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        return packageManager.getPackagesForUid(uid)?.contains(WECHAT_PACKAGE) == true
    }

    private fun register(client: Messenger?, data: Bundle) {
        val parsed = parseRegister(client, data) ?: return
        markAuthorizedCommand()
        val listener = StatusListener(parsed.client, parsed.nonce, parsed.lastSeenAuthGeneration)
        listeners[parsed.client.binder] = listener
        sendStatus(listener)
        sendAuthSnapshot(listener)
    }

    private fun parseRegister(client: Messenger?, data: Bundle): AuthRegistration? = runCatching {
        client ?: return null
        val keys = data.keySet()
        if (
            ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE !in keys ||
            keys.any { it !in ReadReceiptsTunnelProtocol.REGISTER_KEYS }
        ) {
            return null
        }
        val nonce = data.strictString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE) ?: return null
        if (!isValidClientNonce(nonce)) return null
        val lastSeen = if (ReadReceiptsTunnelProtocol.KEY_LAST_SEEN_AUTH_GENERATION in keys) {
            data.strictLong(ReadReceiptsTunnelProtocol.KEY_LAST_SEEN_AUTH_GENERATION)
                ?.takeIf { it > 0 } ?: return null
        } else {
            0L
        }
        AuthRegistration(client, nonce, lastSeen)
    }.getOrNull()

    private fun handleAuthMessage(message: Message, expectedKind: ServiceAuthWireKind) {
        val envelope = parseAuthEnvelope(message, expectedKind) ?: return
        markAuthorizedCommand()
        when (expectedKind) {
            ServiceAuthWireKind.BEGIN -> beginLogin(envelope)
            ServiceAuthWireKind.LIST -> listTunnels(envelope)
            ServiceAuthWireKind.SELECT -> {
                sendAuthAck(envelope, accepted = false)
                sendAuthTerminal(envelope, AuthOperationTerminal.Failed(AUTH_SELECT_DEFERRED))
            }
            ServiceAuthWireKind.CANCEL -> clearLogin(envelope, AuthOperationKind.CANCEL)
            ServiceAuthWireKind.LOGOUT -> clearLogin(envelope, AuthOperationKind.LOGOUT)
        }
    }

    private fun parseAuthEnvelope(
        message: Message,
        expectedKind: ServiceAuthWireKind,
    ): ServiceAuthEnvelope? = runCatching {
        val client = message.replyTo ?: return null
        val listener = listeners[client.binder] ?: return null
        val data = message.data
        val expectedKeys = if (expectedKind == ServiceAuthWireKind.SELECT) {
            ReadReceiptsTunnelProtocol.AUTH_OPERATION_KEYS +
                ReadReceiptsTunnelProtocol.SELECT_OPERATION_KEYS
        } else {
            ReadReceiptsTunnelProtocol.AUTH_OPERATION_KEYS
        }
        if (data.keySet() != expectedKeys) return null
        val nonce = data.strictString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE) ?: return null
        if (nonce != listener.nonce || !isValidClientNonce(nonce)) return null
        val kind = data.strictString(ReadReceiptsTunnelProtocol.KEY_AUTH_KIND) ?: return null
        if (kind != expectedKind.wireName) return null
        val authGeneration = data.strictLong(ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION)
            ?.takeIf { it > 0 } ?: return null
        val requestId = data.strictLong(ReadReceiptsTunnelProtocol.KEY_AUTH_REQUEST_ID)
            ?.takeIf { it > 0 } ?: return null
        val selection = if (expectedKind == ServiceAuthWireKind.SELECT) {
            parseSelection(data) ?: return null
        } else {
            null
        }
        ServiceAuthEnvelope(
            listener = listener,
            key = AuthOperationKey(authGeneration, requestId),
            wireKind = expectedKind,
            selection = selection,
        )
    }.getOrNull()

    private fun parseSelection(data: Bundle): DeferredAuthSelection? {
        val tunnelId = data.strictString(ReadReceiptsTunnelProtocol.KEY_TUNNEL_ID) ?: return null
        if (!ExistingTunnel.isCanonicalId(tunnelId)) return null
        val canonicalRoot = data.strictString(ReadReceiptsTunnelProtocol.KEY_HOSTNAME) ?: return null
        if (canonicalPublicRoot(canonicalRoot) != canonicalRoot) return null
        val originPort = data.strictInt(ReadReceiptsTunnelProtocol.KEY_FIXED_ORIGIN_PORT)
            ?.takeIf { it in 1..65535 } ?: return null
        val connectorGeneration = data.strictLong(
            ReadReceiptsTunnelProtocol.KEY_CONNECTOR_GENERATION,
        )?.takeIf { it > 0 } ?: return null
        return DeferredAuthSelection(tunnelId, canonicalRoot, originPort, connectorGeneration)
    }

    private fun Bundle.strictString(key: String): String? = get(key) as? String

    private fun Bundle.strictLong(key: String): Long? = get(key) as? Long

    private fun Bundle.strictInt(key: String): Int? = get(key) as? Int

    private fun isValidClientNonce(value: String): Boolean =
        value.length in 16..128 && value.all { it.code in 0x20..0x7e }

    private fun markAuthorizedCommand() {
        authorizedCommandSeen = true
        authorizationTimeout?.cancel()
    }

    private fun beginLogin(envelope: ServiceAuthEnvelope) {
        val admission = authCoordinator.begin(envelope.key) { terminal ->
            sendAuthTerminal(envelope, terminal)
        }
        if (admission is ServiceAuthAdmission.Rejected) {
            rejectAuthAdmission(envelope, admission)
            return
        }
        check(authCoordinator.claimAck(envelope.key, AuthOperationKind.BEGIN))
        sendAuthAck(envelope, accepted = true)

        val processTeardown = captureProcessAuthTeardown()
        val previousJobs = authOperationJobs.values.flatMap { jobs ->
            listOfNotNull(jobs.worker, jobs.watchdog)
        } + listOfNotNull(authPollJob, authCleanupJob)
        authOperationJobs.clear()
        authPollJob = null
        authCleanupJob = authControlScope.launch {
            processTeardown?.join()
            var cancelled = ReadReceiptsTunnelNative.cancelLogin().isSuccess
            previousJobs.forEach(Job::cancel)
            for (job in previousJobs) job.join()
            cancelled = ReadReceiptsTunnelNative.cancelLogin().isSuccess && cancelled
            val barrierFinished = withContext(Dispatchers.Main.immediate) {
                if (!authCoordinator.finishBeginBarrier(envelope.key)) return@withContext false
                nativeAuthGeneration = 0
                clearTransientAuthState()
                true
            }
            if (!barrierFinished) return@launch
            if (!cancelled) {
                withContext(Dispatchers.Main.immediate) {
                    failBeginAfterBarrier(envelope, AUTH_CLEANUP_FAILED)
                }
                return@launch
            }

            val result = ReadReceiptsTunnelNative.beginLogin()
            val initial = result.getOrNull()
            if (
                initial != null &&
                initial.loginState.state != ReadReceiptsTunnelState.STARTING &&
                initial.loginState.state != ReadReceiptsTunnelState.CONNECTED
            ) {
                val failurePlan = withContext(Dispatchers.Main.immediate) {
                    if (!authCoordinator.canPublish(envelope.key, AuthOperationKind.BEGIN)) {
                        return@withContext null
                    }
                    checkNotNull(
                        authCoordinator.planFailure(
                            envelope.key,
                            AuthOperationKind.BEGIN,
                            ServiceAuthFailure.SESSION_BROKEN,
                            AUTH_BEGIN_FAILED,
                        ),
                    )
                } ?: return@launch
                ReadReceiptsTunnelNative.cancelLogin()
                withContext(Dispatchers.Main.immediate) {
                    finishBrokenBegin(failurePlan)
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                authCleanupJob = null
                if (!authCoordinator.canPublish(envelope.key, AuthOperationKind.BEGIN)) {
                    return@withContext
                }
                val native = result.getOrNull()
                if (native == null) {
                    failBeginAfterBarrier(envelope, AUTH_BEGIN_FAILED)
                    return@withContext
                }
                nativeAuthGeneration = native.generation
                authLoginState = native.loginState
                authAccountId = native.accountId
                authTunnels = emptyList()
                if (native.loginState.state == ReadReceiptsTunnelState.CONNECTED) {
                    check(authCoordinator.markAuthorized(envelope.key.authGeneration))
                }
                check(
                    authCoordinator.complete(
                        envelope.key,
                        AuthOperationKind.BEGIN,
                        AuthOperationTerminal.Completed(native.loginState),
                    ),
                )
                broadcastAuthSnapshot()
                if (native.loginState.state == ReadReceiptsTunnelState.STARTING) {
                    startAuthPolling(envelope.key.authGeneration, native.generation)
                }
            }
        }
    }

    private fun failBeginAfterBarrier(envelope: ServiceAuthEnvelope, message: String) {
        val plan = checkNotNull(
            authCoordinator.planFailure(
                envelope.key,
                AuthOperationKind.BEGIN,
                ServiceAuthFailure.SESSION_BROKEN,
                message,
            ),
        )
        finishBrokenBegin(plan)
    }

    private fun finishBrokenBegin(
        plan: ServiceAuthFailurePlan<CloudflareLoginState>,
    ) {
        authCleanupJob = null
        nativeAuthGeneration = 0
        clearTransientAuthState()
        check(authCoordinator.finishFailure(plan))
        broadcastAuthSnapshot()
    }

    private fun startAuthPolling(authGeneration: Long, expectedNativeGeneration: Long) {
        authPollJob?.cancel()
        authPollJob = scope.launch {
            repeat(AUTH_LOGIN_POLL_LIMIT) {
                delay(NATIVE_STATUS_POLL_MILLIS)
                val result = ReadReceiptsTunnelNative.loginStatus()
                val keepPolling = withContext(Dispatchers.Main.immediate) {
                    applyAuthPollResult(authGeneration, expectedNativeGeneration, result)
                }
                if (!keepPolling) return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                val snapshot = authCoordinator.snapshot()
                if (
                    nativeAuthGeneration == expectedNativeGeneration &&
                    snapshot.authGeneration == authGeneration &&
                    snapshot.phase == ServiceAuthSessionPhase.WAITING
                ) {
                    val plan = checkNotNull(authCoordinator.planSessionTeardown(authGeneration))
                    scheduleBrokenAuthTeardown(plan, preserveLoginFailure = false)
                }
            }
        }
    }

    private fun applyAuthPollResult(
        authGeneration: Long,
        expectedNativeGeneration: Long,
        result: Result<NativeCloudflareLoginStatus>,
    ): Boolean {
        if (
            nativeAuthGeneration != expectedNativeGeneration ||
            authCoordinator.snapshot().let { snapshot ->
                snapshot.authGeneration != authGeneration ||
                    snapshot.phase != ServiceAuthSessionPhase.WAITING
            }
        ) {
            return false
        }
        val native = result.getOrNull()
        if (native == null || native.generation != expectedNativeGeneration) {
            val plan = checkNotNull(authCoordinator.planSessionTeardown(authGeneration))
            scheduleBrokenAuthTeardown(plan, preserveLoginFailure = false)
            return false
        }
        val changed = authLoginState != native.loginState || authAccountId != native.accountId
        authLoginState = native.loginState
        authAccountId = native.accountId
        return when (native.loginState.state) {
            ReadReceiptsTunnelState.CONNECTED -> {
                check(authCoordinator.markAuthorized(authGeneration))
                if (changed) broadcastAuthSnapshot()
                authPollJob = null
                false
            }
            ReadReceiptsTunnelState.STARTING -> {
                if (changed) broadcastAuthSnapshot()
                true
            }
            else -> {
                val plan = checkNotNull(authCoordinator.planSessionTeardown(authGeneration))
                scheduleBrokenAuthTeardown(plan, preserveLoginFailure = true)
                false
            }
        }
    }

    private fun scheduleBrokenAuthTeardown(
        plan: ServiceAuthSessionTeardownPlan,
        preserveLoginFailure: Boolean,
    ) {
        val jobsToDrain = authOperationJobs.values.flatMap { jobs ->
            listOfNotNull(jobs.worker, jobs.watchdog)
        } + listOfNotNull(authPollJob)
        authOperationJobs.clear()
        authPollJob = null
        authCleanupJob = authControlScope.launch {
            ReadReceiptsTunnelNative.cancelLogin()
            jobsToDrain.forEach(Job::cancel)
            for (job in jobsToDrain) job.join()
            ReadReceiptsTunnelNative.cancelLogin()
            withContext(Dispatchers.Main.immediate) {
                authCleanupJob = null
                nativeAuthGeneration = 0
                if (preserveLoginFailure) {
                    authAccountId = ""
                    authTunnels = emptyList()
                } else {
                    clearTransientAuthState()
                }
                check(authCoordinator.finishSessionTeardown(plan, restartRequired = true))
                broadcastAuthSnapshot()
            }
        }
    }

    private fun listTunnels(envelope: ServiceAuthEnvelope) {
        val admission = authCoordinator.admit(
            envelope.key,
            AuthOperationKind.LIST,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
        ) { terminal -> sendAuthTerminal(envelope, terminal) }
        if (admission is ServiceAuthAdmission.Rejected) {
            rejectAuthAdmission(envelope, admission)
            return
        }
        check(authCoordinator.claimAck(envelope.key, AuthOperationKind.LIST))
        sendAuthAck(envelope, accepted = true)

        lateinit var worker: Job
        worker = scope.launch(start = CoroutineStart.LAZY) {
            val result = ReadReceiptsTunnelNative.listExistingTunnels()
            withContext(Dispatchers.Main.immediate) {
                finishList(envelope, result)
            }
        }
        val watchdog = authControlScope.launch {
            delay(AUTH_OPERATION_TIMEOUT_MILLIS)
            val plan = withContext(Dispatchers.Main.immediate) {
                authCoordinator.planFailure(
                    envelope.key,
                    AuthOperationKind.LIST,
                    ServiceAuthFailure.TIMEOUT,
                    AUTH_LIST_FAILED,
                )
            } ?: return@launch
            val jobsToDrain = withContext(Dispatchers.Main.immediate) {
                authOperationJobs.values.flatMap { jobs ->
                    listOfNotNull(jobs.worker, jobs.watchdog)
                }.also { authOperationJobs.clear() }
            }
            ReadReceiptsTunnelNative.cancelLogin()
            val watchdogJob = currentCoroutineContext()[Job]
            val otherJobs = jobsToDrain.filterNot { it === watchdogJob }
            otherJobs.forEach(Job::cancel)
            for (job in otherJobs) job.join()
            withContext(Dispatchers.Main.immediate) {
                nativeAuthGeneration = 0
                clearTransientAuthState()
                check(authCoordinator.finishFailure(plan))
                broadcastAuthSnapshot()
            }
        }
        authOperationJobs[envelope.key] = AuthOperationJobs(worker, watchdog)
        worker.start()
    }

    private fun finishList(
        envelope: ServiceAuthEnvelope,
        result: Result<NativeExistingTunnelList>,
    ) {
        if (!authCoordinator.canPublish(envelope.key, AuthOperationKind.LIST)) return
        val native = result.getOrNull()
        val login = authLoginState
        if (
            native == null || login == null || native.generation != nativeAuthGeneration
        ) {
            val plan = checkNotNull(
                authCoordinator.planFailure(
                    envelope.key,
                    AuthOperationKind.LIST,
                    ServiceAuthFailure.SESSION_BROKEN,
                    AUTH_LIST_FAILED,
                ),
            )
            scheduleBrokenListTeardown(plan)
            return
        }
        val jobs = authOperationJobs.remove(envelope.key)
        jobs?.watchdog?.cancel()
        if (
            native.error != null ||
            !AuthSnapshotBounds.isValid(
                login,
                authAccountId,
                native.tunnels,
                cachedCredentialMetadata,
            )
        ) {
            val plan = checkNotNull(
                authCoordinator.planFailure(
                    envelope.key,
                    AuthOperationKind.LIST,
                    ServiceAuthFailure.API_RETURNED,
                    AUTH_LIST_FAILED,
                ),
            )
            check(authCoordinator.finishFailure(plan))
            return
        }
        authTunnels = Collections.unmodifiableList(ArrayList(native.tunnels))
        broadcastAuthSnapshot()
        check(
            authCoordinator.complete(
                envelope.key,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(authTunnels),
            ),
        )
    }

    private fun scheduleBrokenListTeardown(
        plan: ServiceAuthFailurePlan<List<ExistingTunnel>>,
    ) {
        val jobsToDrain = authOperationJobs.values.flatMap { jobs ->
            listOfNotNull(jobs.worker, jobs.watchdog)
        } + listOfNotNull(authPollJob)
        authOperationJobs.clear()
        authPollJob = null
        authCleanupJob = authControlScope.launch {
            runCatching { ReadReceiptsTunnelNative.cancelLogin() }
            jobsToDrain.forEach(Job::cancel)
            for (job in jobsToDrain) job.join()
            runCatching { ReadReceiptsTunnelNative.cancelLogin() }
            withContext(Dispatchers.Main.immediate) {
                authCleanupJob = null
                nativeAuthGeneration = 0
                clearTransientAuthState()
                check(authCoordinator.finishFailure(plan))
                broadcastAuthSnapshot()
            }
        }
    }

    private fun clearLogin(
        envelope: ServiceAuthEnvelope,
        kind: AuthOperationKind<Unit>,
    ) {
        val admission = authCoordinator.admit(
            envelope.key,
            kind,
            ServiceAuthOperationPhase.NATIVE_BLOCKING,
        ) { terminal -> sendAuthTerminal(envelope, terminal) }
        if (admission is ServiceAuthAdmission.Rejected) {
            rejectAuthAdmission(envelope, admission)
            return
        }
        check(authCoordinator.claimAck(envelope.key, kind))
        sendAuthAck(envelope, accepted = true)
        val plan = checkNotNull(
            authCoordinator.planSessionClear(
                envelope.key,
                kind,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        val previousJobs = authOperationJobs.values.flatMap { jobs ->
            listOfNotNull(jobs.worker, jobs.watchdog)
        } + listOfNotNull(authPollJob, authCleanupJob)
        authOperationJobs.clear()
        authPollJob = null
        authCleanupJob = authControlScope.launch {
            var cleaned = ReadReceiptsTunnelNative.cancelLogin().isSuccess
            previousJobs.forEach(Job::cancel)
            for (job in previousJobs) job.join()
            cleaned = ReadReceiptsTunnelNative.cancelLogin().isSuccess && cleaned
            withContext(Dispatchers.Main.immediate) {
                authCleanupJob = null
                nativeAuthGeneration = 0
                clearTransientAuthState()
                check(authCoordinator.finishSessionClear(plan, restartRequired = !cleaned))
                broadcastAuthSnapshot(resetClientExpectation = true)
            }
        }
    }

    private fun rejectAuthAdmission(
        envelope: ServiceAuthEnvelope,
        rejection: ServiceAuthAdmission.Rejected,
    ) {
        sendAuthAck(envelope, accepted = false)
        val terminal = when (rejection.reason) {
            ServiceAuthRejectReason.STALE_GENERATION,
            ServiceAuthRejectReason.DUPLICATE_REQUEST,
            -> AuthOperationTerminal.Superseded
            ServiceAuthRejectReason.SESSION_UNAVAILABLE,
            ServiceAuthRejectReason.INVALID_KIND,
            -> AuthOperationTerminal.Failed(AUTH_REJECTED)
        }
        sendAuthTerminal(envelope, terminal)
    }

    private fun clearTransientAuthState() {
        authLoginState = null
        authAccountId = ""
        authTunnels = emptyList()
    }

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
        mainHandler.post { applyCredentialCacheUpdate(update) }
    }

    private fun applyCredentialCacheUpdate(update: CredentialCacheUpdate) {
        if (update.revision < appliedCredentialRevision) return
        appliedCredentialRevision = update.revision
        cachedCredentialMetadata = update.metadata
        cachedCredentialExists = update.metadata != null
        credentialMetadataLoading = false
        broadcastAuthSnapshot()
        listeners.values.forEach(::sendStatus)
    }

    private fun handleStart(data: Bundle, client: Messenger?) {
        val requestedGeneration = data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION)
        val nonce = data.getString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE)
        val suppliedToken = data.getString(ReadReceiptsTunnelProtocol.KEY_TOKEN)
        data.remove(ReadReceiptsTunnelProtocol.KEY_TOKEN)
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
            sendStartAck(client, nonce, requestedGeneration, accepted = false)
            return
        }
        if (!foregroundActive) {
            publish(
                requestedGeneration,
                ReadReceiptsTunnelStatus(
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    error = "请从可见设置界面启动前台隧道",
                ),
            )
            sendStartAck(client, nonce, requestedGeneration, accepted = false)
            return
        }
        if (!notificationsVisible()) {
            activeRequest = null
            replaceLifecycle(requestedGeneration, null)
            publish(
                requestedGeneration,
                ReadReceiptsTunnelStatus(
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    error = "WeKit 通知已关闭, 请在系统设置中允许通知后重试",
                    needsNotificationSettings = true,
                ),
            )
            sendStartAck(
                client,
                nonce,
                requestedGeneration,
                accepted = false,
                needsNotificationSettings = true,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
            stopSelf()
            return
        }
        val mode = data.getString(ReadReceiptsTunnelProtocol.KEY_MODE)
            ?.let { name -> ReadReceiptsTunnelMode.entries.firstOrNull { it.name == name } }
        val origin = data.getString(ReadReceiptsTunnelProtocol.KEY_ORIGIN).orEmpty()
        val hostname = data.getString(ReadReceiptsTunnelProtocol.KEY_HOSTNAME).orEmpty()

        if (mode == null) {
            rejectStart(requestedGeneration, client, nonce, "隧道模式无效")
            return
        }
        if (mode == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
            activeRequest = null
            replaceLifecycle(requestedGeneration, null)
            publish(
                requestedGeneration,
                ReadReceiptsTunnelStatus(
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    error = "浏览器登录将在下一阶段提供",
                ),
            )
            sendStartAck(client, nonce, requestedGeneration, accepted = false)
            return
        }
        val publicRoot = if (mode == ReadReceiptsTunnelMode.TOKEN) {
            normalizePublicRoot(hostname) ?: run {
                rejectStart(
                    requestedGeneration,
                    client,
                    nonce,
                    "Token 模式需要根路径 HTTPS 主机名",
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
            rejectStart(requestedGeneration, client, nonce, "Tunnel token 无效")
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
                    error = "请提供 Cloudflare Tunnel token",
                ),
            )
            activeRequest = null
            replaceLifecycle(requestedGeneration, null)
            sendStartAck(client, nonce, requestedGeneration, accepted = false)
            return
        }

        val request = TunnelRequest(requestedGeneration, mode, origin, publicRoot, suppliedToken)
        activeRequest = request
        check(nativeLease.activateRequest(requestedGeneration))
        replaceLifecycle(requestedGeneration, request)
        // The authorized service has copied the request and removed the secret from the Binder
        // Bundle. Connector/public-health success remains asynchronous service-owned status.
        sendStartAck(client, nonce, requestedGeneration, accepted = true)
    }

    private fun rejectStart(
        requestedGeneration: Long,
        client: Messenger?,
        nonce: String?,
        message: String,
    ) {
        activeRequest = null
        replaceLifecycle(requestedGeneration, null)
        publishFailure(requestedGeneration, message)
        sendStartAck(client, nonce, requestedGeneration, accepted = false)
    }

    /** Captures the one real predecessor exactly once; this job is the sole lifecycle successor. */
    private fun replaceLifecycle(generation: Long, request: TunnelRequest?) {
        val previous = lifecycleJob
        lifecycleJob = scope.launch {
            previous?.cancel()
            previous?.join()
            nativeLease.stopForReplacement(generation) {
                ReadReceiptsTunnelNative.stop().getOrThrow()
            }
            if (request != null && activeRequest?.generation == generation) runTunnel(request)
        }
    }

    private suspend fun runTunnel(request: TunnelRequest) {
        publish(request.generation, ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING))
        val originRoot = normalizeLoopbackRoot(request.origin)
        if (originRoot == null || !checkHealth(originRoot)) {
            publishFailure(request.generation, "内置服务器健康检查失败")
            return
        }

        var attempt = 0
        while (scope.isActive && activeRequest?.generation == request.generation) {
            while (!networkAvailable && activeRequest?.generation == request.generation) {
                publish(
                    request.generation,
                    ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.RECONNECTING),
                )
                delay(NETWORK_POLL_MILLIS)
            }
            currentCoroutineContext().ensureActive()

            val token = if (request.mode == ReadReceiptsTunnelMode.TOKEN) {
                request.pendingToken ?: readCredentialOnIo().getOrElse {
                    publish(
                        request.generation,
                        ReadReceiptsTunnelStatus(
                            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                            error = "保存的 Tunnel token 已失效, 请重新输入",
                        ),
                    )
                    return
                }.runToken
            } else {
                null
            }
            val started = nativeLease.startIfCurrent(request.generation) {
                val startResult = when (request.mode) {
                    ReadReceiptsTunnelMode.QUICK ->
                        ReadReceiptsTunnelNative.startQuick(request.origin)
                    ReadReceiptsTunnelMode.TOKEN ->
                        ReadReceiptsTunnelNative.startToken(token!!, request.origin)
                    ReadReceiptsTunnelMode.BROWSER_LOGIN -> error("unreachable")
                }
                startResult.isSuccess
            }
            if (!started) {
                if (activeRequest?.generation != request.generation) return
                publishFailure(request.generation, "Cloudflare Tunnel 启动失败")
                return
            }

            var terminalError: String? = null
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
                        val verification = nativeLease.captureVerification(request.generation)
                        if (verification == null) {
                            delay(NATIVE_STATUS_POLL_MILLIS)
                            continue
                        }
                        val candidate = request.publicRoot ?: normalizePublicRoot(native.publicUrl.orEmpty())
                        if (candidate == null) {
                            terminalError = "Cloudflare 未返回有效的公网地址"
                            break
                        }
                        val needsHealthCheck = verifiedRoot != candidate ||
                            verifiedNetworkEpoch != verification.networkEpoch ||
                            SystemClock.elapsedRealtime() - lastPublicHealthAt >=
                            PUBLIC_HEALTH_RECHECK_MILLIS
                        if (!needsHealthCheck || checkHealth(candidate)) {
                            val pendingToken = request.pendingToken
                            when (
                                nativeLease.commitVerification(
                                    verification,
                                    writeCredential = pendingToken?.let { token ->
                                        {
                                            val payload = TunnelCredentialPayload.create(
                                                runToken = token,
                                                source = TunnelCredentialSource.TOKEN,
                                                canonicalHostname = candidate.toString().trimEnd('/'),
                                                fixedOriginPort = originRoot.port,
                                            )
                                            payload != null && writeCredentialOnIo(payload)
                                        }
                                    },
                                    clearPendingToken = pendingToken?.let {
                                        { request.pendingToken = null }
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
                                    nativeLease.runIfVerificationCurrent(verification) {
                                        publish(
                                            request.generation,
                                            ReadReceiptsTunnelStatus(
                                                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                                                error = "隧道已验证, 但无法安全保存 Tunnel token",
                                            ),
                                        )
                                    }
                                    nativeLease.stopIfOwner(request.generation) {
                                        ReadReceiptsTunnelNative.stop().getOrThrow()
                                    }
                                    return
                                }
                                TunnelVerificationCommit.STALE -> {
                                    delay(NATIVE_STATUS_POLL_MILLIS)
                                    continue
                                }
                                TunnelVerificationCommit.COMMITTED -> Unit
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
                                terminalError = "公网 /health 验证失败, 请检查主机名与 ingress"
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
                        publish(request.generation, native.copy(publicUrl = null, error = null))
                    }
                    ReadReceiptsTunnelState.FAILED -> {
                        terminalError = native.error ?: "Cloudflare Tunnel 连接失败"
                        break
                    }
                    ReadReceiptsTunnelState.STOPPED -> {
                        terminalError = "Cloudflare Tunnel 连接已断开"
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
                publishFailure(request.generation, terminalError!!)
                return
            }

            attempt++
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                publishFailure(request.generation, terminalError ?: "Cloudflare Tunnel 重连失败")
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
            stopSelf()
        }
    }

    private fun deleteCredential(requestedGeneration: Long) {
        scope.launch {
            var capturedState: TunnelNativeSessionState? = null
            var update: CredentialCacheUpdate? = null
            val accepted = nativeLease.withCurrentGeneration(requestedGeneration) { sessionState ->
                update = clearCredentialOnIo()
                capturedState = sessionState
            }
            if (!accepted) return@launch
            withContext(Dispatchers.Main.immediate) {
                applyCredentialCacheUpdate(update!!)
                if (activeRequest?.mode == ReadReceiptsTunnelMode.TOKEN) {
                    stopTunnel(requestedGeneration)
                } else {
                    publish(
                        requestedGeneration,
                        status.forAdministrativePublish(capturedState!!),
                    )
                }
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

    private fun publishFailure(expectedGeneration: Long, message: String) {
        publish(
            expectedGeneration,
            ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.FAILED,
                error = message.take(MAX_ERROR_CHARS),
            ),
        )
    }

    private fun publish(expectedGeneration: Long, value: ReadReceiptsTunnelStatus) {
        val sanitized = value.copy(
            publicUrl = value.publicUrl?.take(MAX_URL_CHARS),
            error = value.error?.take(MAX_ERROR_CHARS),
        )
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
        updateNotification()
        listeners.values.forEach(::sendStatus)
    }

    private fun sendStatus(listener: StatusListener) {
        val snapshot = authoritativeState.get()
        val value = snapshot.status
        val message = Message.obtain(null, ReadReceiptsTunnelProtocol.STATUS).apply {
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, snapshot.generation)
                putString(ReadReceiptsTunnelProtocol.KEY_STATE, value.state.name)
                putString(ReadReceiptsTunnelProtocol.KEY_PUBLIC_URL, value.publicUrl)
                putString(ReadReceiptsTunnelProtocol.KEY_ERROR, value.error)
                putBoolean(ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_EXISTS, cachedCredentialExists)
                putBoolean(
                    ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS,
                    value.needsNotificationSettings,
                )
                putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, listener.nonce)
            }
        }
        runCatching { listener.messenger.send(message) }
            .onFailure { listeners.remove(listener.messenger.binder) }
    }

    private fun broadcastAuthSnapshot(resetClientExpectation: Boolean = false) {
        if (resetClientExpectation) {
            listeners.values.forEach { it.lastSeenAuthGeneration = 0 }
        }
        authSnapshotRevision++
        listeners.values.forEach(::sendAuthSnapshot)
    }

    private fun sendAuthSnapshot(listener: StatusListener) {
        val coordinatorSnapshot = authCoordinator.snapshot()
        val login = authLoginState ?: CloudflareLoginState(
            authorizationUrl = null,
            state = ReadReceiptsTunnelState.STOPPED,
            error = null,
        )
        if (
            !AuthSnapshotBounds.isValid(
                login,
                authAccountId,
                authTunnels,
                cachedCredentialMetadata,
            )
        ) {
            return
        }
        val metadata = cachedCredentialMetadata
        val publishedAuthGeneration = if (nativeAuthGeneration != 0L) {
            coordinatorSnapshot.authGeneration
        } else {
            0L
        }
        val beginInProgress =
            coordinatorSnapshot.phase == ServiceAuthSessionPhase.REPLACING ||
                coordinatorSnapshot.phase == ServiceAuthSessionPhase.WAITING &&
                authLoginState == null
        val restartRequired =
            coordinatorSnapshot.phase == ServiceAuthSessionPhase.RESTART_REQUIRED ||
                !beginInProgress &&
                publishedAuthGeneration == 0L &&
                listener.lastSeenAuthGeneration > 0
        val message = Message.obtain(null, ReadReceiptsTunnelProtocol.AUTH_SNAPSHOT).apply {
            data = Bundle().apply {
                putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, listener.nonce)
                putLong(
                    ReadReceiptsTunnelProtocol.KEY_AUTH_SNAPSHOT_REVISION,
                    authSnapshotRevision,
                )
                putLong(
                    ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION,
                    publishedAuthGeneration,
                )
                putBoolean(ReadReceiptsTunnelProtocol.KEY_AUTH_RESTART_REQUIRED, restartRequired)
                putString(ReadReceiptsTunnelProtocol.KEY_STATE, login.state.name)
                putString(
                    ReadReceiptsTunnelProtocol.KEY_AUTHORIZATION_URL,
                    login.authorizationUrl,
                )
                putString(ReadReceiptsTunnelProtocol.KEY_ERROR, login.error)
                putString(ReadReceiptsTunnelProtocol.KEY_ACCOUNT_ID, authAccountId)
                putParcelableArrayList(
                    ReadReceiptsTunnelProtocol.KEY_TUNNELS,
                    tunnelBundles(authTunnels),
                )
                putBoolean(
                    ReadReceiptsTunnelProtocol.KEY_METADATA_LOADING,
                    credentialMetadataLoading,
                )
                putString(
                    ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_SOURCE,
                    metadata?.source?.name,
                )
                putString(
                    ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_ACCOUNT_ID,
                    metadata?.accountId,
                )
                putString(
                    ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_TUNNEL_ID,
                    metadata?.tunnelId,
                )
                putString(
                    ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_TUNNEL_NAME,
                    metadata?.tunnelName,
                )
                putString(
                    ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_HOSTNAME,
                    metadata?.canonicalHostname,
                )
                putInt(
                    ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_ORIGIN_PORT,
                    metadata?.fixedOriginPort ?: 0,
                )
            }
        }
        runCatching { listener.messenger.send(message) }
            .onFailure { listeners.remove(listener.messenger.binder) }
    }

    private fun sendAuthAck(
        envelope: ServiceAuthEnvelope,
        accepted: Boolean,
    ) {
        val message = Message.obtain(null, ReadReceiptsTunnelProtocol.AUTH_ACK).apply {
            data = authIdentityBundle(envelope).apply {
                putBoolean(ReadReceiptsTunnelProtocol.KEY_ACCEPTED, accepted)
            }
        }
        runCatching { envelope.listener.messenger.send(message) }
            .onFailure { listeners.remove(envelope.listener.messenger.binder) }
    }

    private fun sendAuthTerminal(
        envelope: ServiceAuthEnvelope,
        terminal: AuthOperationTerminal<*>,
    ) {
        val message = Message.obtain(null, ReadReceiptsTunnelProtocol.AUTH_TERMINAL).apply {
            data = authIdentityBundle(envelope).apply {
                val terminalName = when (terminal) {
                    is AuthOperationTerminal.Completed<*> -> "COMPLETED"
                    is AuthOperationTerminal.Failed -> "FAILED"
                    AuthOperationTerminal.Superseded -> "SUPERSEDED"
                    AuthOperationTerminal.TimedOut -> "TIMED_OUT"
                    AuthOperationTerminal.Cancelled -> "CANCELLED"
                }
                putString(ReadReceiptsTunnelProtocol.KEY_AUTH_TERMINAL, terminalName)
                if (terminal is AuthOperationTerminal.Failed) {
                    putString(ReadReceiptsTunnelProtocol.KEY_ERROR, terminal.error)
                }
                if (terminal is AuthOperationTerminal.Completed<*>) {
                    when (envelope.wireKind) {
                        ServiceAuthWireKind.BEGIN -> {
                            val value = terminal.value as CloudflareLoginState
                            putString(ReadReceiptsTunnelProtocol.KEY_STATE, value.state.name)
                            putString(
                                ReadReceiptsTunnelProtocol.KEY_AUTHORIZATION_URL,
                                value.authorizationUrl,
                            )
                        }
                        ServiceAuthWireKind.LIST -> {
                            @Suppress("UNCHECKED_CAST")
                            val tunnels = terminal.value as List<ExistingTunnel>
                            putParcelableArrayList(
                                ReadReceiptsTunnelProtocol.KEY_TUNNELS,
                                tunnelBundles(tunnels),
                            )
                        }
                        ServiceAuthWireKind.CANCEL,
                        ServiceAuthWireKind.LOGOUT,
                        -> check(terminal.value === Unit)

                        ServiceAuthWireKind.SELECT -> error("SELECT is not implemented")
                    }
                }
            }
        }
        runCatching { envelope.listener.messenger.send(message) }
            .onFailure { listeners.remove(envelope.listener.messenger.binder) }
    }

    private fun authIdentityBundle(envelope: ServiceAuthEnvelope): Bundle = Bundle().apply {
        putLong(ReadReceiptsTunnelProtocol.KEY_AUTH_GENERATION, envelope.key.authGeneration)
        putLong(ReadReceiptsTunnelProtocol.KEY_AUTH_REQUEST_ID, envelope.key.requestId)
        putString(ReadReceiptsTunnelProtocol.KEY_AUTH_KIND, envelope.wireKind.wireName)
        putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, envelope.listener.nonce)
    }

    private fun tunnelBundles(tunnels: List<ExistingTunnel>): ArrayList<Bundle> =
        ArrayList(tunnels.map { tunnel ->
            Bundle().apply {
                putString(ReadReceiptsTunnelProtocol.KEY_TUNNEL_ID, tunnel.id)
                putString(ReadReceiptsTunnelProtocol.KEY_TUNNEL_NAME, tunnel.name)
                putStringArrayList(
                    ReadReceiptsTunnelProtocol.KEY_HOSTNAMES,
                    ArrayList(tunnel.hostnames),
                )
            }
        })

    private fun sendStartAck(
        client: Messenger?,
        nonce: String?,
        generation: Long,
        accepted: Boolean,
        needsNotificationSettings: Boolean = false,
    ) {
        if (client == null || nonce == null) return
        val message = Message.obtain(null, ReadReceiptsTunnelProtocol.START_ACK).apply {
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, generation)
                putBoolean(ReadReceiptsTunnelProtocol.KEY_ACCEPTED, accepted)
                putBoolean(
                    ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS,
                    needsNotificationSettings,
                )
                putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, nonce)
            }
        }
        runCatching { client.send(message) }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "已读追踪公网隧道",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notificationsVisible(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            getSystemService(NotificationManager::class.java)
                .getNotificationChannel(NOTIFICATION_CHANNEL)
                ?.importance
                ?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    private fun updateNotification() {
        if (!authorizedCommandSeen || !foregroundActive) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(value: ReadReceiptsTunnelStatus): Notification {
        val stopIntent = Intent(this, ReadReceiptsTunnelService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_STOP_NONCE, notificationStopNonce)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = value.publicUrl?.toHttpUrlOrNull()?.host ?: when (value.state) {
            ReadReceiptsTunnelState.STOPPED -> "已停止"
            ReadReceiptsTunnelState.STARTING -> "正在启动"
            ReadReceiptsTunnelState.CONNECTED -> "已连接"
            ReadReceiptsTunnelState.RECONNECTING -> "正在重连"
            ReadReceiptsTunnelState.NEEDS_USER_ACTION -> "需要用户操作"
            ReadReceiptsTunnelState.FAILED -> "连接失败"
            ReadReceiptsTunnelState.STOPPING -> "正在停止"
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("WeKit 已读追踪隧道")
            .setContentText(detail.take(128))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止", stopPendingIntent)
            .build()
    }

    private data class TunnelRequest(
        val generation: Long,
        val mode: ReadReceiptsTunnelMode,
        val origin: String,
        val publicRoot: HttpUrl?,
        var pendingToken: String?,
    )

    private data class StatusListener(
        val messenger: Messenger,
        val nonce: String,
        var lastSeenAuthGeneration: Long,
    )

    private data class AuthRegistration(
        val client: Messenger,
        val nonce: String,
        val lastSeenAuthGeneration: Long,
    )

    private data class DeferredAuthSelection(
        val tunnelId: String,
        val canonicalRoot: String,
        val fixedOriginPort: Int,
        val connectorGeneration: Long,
    )

    private data class ServiceAuthEnvelope(
        val listener: StatusListener,
        val key: AuthOperationKey,
        val wireKind: ServiceAuthWireKind,
        val selection: DeferredAuthSelection?,
    )

    private data class AuthOperationJobs(
        val worker: Job,
        val watchdog: Job?,
    )

    private data class CredentialCacheUpdate(
        val revision: Long,
        val metadata: CommittedTunnelCredentialMetadata?,
    )

    private enum class ServiceAuthWireKind(val wireName: String) {
        BEGIN("BEGIN"),
        LIST("LIST"),
        SELECT("SELECT"),
        CANCEL("CANCEL"),
        LOGOUT("LOGOUT"),
    }

    private data class AuthoritativeTunnelState(
        val generation: Long,
        val status: ReadReceiptsTunnelStatus,
    )

    companion object {
        private val processAuthTeardownLock = Any()
        private var processAuthTeardown: CompletableDeferred<Unit>? = null

        private fun registerProcessAuthTeardown(): Pair<Deferred<Unit>?, CompletableDeferred<Unit>> =
            synchronized(processAuthTeardownLock) {
                val prior = processAuthTeardown
                val current = CompletableDeferred<Unit>()
                processAuthTeardown = current
                prior to current
            }

        private fun captureProcessAuthTeardown(): Deferred<Unit>? =
            synchronized(processAuthTeardownLock) { processAuthTeardown }

        private fun clearProcessAuthTeardown(completed: CompletableDeferred<Unit>) {
            synchronized(processAuthTeardownLock) {
                if (processAuthTeardown === completed) processAuthTeardown = null
            }
        }

        const val ACTION_START = "dev.ujhhgtg.wekit.action.START_READ_RECEIPTS_TUNNEL"
        const val ACTION_STOP = "dev.ujhhgtg.wekit.action.STOP_READ_RECEIPTS_TUNNEL"
        private const val EXTRA_STOP_NONCE = "notification_stop_nonce"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val NOTIFICATION_CHANNEL = "read_receipts_tunnel"
        private const val NOTIFICATION_ID = 0x574b52
        private const val AUTHORIZATION_TIMEOUT_MILLIS = 10_000L
        private const val NATIVE_STATUS_POLL_MILLIS = 500L
        private const val NETWORK_POLL_MILLIS = 1_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val MAX_PUBLIC_HEALTH_ATTEMPTS = 12
        private const val PUBLIC_HEALTH_RECHECK_MILLIS = 15_000L
        private const val MAX_TOKEN_CHARS = 16 * 1024
        private const val MAX_URL_CHARS = 2048
        private const val MAX_ERROR_CHARS = 256
        private const val AUTH_OPERATION_TIMEOUT_MILLIS = 30_000L
        private const val AUTH_LOGIN_POLL_LIMIT = 1_200
        private const val AUTH_SELECT_DEFERRED = "当前版本尚未启用隧道选择"
        private const val AUTH_REJECTED = "认证请求已被拒绝"
        private const val AUTH_BEGIN_FAILED = "无法启动 Cloudflare 登录"
        private const val AUTH_LIST_FAILED = "无法读取 Cloudflare Tunnel 列表"
        private const val AUTH_CLEANUP_FAILED = "认证清理未完成，请重新启动登录"
        private val RECONNECT_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)

        internal fun normalizePublicRoot(value: String): HttpUrl? {
            if (value.isBlank() || value != value.trim() || value.any(Char::isWhitespace)) return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (
                url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
                url.password.isNotEmpty() || url.query != null || url.fragment != null ||
                url.encodedPath != "/" || url.host.length > 253 ||
                !url.host.contains('.') || url.host.contains(':') ||
                url.host.all { it.isDigit() || it == '.' }
            ) {
                return null
            }
            return url
        }

        internal fun canonicalPublicRoot(value: String): String? =
            normalizePublicRoot(value)?.toString()?.trimEnd('/')

        internal fun normalizeLoopbackRoot(value: String): HttpUrl? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (
                url.scheme != "http" || url.host !in setOf("127.0.0.1", "localhost", "[::1]") ||
                url.encodedPath != "/" || url.query != null || url.fragment != null
            ) {
                return null
            }
            return url
        }
    }
}

internal object ReadReceiptsTunnelProtocol {
    const val REGISTER = 1
    const val START = 2
    const val STOP = 3
    const val DELETE_CREDENTIAL = 4
    const val BEGIN_LOGIN = 5
    const val LIST_TUNNELS = 6
    const val SELECT_TUNNEL = 7
    const val CANCEL_LOGIN = 8
    const val LOGOUT = 9
    const val STATUS = 100
    const val START_ACK = 101
    const val AUTH_ACK = 102
    const val AUTH_TERMINAL = 103
    const val AUTH_SNAPSHOT = 104

    const val KEY_GENERATION = "generation"
    const val KEY_MODE = "mode"
    const val KEY_ORIGIN = "origin"
    const val KEY_HOSTNAME = "hostname"
    const val KEY_TOKEN = "token"
    const val KEY_STATE = "state"
    const val KEY_PUBLIC_URL = "public_url"
    const val KEY_ERROR = "error"
    const val KEY_CREDENTIAL_EXISTS = "credential_exists"
    const val KEY_CLIENT_NONCE = "client_nonce"
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEEDS_NOTIFICATION_SETTINGS = "needs_notification_settings"
    const val KEY_LAST_SEEN_AUTH_GENERATION = "last_seen_auth_generation"
    const val KEY_AUTH_GENERATION = "auth_generation"
    const val KEY_AUTH_REQUEST_ID = "auth_request_id"
    const val KEY_AUTH_KIND = "auth_kind"
    const val KEY_AUTH_TERMINAL = "auth_terminal"
    const val KEY_AUTH_SNAPSHOT_REVISION = "auth_snapshot_revision"
    const val KEY_AUTH_RESTART_REQUIRED = "auth_restart_required"
    const val KEY_AUTHORIZATION_URL = "authorization_url"
    const val KEY_ACCOUNT_ID = "account_id"
    const val KEY_TUNNELS = "tunnels"
    const val KEY_TUNNEL_ID = "tunnel_id"
    const val KEY_TUNNEL_NAME = "tunnel_name"
    const val KEY_HOSTNAMES = "hostnames"
    const val KEY_FIXED_ORIGIN_PORT = "fixed_origin_port"
    const val KEY_CONNECTOR_GENERATION = "connector_generation"
    const val KEY_METADATA_LOADING = "metadata_loading"
    const val KEY_CREDENTIAL_SOURCE = "credential_source"
    const val KEY_CREDENTIAL_ACCOUNT_ID = "credential_account_id"
    const val KEY_CREDENTIAL_TUNNEL_ID = "credential_tunnel_id"
    const val KEY_CREDENTIAL_TUNNEL_NAME = "credential_tunnel_name"
    const val KEY_CREDENTIAL_HOSTNAME = "credential_hostname"
    const val KEY_CREDENTIAL_ORIGIN_PORT = "credential_origin_port"

    val REGISTER_KEYS = setOf(KEY_CLIENT_NONCE, KEY_LAST_SEEN_AUTH_GENERATION)
    val AUTH_OPERATION_KEYS = setOf(
        KEY_AUTH_GENERATION,
        KEY_AUTH_REQUEST_ID,
        KEY_AUTH_KIND,
        KEY_CLIENT_NONCE,
    )
    val SELECT_OPERATION_KEYS = setOf(
        KEY_TUNNEL_ID,
        KEY_HOSTNAME,
        KEY_FIXED_ORIGIN_PORT,
        KEY_CONNECTOR_GENERATION,
    )
}

private class TunnelCredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_PATH))

    fun exists(): Boolean = file.baseFile.isFile

    fun write(credential: TunnelCredentialPayload): Result<Unit> = runCatching {
        var plaintext: ByteArray? = null
        var iv: ByteArray? = null
        var encrypted: ByteArray? = null
        var filePayload: ByteArray? = null
        try {
            plaintext = TunnelCredentialPayloadCodec.encode(credential)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            iv = cipher.iv
            encrypted = cipher.doFinal(plaintext)
            filePayload = listOf(
                VERSION,
                Base64.encodeToString(iv, Base64.NO_WRAP),
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ).joinToString("\n").toByteArray(Charsets.US_ASCII)
            require(filePayload.size <= MAX_FILE_BYTES)
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try {
                output.write(filePayload)
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        } finally {
            plaintext?.fill(0)
            iv?.fill(0)
            encrypted?.fill(0)
            filePayload?.fill(0)
        }
    }

    fun read(): Result<TunnelCredentialPayload> = runCatching {
        var filePayload: ByteArray? = null
        var iv: ByteArray? = null
        var encrypted: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            filePayload = readFileBytes()
            val envelope = filePayload.toString(Charsets.US_ASCII).split('\n')
            require(
                envelope.size == 3 &&
                    (envelope[0] == VERSION || envelope[0] == LEGACY_VERSION),
            )
            iv = Base64.decode(envelope[1], Base64.NO_WRAP)
            encrypted = Base64.decode(envelope[2], Base64.NO_WRAP)
            require(iv.size == IV_BYTES)
            require(encrypted.size in GCM_TAG_BYTES..TunnelCredentialPayloadCodec.MAX_BYTES + GCM_TAG_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            plaintext = cipher.doFinal(encrypted)
            val decoded = TunnelCredentialPayloadCodec.decode(plaintext)
            require(decoded is TunnelCredentialDecode.Decoded)
            decoded.payload
        } finally {
            filePayload?.fill(0)
            iv?.fill(0)
            encrypted?.fill(0)
            plaintext?.fill(0)
        }
    }.onFailure { clear() }

    fun readMetadata(): Result<CommittedTunnelCredentialMetadata> =
        read().map(TunnelCredentialPayload::committedMetadata)

    fun clear() {
        file.delete()
    }

    private fun readFileBytes(): ByteArray {
        val scratch = ByteArray(MAX_FILE_BYTES + 1)
        try {
            var size = 0
            file.openRead().use { input ->
                while (size < scratch.size) {
                    val count = input.read(scratch, size, scratch.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size <= MAX_FILE_BYTES && input.read() < 0)
            }
            return scratch.copyOf(size)
        } finally {
            scratch.fill(0)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE_PATH = "read_receipts/tunnel_credential.v1"
        private const val VERSION = "2"
        private const val LEGACY_VERSION = "1"
        private const val MAX_FILE_BYTES = 64 * 1024
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val KEY_ALIAS = "wekit_read_receipts_tunnel_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
