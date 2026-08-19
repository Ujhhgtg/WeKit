package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

class NativeTerminalBackend : TerminalBackend {
    override suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart {
        require(environment.type == LinuxEnvironmentType.NATIVE) { "native backend requires native environment" }
        require(argv.isNotEmpty()) { "terminal command cannot be empty" }
        val handle = NativePty.start(argv.toTypedArray(), environmentVariables.map { "${it.key}=${it.value}" }.toTypedArray(), workingDirectory ?: environment.workingDirectory, cols, rows)
        check(handle != 0L) { "failed to start native PTY" }
        return TerminalBackendStart(NativeSession(handle), environment)
    }

    private class NativeSession(handle: Long) : TerminalBackendSession {
        private val handle = AtomicLong(handle)
        private val lifecycleLock = ReentrantReadWriteLock()
        private inline fun <T> withHandle(action: (Long) -> T): T = lifecycleLock.read {
            action(handle.get().also { check(it != 0L) { "terminal is closed" } })
        }
        override suspend fun write(bytes: ByteArray) { check(withHandle { NativePty.write(it, bytes) }) { "native PTY write failed" } }
        override suspend fun read(maxBytes: Int): ByteArray? = withHandle { handle ->
            val buffer = ByteArray(maxBytes)
            when (val count = NativePty.read(handle, buffer)) {
                NativePty.READ_TIMEOUT -> null
                NativePty.READ_EOF -> ByteArray(0)
                NativePty.READ_ERROR -> error("native PTY read failed")
                else -> buffer.copyOf(count)
            }
        }
        override suspend fun resize(cols: Int, rows: Int) { check(withHandle { NativePty.resize(it, cols, rows) }) { "native PTY resize failed" } }
        override suspend fun waitForExit(): Int? = withHandle(NativePty::waitForExit).let {
            check(it != NativePty.NATIVE_WAIT_ERROR) { "native PTY wait failed" }
            if (it >= 0) it else null
        }
        override suspend fun kill() { check(withHandle(NativePty::kill)) { "native PTY kill failed" } }
        override suspend fun close() {
            val writeLock = lifecycleLock.writeLock()
            check(writeLock.tryLock(CLOSE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "timed out closing native PTY" }
            try {
                handle.getAndSet(0L).takeIf { it != 0L }?.let(NativePty::close)
            } finally {
                writeLock.unlock()
            }
        }

        companion object {
            private const val CLOSE_LOCK_TIMEOUT_MS = 1_000L
        }
    }

    private object NativePty {
        init { try { System.loadLibrary("wekit_native") } catch (_: UnsatisfiedLinkError) { } }
        external fun start(argv: Array<String>, environment: Array<String>, cwd: String, cols: Int, rows: Int): Long
        external fun write(handle: Long, bytes: ByteArray): Boolean
        external fun read(handle: Long, buffer: ByteArray): Int
        external fun resize(handle: Long, cols: Int, rows: Int): Boolean
        external fun waitForExit(handle: Long): Int
        external fun kill(handle: Long): Boolean
        external fun close(handle: Long)
        const val NATIVE_WAIT_ERROR = -2
        const val READ_TIMEOUT = 0
        const val READ_EOF = -1
        const val READ_ERROR = -2
    }
}
