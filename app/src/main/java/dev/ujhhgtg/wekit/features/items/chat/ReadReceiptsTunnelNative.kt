package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** Direct JNI owner for the separately-built Go cloudflared shared library. */
internal object ReadReceiptsTunnelNative {
    private val handle = AtomicLong()
    private val authHandle = AtomicLong()

    init {
        System.loadLibrary("wekit_cloudflared")
    }

    @Synchronized
    fun startQuick(origin: String): Result<Unit> = start { nativeStartQuick(origin) }

    @Synchronized
    fun startToken(token: String, origin: String): Result<Unit> =
        start { nativeStartToken(token, origin) }

    /** Replaces only browser authentication; the active connector remains untouched. */
    @Synchronized
    fun beginLogin(): Result<String> = runCatching {
        val previous = authHandle.getAndSet(0L)
        if (previous != 0L) {
            check(nativeAuthCancel(previous) == 0) { "browser login replacement failed" }
        }
        val created = nativeAuthBegin()
        check(created != 0L) { "browser login could not be created" }
        authHandle.set(created)
        checkNotNull(nativeAuthStatus(created)) { "browser login status is unavailable" }
    }.onFailure {
        val created = authHandle.getAndSet(0L)
        if (created != 0L) nativeAuthCancel(created)
    }

    fun loginStatusJson(): Result<String> = runCatching {
        checkNotNull(nativeAuthStatus(requireAuthHandle())) {
            "browser login status is unavailable"
        }
    }

    /** Intentionally unlocked: a timeout owner must cancel this blocking JNI call from another IO coroutine. */
    fun listExistingTunnelsJson(): Result<String> = runCatching {
        checkNotNull(nativeAuthList(requireAuthHandle())) {
            "Cloudflare tunnel list is unavailable"
        }
    }

    /**
     * The run token exists only as this private service-facing return value. This remains unlocked so
     * [cancelLogin] can cancel and join an in-flight native selection from another IO coroutine.
     */
    fun selectExistingTunnelForService(tunnelId: String, hostname: String): Result<String> =
        runCatching {
            checkNotNull(nativeAuthSelect(requireAuthHandle(), tunnelId, hostname)) {
                "Cloudflare tunnel selection failed"
            }
        }

    @Synchronized
    fun cancelLogin(): Result<Unit> {
        val owned = authHandle.getAndSet(0L)
        if (owned == 0L) return Result.success(Unit)
        return runCatching {
            check(nativeAuthCancel(owned) == 0) { "browser login cancellation failed" }
        }
    }

    @Synchronized
    private fun requireAuthHandle(): Long =
        authHandle.get().also { check(it != 0L) { "browser login is not active" } }

    @Synchronized
    private fun start(create: () -> Long): Result<Unit> = runCatching {
        check(handle.get() == 0L) { "tunnel is already active" }
        val created = create()
        check(created != 0L) { "tunnel could not be created" }
        handle.set(created)
    }

    /** Atomically clears ownership before native stop frees the handle. */
    @Synchronized
    fun stop(): Result<Unit> {
        val owned = handle.getAndSet(0L)
        if (owned == 0L) return Result.success(Unit)
        return runCatching { check(nativeStop(owned) == 0) { "tunnel stop failed" } }
    }

    @Synchronized
    fun status(): ReadReceiptsTunnelStatus {
        val owned = handle.get()
        if (owned == 0L) return ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPED)
        return runCatching {
            val value = DefaultJson.parseToJsonElement(nativeStatus(owned)).jsonObject
            val state = when (value.getValue("status").jsonPrimitive.content) {
                "STOPPED" -> ReadReceiptsTunnelState.STOPPED
                "STARTING" -> ReadReceiptsTunnelState.STARTING
                "CONNECTED" -> ReadReceiptsTunnelState.CONNECTED
                "RECONNECTING" -> ReadReceiptsTunnelState.RECONNECTING
                "STOPPING" -> ReadReceiptsTunnelState.STOPPING
                "UNSUPPORTED" -> ReadReceiptsTunnelState.NEEDS_USER_ACTION
                else -> ReadReceiptsTunnelState.FAILED
            }
            ReadReceiptsTunnelStatus(
                state = state,
                publicUrl = value.getValue("url").jsonPrimitive.content.takeIf(String::isNotEmpty),
                error = value.getValue("error").jsonPrimitive.content.takeIf(String::isNotEmpty),
            )
        }.getOrElse {
            ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.FAILED,
                error = "无法读取 Cloudflare Tunnel 状态",
            )
        }
    }

    private external fun nativeStartQuick(origin: String): Long

    private external fun nativeStartToken(token: String, origin: String): Long

    private external fun nativeStop(handle: Long): Int

    private external fun nativeStatus(handle: Long): String

    private external fun nativeAuthBegin(): Long

    private external fun nativeAuthStatus(handle: Long): String?

    private external fun nativeAuthList(handle: Long): String?

    private external fun nativeAuthSelect(handle: Long, tunnelId: String, hostname: String): String?

    private external fun nativeAuthCancel(handle: Long): Int
}
