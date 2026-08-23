package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot

enum class TerminalState { STARTING, RUNNING, EXITED, KILLED, FAILED, EXPIRED }

data class TerminalEvent(val type: Type, val value: String? = null, val durationMs: Long = 0) {
    enum class Type { TEXT, KEY, CHORD, SLEEP }
}

data class TerminalReadResult(
    val bytes: ByteArray,
    val cursor: Long,
    val endCursor: Long,
    val state: TerminalState,
    val cursorExpired: Boolean = false,
    val oldestCursor: Long = cursor,
)

data class TerminalBackendStart(
    val session: TerminalBackendSession,
    val environment: EnvironmentSnapshot,
)

interface TerminalBackendSession {
    suspend fun write(bytes: ByteArray)
    /** Returns null when no bytes are ready yet and an empty array at EOF. */
    suspend fun read(maxBytes: Int): ByteArray?
    suspend fun resize(cols: Int, rows: Int)
    suspend fun waitForExit(): Int?
    suspend fun kill()
    suspend fun close()
}

data class TerminalInfo(
    val id: String,
    val environmentId: String,
    val state: TerminalState,
    val cols: Int,
    val rows: Int,
    val cursor: Long,
    val endCursor: Long,
)
