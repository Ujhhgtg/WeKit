package dev.ujhhgtg.wekit.agent.environment

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SshBackendTest {
    @Test
    fun `bash helper rejects malformed call JSON before connecting`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = runHelper(
            port,
            "call", "tool", "--json", "{\"unterminated\"",
        )

        assertEquals(2, result.exitCode)
        assertJsonOutput(result)
    }

    @Test
    fun `bash helper accepts escaped JSON before connecting`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = runHelper(
            port,
            "call", "tool", "--json", "{\"text\":\"quote: \\\" slash: \\\\ newline: \\n\"}",
        )

        assertEquals(7, result.exitCode, result.stdout)
        assertJsonOutput(result)
    }

    @Test
    fun `bash helper returns parseable JSON for every nonzero response status`() {
        listOf(
            3 to "unauthorized",
            4 to "unknown_tool",
            5 to "approval_denied",
            6 to "execution_failed",
        ).forEach { (exitCode, error) ->
            val result = withMockBridge("{\"ok\":false,\"error\":\"$error\"}") {
                runHelper(it, "list")
            }

            assertEquals(exitCode, result.exitCode, result.stdout)
            assertJsonOutput(result)
        }
    }

    @Test
    fun `bash helper rejects truncated response header with exit seven`() {
        ServerSocket(0).use { server ->
            val thread = Thread {
                server.accept().use { socket ->
                    drainRequest(socket.getInputStream())
                    socket.getOutputStream().write("WBT/1 ${"a".repeat(64)}".toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
            thread.start()
            val result = runHelper(
                server.localPort,
                "list",
            )

            assertEquals(7, result.exitCode)
            assertJsonOutput(result)
            thread.join(TimeUnit.SECONDS.toMillis(2))
        }
    }

    @Test
    fun `bash helper preserves escaped JSON input in captured request`() {
        val json = "{\"text\":\"quote: \\\" slash: \\\\ newline: \\n\"}"
        val result = withMockBridge("{\"ok\":true}") { port ->
            runHelper(port, "call", "tool", "--json", json)
        }

        assertEquals(0, result.exitCode, result.stdout)
        val captured = capturedRequest
        val expected = "{\"op\":\"call\",\"name\":\"tool\",\"arguments\":$json}"
        assertEquals(expected, captured.toString(StandardCharsets.UTF_8))
        assertEquals(expected.toByteArray(StandardCharsets.UTF_8).toList(), captured.toList())
    }

    @Test
    fun `reverse forward closes once when post-acquisition construction fails`() {
        var closes = 0
        val forward = SshReverseForward(23456) { closes++ }

        assertThrows(NoSuchElementException::class.java) {
            runBlocking {
                withSshReverseForward(forward) {
                    mapOf("WEAGENT_BRIDGE_PORT" to "12345").getValue("WEAGENT_BRIDGE_TOKEN")
                }
            }
        }

        assertEquals(1, closes)
    }

    @Test
    fun `reverse forward remains open for normal execution lifetime`() = runBlocking {
        var closes = 0
        val forward = SshReverseForward(23456) { closes++ }

        val result = withSshReverseForward(forward) {
            assertEquals(0, closes)
            "complete"
        }

        assertEquals("complete", result)
        assertEquals(1, closes)
    }

    private fun runHelper(port: Int, vararg args: String): ProcessResult {
        val script = Files.createTempFile("weagent-invoke-tool", ".sh")
        Files.writeString(script, SshBackend.REMOTE_HELPER, StandardCharsets.UTF_8)
        try {
            val started = ProcessBuilder("/bin/bash", script.toString(), *args)
                .apply {
                    environment()["WEAGENT_BRIDGE_PORT"] = port.toString()
                    environment()["WEAGENT_BRIDGE_TOKEN"] = "a".repeat(64)
                }
                .start()
            val stdout = started.inputStream.bufferedReader().readText()
            val exitCode = started.waitFor(2, TimeUnit.SECONDS)
            if (!exitCode) started.destroyForcibly()
            return ProcessResult(if (exitCode) started.exitValue() else -1, stdout)
        } finally {
            Files.deleteIfExists(script)
        }
    }

    private fun assertJsonOutput(result: ProcessResult) {
        val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals(false, json.getValue("ok").jsonPrimitive.booleanOrNull)
    }

    private fun withMockBridge(response: String, block: (Int) -> ProcessResult): ProcessResult {
        ServerSocket(0).use { server ->
            var captured: ByteArray? = null
            val thread = Thread {
                server.accept().use { socket ->
                    captured = readRequest(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                    output.write("WBT/1 ${"a".repeat(64)} ${responseBytes.size}\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(responseBytes)
                    output.flush()
                }
            }
            thread.start()
            val result = block(server.localPort)
            thread.join(TimeUnit.SECONDS.toMillis(2))
            capturedRequest = captured ?: error("mock bridge did not capture a request")
            return result
        }
    }

    private fun readRequest(input: java.io.InputStream): ByteArray {
        val header = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "request header was truncated" }
            header.write(byte)
            if (byte == '\n'.code) break
        }
        val length = header.toString(StandardCharsets.US_ASCII).trim().substringAfterLast(' ').toInt()
        return ByteArray(length) { input.read().also { check(it >= 0) { "request payload was truncated" } }.toByte() }
    }

    private fun drainRequest(input: java.io.InputStream) {
        readRequest(input)
    }

    private var capturedRequest = ByteArray(0)

    private data class ProcessResult(val exitCode: Int, val stdout: String)
}
