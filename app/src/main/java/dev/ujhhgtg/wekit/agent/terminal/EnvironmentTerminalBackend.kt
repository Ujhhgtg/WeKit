package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.EnvironmentLease
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import java.util.concurrent.atomic.AtomicBoolean

class EnvironmentTerminalBackend
constructor(
    private val native: TerminalBackend = NativeTerminalBackend(),
    private val ssh: TerminalBackend? = null,
    private val acquireEnvironmentLease: suspend (String) -> EnvironmentLease? = { null },
) : TerminalBackend {
    override suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart {
        val lease = acquireEnvironmentLease(environment.id)
        return try {
            val started =
                startUnleased(environment, argv, workingDirectory, environmentVariables, cols, rows)
            if (lease == null) started
            else
                TerminalBackendStart(
                    LeasedTerminalSession(started.session, lease),
                    started.environment,
                )
        } catch (error: Throwable) {
            lease?.release()
            throw error
        }
    }

    private suspend fun startUnleased(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart =
        when (environment.type) {
            LinuxEnvironmentType.NATIVE ->
                native.start(environment, argv, workingDirectory, environmentVariables, cols, rows)
            LinuxEnvironmentType.SSH ->
                requireNotNull(ssh) { "SSH terminal backend is not configured" }
                    .start(environment, argv, workingDirectory, environmentVariables, cols, rows)
        }

    private class LeasedTerminalSession(
        private val delegate: TerminalBackendSession,
        private val lease: EnvironmentLease,
    ) : TerminalBackendSession by delegate {
        private val closed = AtomicBoolean()

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                delegate.close()
            } finally {
                lease.release()
            }
        }
    }
}
