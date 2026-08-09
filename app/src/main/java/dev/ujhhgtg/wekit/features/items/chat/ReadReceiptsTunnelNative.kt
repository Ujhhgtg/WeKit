package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** Direct JNI owner for the separately-built Go cloudflared shared library. */
internal object ReadReceiptsTunnelNative {
    private val handle = AtomicLong()

    init {
        System.loadLibrary("wekit_cloudflared")
    }

    @Synchronized
    fun startQuick(origin: String): Result<Unit> = start { nativeStartQuick(origin) }

    @Synchronized
    fun startToken(token: String, origin: String): Result<Unit> =
        start { nativeStartToken(token, origin) }

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
}
