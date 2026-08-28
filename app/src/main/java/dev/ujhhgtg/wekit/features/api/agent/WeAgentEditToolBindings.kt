package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.engine.AgentSessionContext
import dev.ujhhgtg.wekit.agent.environment.FileEditRequest
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
import kotlinx.coroutines.currentCoroutineContext

object WeAgentEditToolBindings {
    @AgentTool(name = "edit", description = "Replace exact UTF-8 text in a file in the active Linux environment, or create a missing/empty file when old_string is omitted.", sideEffect = true, group = AgentTool.BUILTIN_FS)
    suspend fun edit(
        @AgentToolParam("Absolute or working-directory-relative file path") path: String,
        @AgentToolParam("Exact old text; omit only for missing or empty-file creation") old_string: String?,
        @AgentToolParam("Replacement or new file content") new_string: String,
        @AgentToolParam("Replace every match instead of requiring exactly one") replace_all: Boolean?,
    ): String {
        val environment = currentCoroutineContext()[AgentSessionContext]?.environment
            ?: error("no active Linux environment context")
        WeAgentService.linuxEnvironmentManager.edit(environment.id, FileEditRequest(path, old_string, new_string, replace_all ?: false))
        return "File edited: $path"
    }
}
