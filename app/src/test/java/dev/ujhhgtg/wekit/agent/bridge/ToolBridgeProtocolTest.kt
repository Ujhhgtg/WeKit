package dev.ujhhgtg.wekit.agent.bridge

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
}
