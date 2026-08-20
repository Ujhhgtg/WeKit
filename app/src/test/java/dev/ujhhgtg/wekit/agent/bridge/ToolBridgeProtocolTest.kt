package dev.ujhhgtg.wekit.agent.bridge

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.SocketTimeoutException

class ToolBridgeProtocolTest {
    private val token = "a".repeat(ToolBridgeProtocol.TOKEN_LENGTH)

    @Test
    fun `preserves exact utf8 payload`() {
        val payload = "{\"text\":\"雪\u0000\u001f\"}"
        val bytes = ToolBridgeProtocol.encode(token, payload)
        val frame = ToolBridgeProtocol.read(ByteArrayInputStream(bytes))
        assertEquals(token, frame.token)
        assertEquals(payload, frame.payload)
        assertArrayEquals(payload.toByteArray(StandardCharsets.UTF_8), bytes.takeLast(payload.toByteArray(StandardCharsets.UTF_8).size).toByteArray())
    }

    @Test
    fun `rejects malformed header and token`() {
        assertThrows(IllegalArgumentException::class.java) { ToolBridgeProtocol.read(ByteArrayInputStream("bad\n".toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) {
            ToolBridgeProtocol.read(ByteArrayInputStream("${ToolBridgeProtocol.VERSION} nope 0\n".toByteArray()))
        }
    }

    @Test
    fun `rejects oversized length`() {
        val header = "${ToolBridgeProtocol.VERSION} $token ${ToolBridgeProtocol.MAX_PAYLOAD_BYTES + 1}\n"
        assertThrows(IllegalArgumentException::class.java) { ToolBridgeProtocol.read(ByteArrayInputStream(header.toByteArray())) }
    }

    @Test
    fun `concurrent frames remain independent`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 64).map { index -> executor.submit<String> {
                ToolBridgeProtocol.read(ByteArrayInputStream(ToolBridgeProtocol.encode(token, "payload-$index"))).payload
            } }
            assertEquals((0 until 64).map { "payload-$it" }, results.map { it.get(2, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `client JSON-escapes provider IDs`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Executors.newSingleThreadExecutor().use { executor ->
                val response = executor.submit<String> {
                    server.accept().use { socket ->
                        val frame = ToolBridgeProtocol.read(socket.getInputStream())
                        ToolBridgeProtocol.write(socket.getOutputStream(), frame.token, "{\"ok\":true}")
                        frame.payload
                    }
                }
                InvokeToolClient(server.localPort, token).list("provider\"\\line\n")
                val request = Json.parseToJsonElement(response.get(2, TimeUnit.SECONDS)).jsonObject
                assertEquals("provider\"\\line\n", request.getValue("provider").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `client builds all requests structurally`() {
        val requests = mutableListOf<String>()
        val escaped = "value\"\\line\n"
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            Executors.newSingleThreadExecutor().use { executor ->
                val responses = executor.submit {
                    repeat(4) {
                        server.accept().use { socket ->
                            val frame = ToolBridgeProtocol.read(socket.getInputStream())
                            requests += frame.payload
                            ToolBridgeProtocol.write(socket.getOutputStream(), frame.token, "{\"ok\":true}")
                        }
                    }
                }
                val client = InvokeToolClient(server.localPort, token)
                client.list(escaped)
                client.search(escaped)
                client.schema(escaped)
                client.call(escaped, kotlinx.serialization.json.buildJsonObject {
                    put("nested", escaped)
                }.toString())
                responses.get(2, TimeUnit.SECONDS)
            }
        }
        val decoded = requests.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(escaped, decoded[0].getValue("provider").jsonPrimitive.content)
        assertEquals(escaped, decoded[1].getValue("keyword").jsonPrimitive.content)
        assertEquals(escaped, decoded[2].getValue("name").jsonPrimitive.content)
        assertEquals(escaped, decoded[3].getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `client read deadline is bounded`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Executors.newSingleThreadExecutor().use { executor ->
                val accepted = executor.submit {
                    server.accept().use { Thread.sleep(250) }
                }
                assertThrows(SocketTimeoutException::class.java) {
                    InvokeToolClient(server.localPort, token, responseTimeoutMs = 50).list()
                }
                accepted.get(1, TimeUnit.SECONDS)
            }
        }
    }
}
