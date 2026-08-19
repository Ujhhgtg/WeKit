package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.terminal.TerminalEvent
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import kotlinx.coroutines.currentCoroutineContext

object WeAgentTerminalToolBindings {
    private suspend fun owner(): String = currentCoroutineContext()[AgentSessionContext]?.sessionId ?: error("no active agent session")
    private fun event(value: String): TerminalEvent {
        val split = value.indexOf(':')
        val type = if (split < 0) value else value.substring(0, split)
        val payload = if (split < 0) null else value.substring(split + 1)
        return when (type.lowercase()) {
            "text" -> TerminalEvent(TerminalEvent.Type.TEXT, payload)
            "key" -> TerminalEvent(TerminalEvent.Type.KEY, payload)
            "chord" -> TerminalEvent(TerminalEvent.Type.CHORD, payload)
            "sleep" -> TerminalEvent(TerminalEvent.Type.SLEEP, durationMs = payload!!.toLong())
            else -> error("unknown terminal event: $type")
        }
    }
    @AgentTool(name = "terminal_list", description = "List terminal sessions owned by this conversation.", sideEffect = false, group = AgentTool.BUILTIN_TERMINAL)
    suspend fun list(): String = WeAgentService.terminalManager.list(owner()).joinToString("\n") { "id=${it.id}, environment=${it.environmentId}, state=${it.state}, size=${it.cols}x${it.rows}, cursor=${it.cursor}" }
    @AgentTool(name = "terminal_start", description = "Start an interactive PTY shell in the active Linux environment.", sideEffect = true, group = AgentTool.BUILTIN_TERMINAL)
    suspend fun start(@AgentToolParam("Optional command") command: String?, @AgentToolParam("PTY columns") cols: Int?, @AgentToolParam("PTY rows") rows: Int?): String {
        val context = currentCoroutineContext()[AgentSessionContext] ?: error("no active agent session")
        val environment = context.environment ?: error("no active Linux environment")
        val info = WeAgentService.terminalManager.start(owner(), environment, command?.let { listOf(it) } ?: listOf(environment.shell), cols = cols ?: 80, rows = rows ?: 24)
        return "id=${info.id}, state=${info.state}, size=${info.cols}x${info.rows}"
    }
    @AgentTool(name = "terminal_write", description = "Write ordered terminal events.", sideEffect = true, group = AgentTool.BUILTIN_TERMINAL)
    suspend fun write(@AgentToolParam("Terminal session id") sessionId: String, @AgentToolParam("Events: text:value, key:ENTER, chord:CTRL-C, sleep:milliseconds") events: List<String>): String { WeAgentService.terminalManager.write(owner(), sessionId, events.map(::event)); return "OK" }
    @AgentTool(name = "terminal_read", description = "Read raw terminal output from a cursor.", sideEffect = false, group = AgentTool.BUILTIN_TERMINAL)
    suspend fun read(@AgentToolParam("Terminal session id") sessionId: String, @AgentToolParam("Output cursor") cursor: Long?, @AgentToolParam("Maximum bytes") maxBytes: Int?, @AgentToolParam("Maximum wait in milliseconds") waitMs: Long?): String {
        val result = WeAgentService.terminalManager.read(owner(), sessionId, cursor, maxBytes ?: 64 * 1024, waitMs ?: 0)
        return "cursor=${result.cursor}, end_cursor=${result.endCursor}, state=${result.state}, cursor_expired=${result.cursorExpired}, output=${result.bytes.toString(Charsets.ISO_8859_1)}"
    }
    @AgentTool(name = "terminal_resize", description = "Resize a terminal PTY.", sideEffect = false, group = AgentTool.BUILTIN_TERMINAL)
    suspend fun resize(@AgentToolParam("Terminal session id") sessionId: String, @AgentToolParam("Columns") cols: Int, @AgentToolParam("Rows") rows: Int): String { WeAgentService.terminalManager.resize(owner(), sessionId, cols, rows); return "OK" }
    @AgentTool(name = "terminal_kill", description = "Terminate a terminal process group.", sideEffect = false, group = AgentTool.BUILTIN_TERMINAL)
    suspend fun kill(@AgentToolParam("Terminal session id") sessionId: String): String { val info = WeAgentService.terminalManager.kill(owner(), sessionId); return "id=${info.id}, state=${info.state}" }
}
