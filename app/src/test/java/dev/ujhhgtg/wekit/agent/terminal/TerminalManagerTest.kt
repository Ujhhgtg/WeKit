package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TerminalManagerTest {
    private val environment = EnvironmentSnapshot("native", "Native", LinuxEnvironmentType.NATIVE, "Android", "arm64", "/system/bin/sh", "/tmp", null, "uid")

    @Test
    fun `events are ordered and encoded`() = runBlocking {
        val backend = FakeBackend()
        val manager = TerminalManager(backend)
        val id = manager.start("conversation", environment).id
        manager.write("conversation", id, listOf(TerminalEvent(TerminalEvent.Type.TEXT, "hello"), TerminalEvent(TerminalEvent.Type.KEY, "ENTER"), TerminalEvent(TerminalEvent.Type.CHORD, "CTRL-C")))
        assertEquals("hello\r\u0003", backend.session.writes.flatMap { it.asList() }.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `sessions are owned by their conversation`() = runBlocking {
        val manager = TerminalManager(FakeBackend())
        val id = manager.start("one", environment).id
        assertThrows(IllegalStateException::class.java) { runBlocking { manager.read("two", id) } }
    }

    @Test
    fun `invalid sleep and dimensions are rejected`() = runBlocking {
        val manager = TerminalManager(FakeBackend())
        val id = manager.start("one", environment).id
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.write("one", id, listOf(TerminalEvent(TerminalEvent.Type.SLEEP, durationMs = 10_001))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.resize("one", id, 0, 24) } }
    }

    private class FakeBackend : TerminalBackend {
        lateinit var session: FakeSession
        override suspend fun start(environment: EnvironmentSnapshot, argv: List<String>, workingDirectory: String?, environmentVariables: Map<String, String>, cols: Int, rows: Int) = TerminalBackendStart(FakeSession().also { session = it }, environment)
    }

    private class FakeSession : TerminalBackendSession {
        val writes = mutableListOf<ByteArray>()
        override suspend fun write(bytes: ByteArray) { writes += bytes }
        override suspend fun read(maxBytes: Int) = ByteArray(0)
        override suspend fun resize(cols: Int, rows: Int) = Unit
        override suspend fun waitForExit(): Int? = null
        override suspend fun kill() = Unit
    }
}
