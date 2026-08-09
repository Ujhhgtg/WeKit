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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
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
    private val listeners = ConcurrentHashMap<IBinder, StatusListener>()
    private val messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
    private val credentialStore by lazy { TunnelCredentialStore(this) }
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
        scope.cancel()
        ReadReceiptsTunnelNative.stop()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        httpClient.dispatcher.cancelAll()
        super.onDestroy()
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (!isAuthorizedUid(message.sendingUid)) return
            authorizedCommandSeen = true
            authorizationTimeout?.cancel()
            when (message.what) {
                ReadReceiptsTunnelProtocol.REGISTER -> register(
                    message.replyTo,
                    message.data.getString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE),
                )
                ReadReceiptsTunnelProtocol.START -> handleStart(
                    message.data,
                    message.replyTo,
                )
                ReadReceiptsTunnelProtocol.STOP -> stopTunnel(
                    message.data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION),
                )
                ReadReceiptsTunnelProtocol.DELETE_CREDENTIAL -> deleteCredential(
                    message.data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION),
                )
                else -> super.handleMessage(message)
            }
        }
    }

    private fun isAuthorizedUid(uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        return packageManager.getPackagesForUid(uid)?.contains(WECHAT_PACKAGE) == true
    }

    private fun register(client: Messenger?, nonce: String?) {
        if (client == null || nonce == null || nonce.length !in 16..128) return
        val listener = StatusListener(client, nonce)
        listeners[client.binder] = listener
        sendStatus(listener)
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
            !credentialStore.exists()
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
                request.pendingToken ?: credentialStore.read().getOrElse {
                    publish(
                        request.generation,
                        ReadReceiptsTunnelStatus(
                            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                            error = "保存的 Tunnel token 已失效, 请重新输入",
                        ),
                    )
                    return
                }
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
                                        { credentialStore.write(token).isSuccess }
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
        nativeLease.withCurrentGeneration(requestedGeneration) { sessionState ->
            credentialStore.clear()
            if (activeRequest?.mode == ReadReceiptsTunnelMode.TOKEN) {
                stopTunnel(requestedGeneration)
            } else {
                publish(requestedGeneration, status.forAdministrativePublish(sessionState))
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
                putBoolean(ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_EXISTS, credentialStore.exists())
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
    )

    private data class AuthoritativeTunnelState(
        val generation: Long,
        val status: ReadReceiptsTunnelStatus,
    )

    companion object {
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
    const val STATUS = 100
    const val START_ACK = 101

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
}

private class TunnelCredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_PATH))

    fun exists(): Boolean = file.baseFile.isFile

    fun write(token: String): Result<Unit> = runCatching {
        require(token.length in 1..MAX_TOKEN_CHARS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload = listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString("\n").toByteArray(Charsets.US_ASCII)
        require(payload.size <= MAX_FILE_BYTES)
        file.baseFile.parentFile!!.mkdirs()
        val output = file.startWrite()
        try {
            output.write(payload)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    fun read(): Result<String> = runCatching {
        val payload = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }.toString(Charsets.US_ASCII).split('\n')
        require(payload.size == 3 && payload[0] == VERSION)
        val iv = Base64.decode(payload[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(payload[2], Base64.NO_WRAP)
        require(iv.size == 12 && encrypted.size <= MAX_TOKEN_CHARS + 32)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8).also {
            require(it.length in 1..MAX_TOKEN_CHARS)
        }
    }.onFailure { clear() }

    fun clear() {
        file.delete()
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
        private const val VERSION = "1"
        private const val MAX_TOKEN_CHARS = 16 * 1024
        private const val MAX_FILE_BYTES = 32 * 1024
        private const val KEY_ALIAS = "wekit_read_receipts_tunnel_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
