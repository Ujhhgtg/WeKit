package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.agent.workspace.VfsContext
import dev.ujhhgtg.wekit.agent.workspace.WorkspaceVfs
import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import kotlinx.coroutines.currentCoroutineContext

/**
 * Filesystem `@AgentTool`s operating on the virtual `/workspace/`, `/memory/`, and `/cache/` roots.
 *
 * These resolve the active [WorkspaceVfs] from the [VfsContext] coroutine-context element that the
 * engine installs for the turn — so no session/workspace state is threaded through arguments. When
 * neither workspace nor memory is enabled the tools are hidden entirely by
 * [dev.ujhhgtg.wekit.agent.tool.BuiltinToolProvider.fsToolsVisible]; when only one root is active,
 * paths pointing at the other root fail with a clear error the model can correct.
 *
 * Read tools default to ENABLED (`sideEffect = false`); mutating tools default to MANUAL_APPROVAL
 * (`sideEffect = true`).
 */
object WeAgentFsToolBindings {

    private suspend fun vfs(): WorkspaceVfs {
        val ctx = currentCoroutineContext()[VfsContext]
            ?: throw IllegalStateException("no active workspace/memory context")
        return ctx.vfs
    }

    @WeKitOperation(
        name = "read_file",
        description = "Read a UTF-8 text file at a /workspace/, /memory/, or /cache/ path. " +
                "Reads the full file when `lines` is omitted. " +
                "Pass `lines` to select specific lines using a compact range spec: " +
                "comma-separated segments where each segment is a single line number (e.g. `5`) " +
                "or a start:end range (e.g. `1:10`). " +
                "Line numbers are 1-based and inclusive; omit either bound to mean first/last line; " +
                "negative numbers count from EOF (-1 = last line). " +
                "Examples: `1` (first line), `:-1` or `1:` or `1:-1` (whole file), " +
                "`1:2,5:6` (lines 1–2 and 5–6), `1:10,20:30,100:` (lines 1–10, 20–30, 100 to EOF). " +
                "The result is prefixed with a header showing the selected ranges and total line count.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun readFile(
        @Param("Virtual path, e.g. /workspace/notes.md or /memory/MEMORY.md or /cache/result.txt") path: String,
        @Param(
            "Optional line-range spec. Omit to read the whole file. " +
                    "Format: comma-separated segments, each either a 1-based line number (e.g. `42`) " +
                    "or a start:end range (e.g. `1:10`, `50:`, `:20`, `1:-1`). " +
                    "Negative indices count from EOF: -1 = last line."
        ) lines: String?,
    ): String = if (lines == null) {
        vfs().readFile(path)
    } else {
        vfs().readFileRanges(path, lines)
    }

    @WeKitOperation(
        name = "list_dir",
        description = "List the entries of a directory at a /workspace/, /memory/, or /cache/ path. Directories are suffixed with '/'.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun listDir(
        @Param("Virtual directory path, e.g. /workspace/ or /memory/ or /cache/") path: String,
    ): String = vfs().listDir(path)

    @WeKitOperation(
        name = "search_files",
        description = "Recursively search files under a /workspace/ or /memory/ path for a substring (case-insensitive). Returns matching path:line entries.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun searchFiles(
        @Param("Virtual root path to search under, e.g. /workspace/") path: String,
        @Param("Substring to search for") query: String,
    ): String = vfs().searchFiles(path, query)

    @WeKitOperation(
        name = "write_file",
        description = "Create or overwrite a UTF-8 text file at a /workspace/, /memory/, or /cache/ path. Creates parent directories as needed.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun writeFile(
        @Param("Virtual path to write, e.g. /workspace/notes.md") path: String,
        @Param("Full new file content") content: String,
    ): String = vfs().writeFile(path, content)

    @WeKitOperation(
        name = "append_file",
        description = "Append UTF-8 text to a file at a /workspace/, /memory/, or /cache/ path, creating it if absent.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun appendFile(
        @Param("Virtual path to append to") path: String,
        @Param("Text to append") content: String,
    ): String = vfs().appendFile(path, content)

    @WeKitOperation(
        name = "delete_file",
        description = "Delete a file at a /workspace/, /memory/, or /cache/ path. The /memory/MEMORY.md index cannot be deleted.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun deleteFile(
        @Param("Virtual path to delete") path: String,
    ): String = vfs().deleteFile(path)

    @WeKitOperation(
        name = "move_file",
        description = "Move or rename a file between two /workspace/, /memory/, or /cache/ paths.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_FS,
    )
    suspend fun moveFile(
        @Param("Source virtual path") from: String,
        @Param("Destination virtual path") to: String,
    ): String = vfs().moveFile(from, to)
}
