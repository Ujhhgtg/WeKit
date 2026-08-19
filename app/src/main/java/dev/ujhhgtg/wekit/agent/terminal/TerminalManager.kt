package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TerminalManager(
    private val backend: TerminalBackend,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun start(owner: String, environment: EnvironmentSnapshot, argv: List<String> = listOf(environment.shell), workingDirectory: String? = null, environmentVariables: Map<String, String> = emptyMap(), cols: Int = 80, rows: Int = 24): TerminalInfo {
        require(cols in 1..500 && rows in 1..200)
        require(sessions.values.count { it.owner == owner && it.state == TerminalState.RUNNING } < MAX_SESSIONS)
        val started = backend.start(environment, argv, workingDirectory, environmentVariables, cols, rows)
        val session = Session(UUID.randomUUID().toString(), owner, environment, started.session, cols, rows, now())
        sessions[session.id] = session
        session.reader = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { session.readLoop() }
        session.waiter = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { session.finish(started.session.waitForExit()) }
        return session.info()
    }

    fun list(owner: String): List<TerminalInfo> = sessions.values.filter { it.owner == owner }.map { it.info() }.sortedBy { it.id }

    suspend fun write(owner: String, id: String, events: List<TerminalEvent>) {
        val session = owned(owner, id)
        require(events.size <= 256) { "too many terminal events" }
        var total = 0L
        session.writeMutex.withLock {
            for (event in events) {
                when (event.type) {
                    TerminalEvent.Type.SLEEP -> {
                        require(event.durationMs in 0..MAX_SLEEP_MS)
                        total += event.durationMs
                        require(total <= MAX_TOTAL_SLEEP_MS)
                        delay(event.durationMs)
                    }
                    else -> {
                        val bytes = encode(event)
                        total += bytes.size
                        require(total <= MAX_INPUT_BYTES)
                        session.backend.write(bytes)
                    }
                }
                session.lastActivity = now()
            }
        }
    }

    suspend fun read(owner: String, id: String, cursor: Long? = null, maxBytes: Int = 64 * 1024, waitMs: Long = 0): TerminalReadResult {
        require(maxBytes in 1..MAX_READ_BYTES && waitMs in 0..MAX_WAIT_MS)
        val session = owned(owner, id)
        if (waitMs > 0 && session.ring.end == (cursor ?: session.ring.end)) delay(waitMs)
        return session.read(cursor, maxBytes)
    }

    suspend fun resize(owner: String, id: String, cols: Int, rows: Int) {
        require(cols in 1..500 && rows in 1..200)
        val session = owned(owner, id)
        session.writeMutex.withLock { session.backend.resize(cols, rows) }
        session.cols = cols; session.rows = rows; session.lastActivity = now()
    }

    suspend fun kill(owner: String, id: String): TerminalInfo {
        val session = owned(owner, id)
        session.writeMutex.withLock { session.backend.kill() }
        session.state = TerminalState.KILLED
        session.reader?.cancel(); session.waiter?.cancel()
        return session.info()
    }

    private fun owned(owner: String, id: String): Session = sessions[id]?.also { check(it.owner == owner) { "terminal is owned by another conversation" } } ?: error("terminal not found")

    private fun encode(event: TerminalEvent): ByteArray = when (event.type) {
        TerminalEvent.Type.TEXT -> event.value.orEmpty().also { require(it.toByteArray().size <= MAX_TEXT_BYTES) }.toByteArray()
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

    private inner class Session(val id: String, val owner: String, val environment: EnvironmentSnapshot, val backend: TerminalBackendSession, var cols: Int, var rows: Int, var lastActivity: Long) {
        val writeMutex = Mutex(); val ring = ByteRing(); var state = TerminalState.STARTING; var reader: kotlinx.coroutines.Job? = null; var waiter: kotlinx.coroutines.Job? = null
        init { state = TerminalState.RUNNING }
        suspend fun readLoop() { try { while (state == TerminalState.RUNNING) { val bytes = backend.read(64 * 1024); if (bytes.isEmpty()) break; ring.append(bytes) } } catch (_: CancellationException) { } }
        suspend fun finish(exitCode: Int?) { if (state == TerminalState.RUNNING) state = if (exitCode == null) TerminalState.EXITED else TerminalState.EXITED }
        fun read(cursor: Long?, max: Int): TerminalReadResult { val result = ring.read(cursor ?: ring.end, max); return TerminalReadResult(result.bytes, result.cursor, result.end, state, result.expired, result.oldest) }
        fun info() = TerminalInfo(id, environment.id, state, cols, rows, ring.end, ring.end)
    }

    private data class RingRead(val bytes: ByteArray, val cursor: Long, val end: Long, val expired: Boolean, val oldest: Long)
    private class ByteRing(private val capacity: Int = 8 * 1024 * 1024) {
        private var data = ByteArray(0); var base = 0L; var end = 0L; fun append(bytes: ByteArray) { data = (data + bytes).takeLast(capacity).toByteArray(); end += bytes.size; base = end - data.size }
        fun read(cursor: Long, max: Int): RingRead { val expired = cursor < base; val start = maxOf(cursor, base); val offset = (start - base).toInt(); val bytes = data.copyOfRange(offset, minOf(data.size, offset + max)); return RingRead(bytes, start, end, expired, base) }
    }
    companion object { const val MAX_SESSIONS = 4; const val MAX_TEXT_BYTES = 64 * 1024; const val MAX_INPUT_BYTES = 256 * 1024; const val MAX_SLEEP_MS = 10_000L; const val MAX_TOTAL_SLEEP_MS = 30_000L; const val MAX_READ_BYTES = 1024 * 1024; const val MAX_WAIT_MS = 30_000L }
}
