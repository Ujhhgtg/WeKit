package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType

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
        val handle = NativePty.start(argv.joinToString("\u0000"), environmentVariables.map { "${it.key}=${it.value}" }.joinToString("\u0000"), workingDirectory ?: environment.workingDirectory, cols, rows)
        return TerminalBackendStart(NativeSession(handle), environment)
    }

    private class NativeSession(private val handle: Long) : TerminalBackendSession {
        override suspend fun write(bytes: ByteArray) { NativePty.write(handle, bytes) }
        override suspend fun read(maxBytes: Int): ByteArray = NativePty.read(handle, maxBytes)
        override suspend fun resize(cols: Int, rows: Int) { NativePty.resize(handle, cols, rows) }
        override suspend fun waitForExit(): Int? = NativePty.waitForExit(handle)
        override suspend fun kill() { NativePty.kill(handle) }
    }

    private object NativePty {
        init { try { System.loadLibrary("wekit_native") } catch (_: UnsatisfiedLinkError) { } }
        external fun start(argv: String, environment: String, cwd: String, cols: Int, rows: Int): Long
        external fun write(handle: Long, bytes: ByteArray)
        external fun read(handle: Long, maxBytes: Int): ByteArray
        external fun resize(handle: Long, cols: Int, rows: Int)
        external fun waitForExit(handle: Long): Int?
        external fun kill(handle: Long)
    }
}
