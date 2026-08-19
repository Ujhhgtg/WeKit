package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.tool.ToolCallOrigin
import dev.ujhhgtg.wekit.agent.tool.ToolRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalManagerTest {
    private val environment = EnvironmentSnapshot("native", "Native", LinuxEnvironmentType.NATIVE, "Android", "arm64", "/system/bin/sh", "/tmp", null, "uid")
    private val linuxEnvironment = environment.copy(id = "linux", type = LinuxEnvironmentType.PROOT, shell = "/bin/bash")

    @Test
    fun `events are ordered and encoded`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        val id = manager.start("conversation", environment).id
        manager.write("conversation", id, listOf(TerminalEvent(TerminalEvent.Type.TEXT, "hello"), TerminalEvent(TerminalEvent.Type.KEY, "ENTER"), TerminalEvent(TerminalEvent.Type.CHORD, "CTRL-C")))
        assertEquals("hello\r\u0003", backend.sessions.single().writes.flatMap { it.asList() }.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `default commands and explicit argv stay separated`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        manager.start("native", environment)
        manager.start("linux", linuxEnvironment)
        manager.start("explicit", environment, listOf("/system/bin/sh", "-c", "echo hello"))
        assertEquals(listOf("/system/bin/sh"), backend.argv[0])
        assertEquals(listOf("/bin/bash", "-l"), backend.argv[1])
        assertEquals(listOf("/system/bin/sh", "-c", "echo hello"), backend.argv[2])
    }

    @Test
    fun `output cursors are bounded and report expiry`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        val id = manager.start("one", environment).id
        backend.sessions.single().emit("abcdef")

        assertEquals("abcdef", manager.read("one", id, cursor = 0, waitMs = 1_000).bytes.toString(Charsets.UTF_8))
        val first = manager.read("one", id, cursor = 1, maxBytes = 3)
        assertEquals("bcd", first.bytes.toString(Charsets.UTF_8))
        assertEquals(1, first.cursor)
        val future = manager.read("one", id, cursor = 100, waitMs = 1_000)
        assertTrue(future.bytes.isEmpty())
        assertEquals(6, future.cursor)

        val ring = TerminalManager.ByteRing(4)
        ring.append("abcdef".toByteArray())
        val expired = ring.read(0, 4)
        assertTrue(expired.expired)
        assertEquals(2, expired.oldest)
        assertEquals("cdef", expired.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `read wakes when output arrives and when session terminates`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        val id = manager.start("one", environment).id
        val outputRead = async { manager.read("one", id, cursor = 0, waitMs = 5_000) }
        delay(50)
        backend.sessions.single().emit("ready")
        assertEquals("ready", withTimeout(1_000) { outputRead.await() }.bytes.toString(Charsets.UTF_8))

        val endRead = async { manager.read("one", id, cursor = 5, waitMs = 5_000) }
        delay(50)
        backend.sessions.single().exit(0)
        assertEquals(TerminalState.EXITED, withTimeout(1_000) { endRead.await() }.state)
    }

    @Test
    fun `sessions are globally limited and owned by conversation`() = runBlocking {
        val manager = manager(FakeBackend())
        val results = coroutineScope {
            (0..TerminalManager.MAX_SESSIONS).map { index ->
                async { runCatching { manager.start("owner-$index", environment).id } }
            }.map { it.await() }
        }
        val ids = results.mapNotNull(Result<String>::getOrNull)
        assertEquals(TerminalManager.MAX_SESSIONS, ids.size)
        assertEquals(1, results.count(Result<String>::isFailure))
        assertThrows(IllegalStateException::class.java) { runBlocking { manager.read("other", ids.first()) } }
    }

    @Test
    fun `starting running exited and spawn failure are observable`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val backend = FakeBackend(startGate = gate, startEntered = entered)
        val manager = manager(backend)
        val start = async { manager.start("one", environment) }
        entered.await()
        assertEquals(TerminalState.STARTING, manager.list("one").single().state)
        gate.complete(Unit)
        val info = start.await()
        assertEquals(TerminalState.RUNNING, info.state)
        backend.sessions.single().exit(7)
        eventually { manager.list("one").single().state == TerminalState.EXITED }

        val failedManager = manager(FakeBackend(failStart = true))
        assertEquals(TerminalState.FAILED, failedManager.start("failed", environment).state)
    }

    @Test
    fun `reader failure becomes failed and waiter closes once`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        manager.start("one", environment)
        val session = backend.sessions.single()
        session.failRead()
        eventually { manager.list("one").single().state == TerminalState.FAILED }
        eventually { session.closeCount == 1 }
        assertEquals(1, session.killCount)
    }

    @Test
    fun `kill and revocation keep waiter responsible for one close`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        val first = manager.start("owner", environment).id
        manager.start("owner", environment)
        assertEquals(TerminalState.KILLED, manager.kill("owner", first).state)
        manager.revokeOwner("owner")
        eventually { backend.sessions.all { it.closeCount == 1 } }
        assertEquals(listOf(1, 1), backend.sessions.map { it.killCount })
        assertTrue(manager.list("owner").all { it.state == TerminalState.KILLED })
    }

    @Test
    fun `idle and lifetime expiry retain then clean finished output`() = runBlocking {
        val clock = AtomicLong(1_000)
        val backend = FakeBackend()
        val manager = manager(backend, clock::get)
        val idle = manager.start("idle", environment).id
        clock.addAndGet(TerminalManager.IDLE_TIMEOUT_MS)
        manager.expireSessions()
        assertEquals(TerminalState.EXPIRED, manager.list("idle").single().state)
        assertEquals(1, backend.sessions.single().killCount)

        clock.addAndGet(TerminalManager.FINISHED_RETENTION_MS)
        manager.expireSessions()
        assertTrue(manager.list("idle").isEmpty())

        clock.set(10_000)
        val lifetimeBackend = FakeBackend()
        val lifetimeManager = manager(lifetimeBackend, clock::get)
        lifetimeManager.start("life", environment)
        repeat(3) {
            clock.addAndGet(TerminalManager.IDLE_TIMEOUT_MS - 1)
            lifetimeManager.read("life", lifetimeManager.list("life").single().id)
        }
        clock.set(10_000 + TerminalManager.MAX_LIFETIME_MS)
        lifetimeManager.expireSessions()
        assertEquals(TerminalState.EXPIRED, lifetimeManager.list("life").single().state)
    }

    @Test
    fun `input output and dimensions are bounded`() = runBlocking {
        val manager = manager(FakeBackend())
        val id = manager.start("one", environment).id
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.write("one", id, listOf(TerminalEvent(TerminalEvent.Type.SLEEP, durationMs = 10_001))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.resize("one", id, 0, 24) } }
        val ring = TerminalManager.ByteRing(8)
        ring.append(ByteArray(20))
        assertEquals(8, ring.size)
        assertEquals(12, ring.base)
    }

    @Test
    fun `global output quota evicts oldest retained bytes`() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        val ids = (0 until TerminalManager.MAX_SESSIONS).map { manager.start("owner", environment).id }
        backend.sessions.forEach { it.emit(ByteArray(TerminalManager.MAX_SESSION_OUTPUT_BYTES)) }
        eventually { manager.list("owner").all { it.endCursor == TerminalManager.MAX_SESSION_OUTPUT_BYTES.toLong() } }
        backend.sessions.forEach { it.exit(0) }
        eventually { manager.list("owner").all { it.state == TerminalState.EXITED } }

        val newest = manager.start("owner", environment).id
        backend.sessions.last().emit(byteArrayOf(1))
        eventually { manager.list("owner").single { it.id == newest }.endCursor == 1L }
        assertTrue(manager.read("owner", ids.first(), cursor = 0).cursorExpired)
    }

    @Test
    fun `terminal tools are direct only and revocation hook is public`() {
        assertTrue(ToolRegistry.isCallAllowed("terminal_read", ToolCallOrigin.DIRECT))
        assertFalse(ToolRegistry.isCallAllowed("terminal_read", ToolCallOrigin.ENVIRONMENT_BRIDGE))
        assertTrue(ToolRegistry.isCallAllowed("exec", ToolCallOrigin.ENVIRONMENT_BRIDGE))
        assertTrue(TerminalManager::class.java.methods.any { it.name == "revokeOwner" })
    }

    private fun manager(backend: FakeBackend, now: () -> Long = System::currentTimeMillis) =
        TerminalManager(backend, now, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private suspend fun eventually(predicate: suspend () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) delay(10)
        }
    }

    private class FakeBackend(
        private val failStart: Boolean = false,
        private val startGate: CompletableDeferred<Unit>? = null,
        private val startEntered: CompletableDeferred<Unit>? = null,
    ) : TerminalBackend {
        val sessions = CopyOnWriteArrayList<FakeSession>()
        val argv = CopyOnWriteArrayList<List<String>>()

        override suspend fun start(environment: EnvironmentSnapshot, argv: List<String>, workingDirectory: String?, environmentVariables: Map<String, String>, cols: Int, rows: Int): TerminalBackendStart {
            this.argv += argv
            startEntered?.complete(Unit)
            startGate?.await()
            if (failStart) error("spawn failed")
            return TerminalBackendStart(FakeSession().also { sessions += it }, environment)
        }
    }

    private class FakeSession : TerminalBackendSession {
        val writes = CopyOnWriteArrayList<ByteArray>()
        private val reads = Channel<ByteArray>(Channel.UNLIMITED)
        private val exit = CompletableDeferred<Int?>()
        @Volatile var killCount = 0
        @Volatile var closeCount = 0

        suspend fun emit(value: String) = emit(value.toByteArray())
        suspend fun emit(bytes: ByteArray) = reads.send(bytes)
        fun failRead() {
            reads.close(IllegalStateException("read failed"))
        }
        fun exit(code: Int?) {
            reads.trySend(ByteArray(0))
            exit.complete(code)
        }

        override suspend fun write(bytes: ByteArray) { writes += bytes }
        override suspend fun read(maxBytes: Int): ByteArray = reads.receive()
        override suspend fun resize(cols: Int, rows: Int) = Unit
        override suspend fun waitForExit(): Int? = exit.await()
        override suspend fun kill() {
            killCount++
            exit(null)
        }
        override suspend fun close() { closeCount++ }
    }
}
