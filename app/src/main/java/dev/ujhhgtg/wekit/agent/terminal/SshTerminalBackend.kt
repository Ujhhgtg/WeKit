package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.bridge.ToolBridgeServer
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.SshConnectionManager
import dev.ujhhgtg.wekit.agent.environment.SshReverseForward
import dev.ujhhgtg.wekit.agent.environment.SshTerminalConnection
import java.util.concurrent.atomic.AtomicBoolean

class SshTerminalBackend(
    private val connection: suspend (String) -> SshConnectionManager,
) : TerminalBackend {
    override suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart {
        val manager = connection(environment.id)
        val localBridgePort = environmentVariables["WEAGENT_BRIDGE_PORT"]?.toIntOrNull()
        val forward = localBridgePort?.let { manager.openReverseForward(it) }
        return try {
            val remoteEnvironment = if (forward == null) environmentVariables else {
                environmentVariables + ToolBridgeServer.Endpoint(
                    localBridgePort,
                    environmentVariables.getValue("WEAGENT_BRIDGE_TOKEN"),
                ).environment(forward.remotePort)
            }
            val command = buildCommand(argv, workingDirectory ?: environment.workingDirectory, remoteEnvironment)
            val terminal = manager.openTerminal(command, emptyMap(), cols, rows)
            TerminalBackendStart(Session(terminal, forward), environment)
        } catch (error: Throwable) {
            forward?.close()
            throw error
        }
    }

    private fun buildCommand(argv: List<String>, workingDirectory: String, environment: Map<String, String>): String {
        require(argv.isNotEmpty() && argv.none(String::isEmpty))
        val exports = environment.entries.joinToString(" ") { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            "$key=${quote(value)}"
        }
        return buildString {
            append("cd ").append(quote(workingDirectory)).append(" && ")
            if (exports.isNotEmpty()) append("export ").append(exports).append("; ")
            append("exec ").append(argv.joinToString(" ", transform = ::quote))
        }
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"

    private class Session(
        private val terminal: SshTerminalConnection,
        private val forward: SshReverseForward?,
    ) : TerminalBackendSession {
        private val closed = AtomicBoolean()
        override suspend fun write(bytes: ByteArray) = terminal.write(bytes)
        override suspend fun read(maxBytes: Int): ByteArray? = terminal.read(maxBytes)
        override suspend fun resize(cols: Int, rows: Int) = terminal.resize(cols, rows)
        override suspend fun waitForExit(): Int? = terminal.waitForExit()
        override suspend fun kill() {
            terminal.kill()
            close()
        }
        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                terminal.close()
            } finally {
                forward?.close()
            }
        }
    }
}
