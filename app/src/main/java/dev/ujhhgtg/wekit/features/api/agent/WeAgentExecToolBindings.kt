package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import kotlinx.coroutines.currentCoroutineContext

object WeAgentExecToolBindings {
    @AgentTool(name = "exec", description = "Run a bounded non-interactive shell command in the active Linux environment and return its output and exit metadata.", sideEffect = true, group = AgentTool.BUILTIN_FS)
    suspend fun exec(
        @AgentToolParam("Shell command source") command: String,
        @AgentToolParam("Positive timeout in milliseconds") timeout_ms: Long?,
    ): String {
        val environment = currentCoroutineContext()[AgentSessionContext]?.environment
            ?: error("no active Linux environment context")
        val result = WeAgentService.linuxEnvironmentManager.exec(environment.id, command, timeout_ms ?: 60_000L)
        return buildString {
            append("exit_code=").append(result.exitCode).append('\n')
            append("timed_out=").append(result.timedOut).append('\n')
            append("elapsed_ms=").append(result.elapsedMillis).append('\n')
            append("stdout:\n").append(result.stdout).append("\nstderr:\n").append(result.stderr)
            result.spillPath?.let { append("\noutput_spill_path=").append(it) }
        }
    }
}
