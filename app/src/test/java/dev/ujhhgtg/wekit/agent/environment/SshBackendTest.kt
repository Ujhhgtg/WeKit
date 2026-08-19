package dev.ujhhgtg.wekit.agent.environment

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
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
        assertTrue(result.stdout.trim().startsWith("{\"ok\":false,"))
    }

    @Test
    fun `bash helper accepts escaped JSON before connecting`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = runHelper(
            port,
            "call", "tool", "--json", "{\"text\":\"quote: \\\" slash: \\\\ newline: \\n\"}",
        )

        assertEquals(7, result.exitCode, result.stdout)
        assertTrue(result.stdout.contains("bridge unavailable"))
    }

    @Test
    fun `bash helper rejects truncated response`() {
        ServerSocket(0).use { server ->
            val thread = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    val header = input.readLine()
                    val length = header.substringAfterLast(' ').toInt()
                    repeat(length) { input.read() }
                    socket.getOutputStream().write("WBT/1 ${"a".repeat(64)} 10\n{}".toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
            thread.start()
            val result = runHelper(
                server.localPort,
                "list",
            )

            assertEquals(7, result.exitCode)
            assertTrue(result.stdout.trim().startsWith("{\"ok\":false,"))
            thread.join(TimeUnit.SECONDS.toMillis(2))
        }
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

    private data class ProcessResult(val exitCode: Int, val stdout: String)
}
