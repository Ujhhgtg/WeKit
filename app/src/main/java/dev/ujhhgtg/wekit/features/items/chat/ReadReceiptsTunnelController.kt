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
import android.util.Base64
import androidx.core.content.ContextCompat
import dev.ujhhgtg.wekit.utils.HostInfo
import java.util.concurrent.CopyOnWriteArrayList
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
    private val stoppedCallbacks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var service: Messenger? = null

    @Volatile
    private var binding = false

    @Volatile
    private var bound = false

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
    ): Result<Unit> {
        val context = HostInfo.application
        val nextGeneration = nextGeneration()
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
            return Result.failure(started.exceptionOrNull()!!)
        }

        val command = Message.obtain(null, ReadReceiptsTunnelProtocol.START).apply {
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, nextGeneration)
                putString(ReadReceiptsTunnelProtocol.KEY_MODE, mode.name)
                putString(
                    ReadReceiptsTunnelProtocol.KEY_ORIGIN,
                    "http://127.0.0.1:$originPort/",
                )
                putString(ReadReceiptsTunnelProtocol.KEY_HOSTNAME, hostname)
                if (token != null) putString(ReadReceiptsTunnelProtocol.KEY_TOKEN, token)
            }
        }
        queueOrSend(context, command)
        return Result.success(Unit)
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        if (onStopped != null) stoppedCallbacks += onStopped
        val nextGeneration = nextGeneration()
        status = ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPING)
        val command = Message.obtain(null, ReadReceiptsTunnelProtocol.STOP).apply {
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, nextGeneration)
            }
        }
        queueOrSend(HostInfo.application, command)
        mainHandler.postDelayed(
            {
                if (
                    generation.get() == nextGeneration &&
                    status.state != ReadReceiptsTunnelState.STOPPED &&
                    stoppedCallbacks.isNotEmpty()
                ) {
                    status = ReadReceiptsTunnelStatus(
                        ReadReceiptsTunnelState.FAILED,
                        error = "隧道停止超时; 已继续停止回环服务器",
                    )
                    finishStoppedCallbacks()
                }
            },
            STOP_COMPLETION_TIMEOUT_MILLIS,
        )
    }

    fun deleteCredential() {
        val nextGeneration = nextGeneration()
        val command = Message.obtain(null, ReadReceiptsTunnelProtocol.DELETE_CREDENTIAL).apply {
            data = Bundle().apply {
                putLong(ReadReceiptsTunnelProtocol.KEY_GENERATION, nextGeneration)
            }
        }
        queueOrSend(HostInfo.application, command)
    }

    fun refresh() {
        bind(HostInfo.application)
    }

    private fun queueOrSend(context: Context, command: Message) {
        pendingCommand = command
        if (!sendPending()) bind(context)
    }

    private fun sendPending(): Boolean {
        val target = service ?: return false
        val command = pendingCommand ?: return true
        val sent = runCatching {
            target.send(command)
            command.data.remove(ReadReceiptsTunnelProtocol.KEY_TOKEN)
            pendingCommand = null
        }.isSuccess
        if (!sent) onBinderDied()
        return sent
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
            finishStoppedCallbacks()
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
        service = null
        binding = false
        bound = false
        if (stoppedCallbacks.isNotEmpty()) {
            finishStoppedCallbacks()
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
                message.what != ReadReceiptsTunnelProtocol.STATUS ||
                message.data.getString(ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE) != clientNonce
            ) {
                return
            }
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
            )
            if (state == ReadReceiptsTunnelState.STOPPED) {
                if (stoppedCallbacks.isEmpty()) {
                    ReadReceipts.onTunnelServiceStopped()
                } else {
                    finishStoppedCallbacks()
                }
                unbind()
            }
        }
    }

    private fun finishStoppedCallbacks() {
        stoppedCallbacks.toList().forEach { callback -> callback() }
        stoppedCallbacks.clear()
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
    private const val STOP_COMPLETION_TIMEOUT_MILLIS = 20_000L
}
