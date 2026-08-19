package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class TerminalManager(
    private val backend: TerminalBackend,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()

    init {
        scope.launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                expireSessions()
            }
        }
    }

    suspend fun start(
        owner: String,
        environment: EnvironmentSnapshot,
        argv: List<String>? = null,
        workingDirectory: String? = null,
        environmentVariables: Map<String, String> = emptyMap(),
        cols: Int = 80,
        rows: Int = 24,
    ): TerminalInfo {
        require(cols in 1..500 && rows in 1..200)
        val command = argv ?: when (environment.type) {
            LinuxEnvironmentType.NATIVE -> listOf("/system/bin/sh")
            else -> listOf("/bin/bash", "-l")
        }
        require(command.isNotEmpty() && command.none(String::isEmpty)) { "terminal argv cannot be empty" }
        expireSessions()
        val createdAt = now()
        val session = Session(UUID.randomUUID().toString(), owner, environment, cols, rows, createdAt)
        synchronized(lock) {
            require(sessions.values.count { it.state.isActive } < MAX_SESSIONS) { "global terminal session limit reached" }
            sessions[session.id] = session
        }

        val started = try {
            backend.start(environment, command, workingDirectory, environmentVariables, cols, rows)
        } catch (error: CancellationException) {
            synchronized(lock) { session.finish(TerminalState.FAILED, now()) }
            throw error
        } catch (_: Throwable) {
            synchronized(lock) { session.finish(TerminalState.FAILED, now()) }
            return synchronized(lock) { session.info() }
        }

        val shouldTerminate = synchronized(lock) {
            session.backend = started.session
            if (session.state == TerminalState.STARTING) session.state = TerminalState.RUNNING
            session.state != TerminalState.RUNNING
        }
        launchWorkers(session, started.session)
        if (shouldTerminate) runCatching { started.session.kill() }
        return synchronized(lock) { session.info() }
    }

    suspend fun list(owner: String): List<TerminalInfo> {
        expireSessions()
        return synchronized(lock) {
            sessions.values.filter { it.owner == owner }.map(Session::info).sortedBy(TerminalInfo::id)
        }
    }

    suspend fun write(owner: String, id: String, events: List<TerminalEvent>) {
        require(events.size <= 256) { "too many terminal events" }
        expireSessions()
        val session = owned(owner, id)
        var inputBytes = 0L
        var sleepMillis = 0L
        session.writeMutex.withLock {
            for (event in events) {
                synchronized(lock) { check(session.state == TerminalState.RUNNING) { "terminal is not running" } }
                when (event.type) {
                    TerminalEvent.Type.SLEEP -> {
                        require(event.durationMs in 0..MAX_SLEEP_MS)
                        sleepMillis += event.durationMs
                        require(sleepMillis <= MAX_TOTAL_SLEEP_MS)
                        delay(event.durationMs)
                    }
                    else -> {
                        val bytes = encode(event)
                        inputBytes += bytes.size
                        require(inputBytes <= MAX_INPUT_BYTES)
                        session.backend!!.write(bytes)
                    }
                }
                synchronized(lock) { session.lastActivity = now() }
            }
        }
    }

    suspend fun read(
        owner: String,
        id: String,
        cursor: Long? = null,
        maxBytes: Int = 64 * 1024,
        waitMs: Long = 0,
    ): TerminalReadResult {
        require(maxBytes in 1..MAX_READ_BYTES && waitMs in 0..MAX_WAIT_MS)
        expireSessions()
        val session = owned(owner, id)
        val (initial, generation) = synchronized(lock) {
            session.lastActivity = now()
            session.read(cursor, maxBytes) to session.changed.value
        }
        if (waitMs == 0L || initial.bytes.isNotEmpty() || initial.state.isFinished ||
            cursor != null && cursor > initial.endCursor
        ) return initial
        withTimeoutOrNull(waitMs) { session.changed.first { it != generation } }
        return synchronized(lock) {
            session.lastActivity = now()
            session.read(cursor, maxBytes)
        }
    }

    suspend fun resize(owner: String, id: String, cols: Int, rows: Int) {
        require(cols in 1..500 && rows in 1..200)
        expireSessions()
        val session = owned(owner, id)
        session.writeMutex.withLock {
            synchronized(lock) { check(session.state == TerminalState.RUNNING) { "terminal is not running" } }
            session.backend!!.resize(cols, rows)
        }
        synchronized(lock) {
            session.cols = cols
            session.rows = rows
            session.lastActivity = now()
        }
    }

    suspend fun kill(owner: String, id: String): TerminalInfo {
        expireSessions()
        val session = owned(owner, id)
        terminate(session, TerminalState.KILLED)
        return synchronized(lock) { session.info() }
    }

    /** Task 5 calls this when the capability token for a conversation is revoked. */
    suspend fun revokeOwner(owner: String) {
        val owned = synchronized(lock) { sessions.values.filter { it.owner == owner && it.state.isActive } }
        owned.forEach { terminate(it, TerminalState.KILLED) }
    }

    /** Runs timeout and retention cleanup immediately; also used by the maintenance coroutine. */
    suspend fun expireSessions() {
        val timestamp = now()
        val expired = synchronized(lock) {
            sessions.entries.removeAll { (_, session) ->
                session.finishedAt?.let { timestamp - it >= FINISHED_RETENTION_MS } == true
            }
            sessions.values.filter { session ->
                session.state.isActive &&
                    (timestamp - session.lastActivity >= IDLE_TIMEOUT_MS || timestamp - session.createdAt >= MAX_LIFETIME_MS)
            }.onEach { it.finish(TerminalState.EXPIRED, timestamp) }
        }
        expired.forEach { session -> runCatching { session.backend?.kill() } }
    }

    private fun launchWorkers(session: Session, terminal: TerminalBackendSession) {
        val reader = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val bytes = terminal.read(64 * 1024)
                    coroutineContext.ensureActive()
                    if (bytes == null) continue
                    if (bytes.isEmpty()) break
                    synchronized(lock) {
                        session.ring.append(bytes)
                        trimGlobalOutput()
                    }
                    synchronized(lock) { session.changed.value = session.changed.value + 1 }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (session.state.isActive) session.finish(TerminalState.FAILED, now())
                }
                synchronized(lock) { session.changed.value = session.changed.value + 1 }
                runCatching { terminal.kill() }
            }
        }
        val waiter = scope.launch(start = CoroutineStart.LAZY) {
            var primaryFailure: Throwable? = null
            try {
                terminal.waitForExit()
                reader.join()
                synchronized(lock) {
                    if (session.state == TerminalState.RUNNING) session.finish(TerminalState.EXITED, now())
                }
            } catch (error: CancellationException) {
                primaryFailure = error
                throw error
            } catch (error: Throwable) {
                primaryFailure = error
            } finally {
                withContext(NonCancellable) {
                    if (primaryFailure != null) cleanupAfterWaitFailure(terminal, reader, primaryFailure)
                    val closeFailure = runCatching {
                        withTimeout(CLEANUP_TIMEOUT_MS) { terminal.close() }
                    }.exceptionOrNull()
                    if (closeFailure != null && primaryFailure != null) {
                        primaryFailure.addSuppressed(closeFailure)
                    }
                    if (primaryFailure !is CancellationException && primaryFailure != null) {
                        synchronized(lock) {
                            if (session.state.isActive) session.finish(TerminalState.FAILED, now())
                        }
                    }
                }
            }
        }
        synchronized(lock) {
            session.reader = reader
            session.waiter = waiter
        }
        reader.start()
        waiter.start()
    }

    private suspend fun cleanupAfterWaitFailure(
        terminal: TerminalBackendSession,
        reader: Job,
        primaryFailure: Throwable,
    ) {
        val firstKill = runCatching {
            withTimeout(CLEANUP_TIMEOUT_MS) { terminal.kill() }
        }
        firstKill.exceptionOrNull()?.let(primaryFailure::addSuppressed)
        if (firstKill.isFailure) {
            runCatching {
                withTimeout(CLEANUP_TIMEOUT_MS) { terminal.kill() }
            }.exceptionOrNull()?.let(primaryFailure::addSuppressed)
        }
        if (withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { reader.join(); true } != true) {
            reader.cancel()
            if (withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { reader.join(); true } != true) {
                primaryFailure.addSuppressed(IllegalStateException("terminal reader did not stop after cancellation"))
            }
        }
    }

    private suspend fun terminate(session: Session, state: TerminalState) {
        val terminal = synchronized(lock) {
            if (!session.state.isActive) return
            session.finish(state, now())
            session.backend
        }
        synchronized(lock) { session.changed.value = session.changed.value + 1 }
        if (terminal != null) {
            try {
                terminal.kill()
            } catch (_: Throwable) {
                synchronized(lock) { session.finish(TerminalState.FAILED, now(), replaceFinished = true) }
                synchronized(lock) { session.changed.value = session.changed.value + 1 }
            }
        }
    }

    private fun owned(owner: String, id: String): Session = synchronized(lock) {
        sessions[id]?.also { check(it.owner == owner) { "terminal is owned by another conversation" } }
            ?: error("terminal not found")
    }

    private fun trimGlobalOutput() {
        var excess = sessions.values.sumOf { it.ring.size }.toLong() - MAX_GLOBAL_OUTPUT_BYTES
        if (excess <= 0) return
        for (session in sessions.values.sortedBy(Session::createdAt)) {
            val discarded = session.ring.discard(excess.coerceAtMost(session.ring.size.toLong()).toInt())
            excess -= discarded
            if (excess == 0L) break
        }
    }

    private fun encode(event: TerminalEvent): ByteArray = when (event.type) {
        TerminalEvent.Type.TEXT -> event.value.orEmpty().toByteArray().also { require(it.size <= MAX_TEXT_BYTES) }
        TerminalEvent.Type.KEY -> key(event.value ?: error("key is required"))
        TerminalEvent.Type.CHORD -> chord(event.value ?: error("chord is required"))
        TerminalEvent.Type.SLEEP -> error("sleep has no bytes")
    }

    private fun key(value: String): ByteArray = mapOf("ENTER" to "\r", "ESC" to "\u001b", "TAB" to "\t", "BACKSPACE" to "\u007f", "UP" to "\u001b[A", "DOWN" to "\u001b[B", "LEFT" to "\u001b[D", "RIGHT" to "\u001b[C", "HOME" to "\u001b[H", "END" to "\u001b[F", "INSERT" to "\u001b[2~", "DELETE" to "\u001b[3~", "PAGE_UP" to "\u001b[5~", "PAGE_DOWN" to "\u001b[6~").getValue(value).toByteArray()
    private fun chord(value: String): ByteArray = when {
        value.startsWith("CTRL-") -> byteArrayOf((value.removePrefix("CTRL-").single().uppercaseChar().code - 'A'.code + 1).toByte())
        value.startsWith("ALT-") -> byteArrayOf(0x1b, value.removePrefix("ALT-").single().code.toByte())
        value == "SHIFT-TAB" -> "\u001b[Z".toByteArray()
        else -> error("unsupported chord: $value")
    }

    private inner class Session(
        val id: String,
        val owner: String,
        val environment: EnvironmentSnapshot,
        var cols: Int,
        var rows: Int,
        val createdAt: Long,
    ) {
        val writeMutex = Mutex()
        val ring = ByteRing()
        val changed = MutableStateFlow(0L)
        var backend: TerminalBackendSession? = null
        var state = TerminalState.STARTING
        var lastActivity = createdAt
        var finishedAt: Long? = null
        var reader: Job? = null
        var waiter: Job? = null

        fun finish(newState: TerminalState, timestamp: Long, replaceFinished: Boolean = false) {
            if (state.isFinished && !replaceFinished) return
            state = newState
            finishedAt = timestamp
            changed.value = changed.value + 1
        }

        fun read(cursor: Long?, max: Int): TerminalReadResult {
            val result = ring.read(cursor ?: ring.end, max)
            return TerminalReadResult(result.bytes, result.cursor, result.end, state, result.expired, result.oldest)
        }

        fun info() = TerminalInfo(id, environment.id, state, cols, rows, ring.end, ring.end)
    }

    internal data class RingRead(val bytes: ByteArray, val cursor: Long, val end: Long, val expired: Boolean, val oldest: Long)

    internal class ByteRing(private val capacity: Int = MAX_SESSION_OUTPUT_BYTES) {
        private var data = ByteArray(0)
        var base = 0L
            private set
        var end = 0L
            private set
        val size: Int get() = data.size

        fun append(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            data = (data + bytes).takeLast(capacity).toByteArray()
            end += bytes.size
            base = end - data.size
        }

        fun discard(count: Int): Long {
            val actual = count.coerceAtMost(data.size)
            data = data.copyOfRange(actual, data.size)
            base += actual
            return actual.toLong()
        }

        fun read(cursor: Long, max: Int): RingRead {
            val expired = cursor < base
            val start = cursor.coerceIn(base, end)
            val offset = (start - base).toInt()
            val bytes = data.copyOfRange(offset, minOf(data.size, offset + max))
            return RingRead(bytes, start, end, expired, base)
        }
    }

    private val TerminalState.isActive: Boolean get() = this == TerminalState.STARTING || this == TerminalState.RUNNING
    private val TerminalState.isFinished: Boolean get() = !isActive

    companion object {
        const val MAX_SESSIONS = 4
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_INPUT_BYTES = 256 * 1024
        const val MAX_SLEEP_MS = 10_000L
        const val MAX_TOTAL_SLEEP_MS = 30_000L
        const val MAX_READ_BYTES = 1024 * 1024
        const val MAX_WAIT_MS = 30_000L
        const val MAX_SESSION_OUTPUT_BYTES = 8 * 1024 * 1024
        const val MAX_GLOBAL_OUTPUT_BYTES = 32L * 1024 * 1024
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        const val MAX_LIFETIME_MS = 30 * 60 * 1000L
        const val FINISHED_RETENTION_MS = 5 * 60 * 1000L
        private const val MAINTENANCE_INTERVAL_MS = 60_000L
        private const val CLEANUP_TIMEOUT_MS = 1_000L
    }
}
