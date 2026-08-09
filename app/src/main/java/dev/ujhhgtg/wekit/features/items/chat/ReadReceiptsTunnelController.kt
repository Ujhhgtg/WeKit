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
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom

/** WeChat-process Binder client. The service remains the authoritative tunnel state owner. */
internal object ReadReceiptsTunnelController {
    private val generation = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val lastIssuedGeneration = AtomicLong()
    private val clientNonce = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incoming = Messenger(IncomingHandler(Looper.getMainLooper()))
    private val handoffGate = TunnelHandoffGate()
    private val stopCompletion = TunnelStopCompletion()

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

    @Volatile
    var status: ReadReceiptsTunnelStatus = ReadReceiptsTunnelStatus(
        ReadReceiptsTunnelState.STOPPED,
    )
        private set

    @Volatile
    var credentialExists: Boolean = false
        private set

    fun verifiedEndpoint(): String? = status
        .takeIf { it.state == ReadReceiptsTunnelState.CONNECTED }
        ?.publicUrl

    fun needsVisibleStart() {
        if (status.state == ReadReceiptsTunnelState.CONNECTED) return
        status = ReadReceiptsTunnelStatus(
            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
            error = "请在已读追踪设置中点击连接以启动前台隧道",
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
        if (stopCompletion.hasPendingStop()) {
            handoffDelivery.complete(
                Result.failure(IllegalStateException("隧道正在停止，请等待完成后重试")),
            )
            return
        }
        val context = HostInfo.application
        val nextGeneration = handoffGate.beginAfterSuperseding(
            pendingGeneration = { pendingStart?.generation },
            supersede = { supersededGeneration ->
                supersedePendingStart(supersededGeneration)
            },
            generationFactory = ::nextGeneration,
        )
        status = ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STARTING)
        val startIntent = serviceIntent(context).apply {
            action = ReadReceiptsTunnelService.ACTION_START
        }
        val started = runCatching { ContextCompat.startForegroundService(context, startIntent) }
        if (started.isFailure) {
            status = ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                error = "系统阻止了前台服务启动, 请保持微信在前台后重试",
            )
            handoffGate.fail(nextGeneration)
            handoffDelivery.complete(Result.failure(started.exceptionOrNull()!!))
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
            { failPendingStart(nextGeneration, IllegalStateException("隧道服务接管请求超时")) },
            START_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    fun stop(onStopped: (() -> Unit)? = null) {
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
                        error = "隧道停止超时; 已继续停止回环服务器",
                    )
                    drain.callbacks.forEach { callback -> callback() }
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
                error = "无法连接 WeKit 隧道服务",
            )
            failPendingStart(IllegalStateException("无法连接 WeKit 隧道服务"))
            stopCompletion.pendingGeneration()?.let(::completeStop)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            bound = true
            service = Messenger(binder)
            runCatching { binder.linkToDeath(::onBinderDied, 0) }
            val register = Message.obtain(null, ReadReceiptsTunnelProtocol.REGISTER).apply {
                replyTo = incoming
                data = Bundle().apply {
                    putString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE, clientNonce)
                }
            }
            runCatching { service!!.send(register) }
                .onFailure { onBinderDied() }
            sendPending()
        }

        override fun onServiceDisconnected(name: ComponentName) = onBinderDied()

        override fun onBindingDied(name: ComponentName) = onBinderDied()

        override fun onNullBinding(name: ComponentName) = onBinderDied()
    }

    private fun onBinderDied() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::handleBinderDied)
        } else {
            handleBinderDied()
        }
    }

    private fun handleBinderDied() {
        service = null
        binding = false
        bound = false
        failPendingStart(IllegalStateException("隧道服务在接管请求前断开"))
        val stoppingGeneration = stopCompletion.pendingGeneration()
        if (stoppingGeneration != null) {
            completeStop(stoppingGeneration)
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
                error = "隧道服务已断开, 请在可见设置界面重新连接",
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
            if (message.what != ReadReceiptsTunnelProtocol.STATUS) return
            val data = message.data
            credentialExists = data.getBoolean(ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_EXISTS)
            val incomingGeneration = data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION)
            if (incomingGeneration < lastIssuedGeneration.get()) return
            generation.updateAndGet { current -> maxOf(current, incomingGeneration) }
            val state = data.getString(ReadReceiptsTunnelProtocol.KEY_STATE)
                ?.let { name -> ReadReceiptsTunnelState.entries.firstOrNull { it.name == name } }
                ?: ReadReceiptsTunnelState.FAILED
            status = ReadReceiptsTunnelStatus(
                state = state,
                publicUrl = data.getString(ReadReceiptsTunnelProtocol.KEY_PUBLIC_URL),
                error = data.getString(ReadReceiptsTunnelProtocol.KEY_ERROR),
                needsNotificationSettings = data.getBoolean(
                    ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS,
                ),
            )
            if (state == ReadReceiptsTunnelState.STOPPED) {
                val drain = stopCompletion.complete(incomingGeneration)
                if (!drain.matched) {
                    ReadReceipts.onTunnelServiceStopped()
                } else {
                    drain.callbacks.forEach { callback -> callback() }
                }
                unbind()
            }
        }
    }

    private fun handleStartAck(data: Bundle) {
        val acknowledgedGeneration = data.getLong(ReadReceiptsTunnelProtocol.KEY_GENERATION)
        val pending = pendingStart
        if (pending?.generation != acknowledgedGeneration) return
        val accepted = data.getBoolean(ReadReceiptsTunnelProtocol.KEY_ACCEPTED)
        if (data.getBoolean(ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS)) {
            status = ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                error = "WeKit 通知已关闭, 请在系统设置中允许通知后重试",
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
                Result.failure(IllegalStateException("隧道服务拒绝了连接请求"))
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

    private fun completeStop(expectedGeneration: Long) {
        val drain = stopCompletion.complete(expectedGeneration)
        if (!drain.matched) return
        drain.callbacks.forEach { callback -> callback() }
    }

    private fun nextGeneration(): Long = generation.updateAndGet { current ->
        maxOf(current + 1, SystemClock.elapsedRealtimeNanos())
    }.also(lastIssuedGeneration::set)

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

    private data class PendingStart(
        val generation: Long,
        val command: Message,
        val completion: TunnelHandoffTerminalDelivery,
        var sent: Boolean = false,
    )
}
