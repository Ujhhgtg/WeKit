package dev.ujhhgtg.wekit.agent.workspace

import dev.ujhhgtg.wekit.agent.workspace.WorkspaceStore.cacheDir
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File

/**
 * Owns the real directories backing workspaces and memory (§7, §8), all under
 * `KnownPaths.moduleData/agent/`:
 *  - workspaces live in `.../agent/workspaces/<name>/`
 *  - memory lives in `.../agent/memory/` with a bootstrapped, undeletable `MEMORY.md` index.
 */
object WorkspaceStore {

    private val TAG = "WorkspaceStore"

    /** Characters/names forbidden in a workspace name (§7). */
    private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|]""")

    /** Soft cap on MEMORY.md before the settings UI nudges the user to tidy up (§8, ~8KB). */
    const val MEMORY_INDEX_SOFT_LIMIT_BYTES = 8 * 1024

    private val agentRoot: File by lazy {
        File(KnownPaths.moduleData.toFile(), "agent").apply { mkdirs() }
    }

    private val workspacesRoot: File by lazy { File(agentRoot, "workspaces").apply { mkdirs() } }

    val memoryDir: File by lazy { File(agentRoot, "memory").apply { mkdirs() } }

    /** Scratch directory for large tool-output spill files (always available, never exposed to the user). */
    val cacheDir: File by lazy { File(agentRoot, "cache").apply { mkdirs() } }

    /** Result of validating a proposed workspace name. */
    sealed interface NameValidation {
        object Ok : NameValidation
        data class Invalid(val reason: InvalidNameReason) : NameValidation
    }

    enum class InvalidNameReason { EMPTY, DOT_PATH, ILLEGAL_CHARACTER }

    fun validateWorkspaceName(name: String): NameValidation {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> NameValidation.Invalid(InvalidNameReason.EMPTY)
            trimmed == "." || trimmed == ".." -> NameValidation.Invalid(InvalidNameReason.DOT_PATH)
            ILLEGAL_CHARS.containsMatchIn(trimmed) -> NameValidation.Invalid(InvalidNameReason.ILLEGAL_CHARACTER)
            else -> NameValidation.Ok
        }
    }

    /** Returns (creating if needed) the directory for a workspace by name. Null if the name is invalid. */
    fun workspaceDir(name: String): File? {
        if (validateWorkspaceName(name) != NameValidation.Ok) return null
        return File(workspacesRoot, name.trim()).apply { mkdirs() }
    }

    /**
     * Renames a workspace's real directory from [oldName] to [newName], preserving its files.
     * Returns true on success. No-op success if the names are equal. Fails if [newName] is invalid
     * or already exists.
     */
    fun renameWorkspaceDir(oldName: String, newName: String): Boolean {
        val n = newName.trim()
        if (n == oldName.trim()) return true
        if (validateWorkspaceName(n) != NameValidation.Ok) return false
        val src = File(workspacesRoot, oldName.trim())
        val dst = File(workspacesRoot, n)
        if (dst.exists()) return false
        if (!src.exists()) {
            dst.mkdirs(); return true
        }
        return runCatching { src.renameTo(dst) }.getOrDefault(false)
    }

    /** Ensures `memory/MEMORY.md` exists with the seed index header, returning the file. */
    fun ensureMemoryIndex(): File {
        val index = File(memoryDir, WorkspaceVfs.MEMORY_INDEX)
        if (!index.exists()) {
            runCatching { index.writeText(SEED_MEMORY_INDEX) }
                .onFailure { WeLogger.w(TAG, "failed to seed MEMORY.md", it) }
        }
        return index
    }

    /** Current MEMORY.md content (empty string if unreadable). */
    fun readMemoryIndex(): String =
        runCatching { ensureMemoryIndex().readText() }.getOrDefault("")

    /** True when MEMORY.md exceeds the soft size limit (§8). */
    fun isMemoryIndexOversized(): Boolean =
        runCatching { ensureMemoryIndex().length() > MEMORY_INDEX_SOFT_LIMIT_BYTES }.getOrDefault(false)

    /**
     * Builds a [WorkspaceVfs] for a turn. [cacheDir] is always provided — it is the always-on
     * scratch space for large network-tool outputs.
     * @param workspaceName the session's bound workspace name, or null when unbound.
     * @param memoryEnabled whether the `/memory/` root should be exposed.
     */
    fun buildVfs(workspaceName: String?, memoryEnabled: Boolean): WorkspaceVfs {
        val wsRoot = workspaceName?.let { workspaceDir(it) }
        val memRoot = if (memoryEnabled) memoryDir.also { ensureMemoryIndex() } else null
        return WorkspaceVfs(wsRoot, memRoot, cacheDir)
    }

    private val SEED_MEMORY_INDEX = """
        # Memory Index

    """.trimIndent() + "\n"
}
