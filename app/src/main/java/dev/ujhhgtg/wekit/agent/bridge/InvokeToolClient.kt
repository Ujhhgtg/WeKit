package dev.ujhhgtg.wekit.agent.bridge

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Portable client contract; shell/native installers can implement the same four operations. */
class InvokeToolClient(
    private val port: Int,
    private val token: String,
    private val connectTimeoutMs: Int = 10_000,
    private val writeTimeoutMs: Int = 10_000,
    private val responseTimeoutMs: Int = 10 * 60 * 1000,
) {
    fun request(payload: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
        socket.soTimeout = responseTimeoutMs
        val writeDeadline = deadlines.schedule({ runCatching { socket.close() } }, writeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            ToolBridgeProtocol.write(socket.getOutputStream(), token, payload)
        } finally {
            writeDeadline.cancel(false)
        }
        ToolBridgeProtocol.read(socket.getInputStream()).payload
    }
    fun list(provider: String? = null) = request(buildJsonObject {
        put("op", "list")
        provider?.let { put("provider", it) }
    }.toString())
    fun search(keyword: String) = request(buildJsonObject { put("op", "search"); put("keyword", keyword) }.toString())
    fun schema(name: String) = request(buildJsonObject { put("op", "schema"); put("name", name) }.toString())
    fun call(name: String, argumentsJson: String) = request(buildJsonObject {
        put("op", "call")
        put("name", name)
        put("arguments", kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as JsonObject)
    }.toString())

    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "invoke-tool-write-deadline").apply { isDaemon = true }
        }
    }
}
