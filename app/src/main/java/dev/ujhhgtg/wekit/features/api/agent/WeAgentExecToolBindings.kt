package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.engine.ToolExecutionContext
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import kotlinx.coroutines.currentCoroutineContext

object WeAgentExecToolBindings {
    @AgentTool(name = "exec", description = "Run a bounded non-interactive shell command in the active Linux environment and return its output and exit metadata.", sideEffect = true, group = AgentTool.BUILTIN_FS)
    suspend fun exec(
        @AgentToolParam("Shell command source") command: String,
        @AgentToolParam("Positive timeout in milliseconds") timeout_ms: Long?,
    ): String {
        val coroutineContext = currentCoroutineContext()
        val session = coroutineContext[AgentSessionContext] ?: error("no active agent session")
        val environment = session.environment ?: error("no active Linux environment context")
        val artifact = WeAgentService.linuxEnvironmentManager.ensureBridge(environment.id)
            ?: error("invoke_tool is unavailable in ${environment.displayName}")
        val result = WeAgentService.toolBridgeServer.withOneShot(
            owner = session.sessionId,
            environmentId = environment.id,
            parentToolCallId = coroutineContext[ToolExecutionContext]?.callId,
            visibility = session.toolVisibility,
            context = coroutineContext,
        ) { endpoint ->
            WeAgentService.linuxEnvironmentManager.exec(
                environment.id,
                command,
                timeout_ms ?: 60_000L,
                endpoint.environment() + mapOf(
                    "WEAGENT_INVOKE_TOOL" to artifact.executablePath,
                    "PATH" to "${artifact.binDirectory}:${System.getenv("PATH").orEmpty()}",
                ),
            )
        }
        return buildString {
            append("exit_code=").append(result.exitCode).append('\n')
            append("timed_out=").append(result.timedOut).append('\n')
            append("elapsed_ms=").append(result.elapsedMillis).append('\n')
            append("stdout:\n").append(result.stdout).append("\nstderr:\n").append(result.stderr)
            result.spillPath?.let { append("\noutput_spill_path=").append(it) }
        }
    }
}
