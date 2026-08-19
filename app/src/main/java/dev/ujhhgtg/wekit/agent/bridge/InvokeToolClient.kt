package dev.ujhhgtg.wekit.agent.bridge

import java.net.InetSocketAddress
import java.net.Socket

/** Portable client contract; shell/native installers can implement the same four operations. */
class InvokeToolClient(private val port: Int, private val token: String, private val timeoutMs: Int = 10_000) {
    fun request(payload: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        ToolBridgeProtocol.write(socket.getOutputStream(), token, payload)
        ToolBridgeProtocol.read(socket.getInputStream()).payload
    }
    fun list(provider: String? = null) = request(if (provider == null) "{\"op\":\"list\"}" else "{\"op\":\"list\",\"provider\":\"$provider\"}")
    fun search(keyword: String) = request("{\"op\":\"search\",\"keyword\":${kotlinx.serialization.json.JsonPrimitive(keyword)}}")
    fun schema(name: String) = request("{\"op\":\"schema\",\"name\":${kotlinx.serialization.json.JsonPrimitive(name)}}")
    fun call(name: String, argumentsJson: String) = request("{\"op\":\"call\",\"name\":${kotlinx.serialization.json.JsonPrimitive(name)},\"arguments\":$argumentsJson}")
}
