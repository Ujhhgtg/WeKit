package dev.ujhhgtg.wekit.activity.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** A failed rollback preserves [backupRoot] for recovery. */
class BackupReplacementRollbackException(
    val backupRoot: File,
    val originalFailure: Throwable,
    rollbackFailures: List<Throwable>,
) : IOException("Backup replacement rollback failed; original files retained at ${backupRoot.path}", originalFailure) {
    init {
        rollbackFailures.forEach(::addSuppressed)
    }
}

/**
 * Replacement semantics for the files owned by the module. The caller closes the database first.
 * Success leaves oldRoot for the caller to remove; a failed
 * replacement restores only completed operations, in reverse order.
 */
object BackupManagedDataReplacement {
    const val MARKER_NAME = ".restored-backup"

    fun replace(
        root: File,
        staging: File,
        oldRoot: File,
        liveDatabase: File,
        managedDirectories: List<String>,
        managedFiles: List<String>,
        stagedDatabaseName: String = "wekit.sqlite",
    ) {
        require(!exists(oldRoot)) { "Backup recovery directory already exists" }
        val owned = managedDirectories + managedFiles
        require(owned.distinct().size == owned.size) { "Managed replacement paths overlap" }
        owned.forEach { relative ->
            require(relative.isNotEmpty() && !relative.startsWith('/') &&
                relative.split('/').none { it.isEmpty() || it == "." || it == ".." } && '\\' !in relative) {
                "Invalid managed replacement path"
            }
            require(relative != MARKER_NAME && owned.none { it != relative && relative.startsWith("$it/") }) {
                "Managed replacement paths overlap"
            }
        }
        val databaseFiles = listOf(liveDatabase) + listOf("-wal", "-shm", "-journal").map {
            File(liveDatabase.path + it)
        }
        require(owned.none { relative -> databaseFiles.any { File(root, relative).absoluteFile == it.absoluteFile } }) {
            "Managed paths include the database"
        }
        ensureDirectory(root)
        ensureDirectory(oldRoot)
        val undo = mutableListOf<Undo>()
        try {
            databaseFiles.forEach { current -> saveExisting(current, File(oldRoot, current.name), undo) }
            install(File(staging, stagedDatabaseName), liveDatabase, directory = false, required = true, undo)

            // Only existence matters: never import the archive's marker contents.
            val replacementMarker = File(staging, ".restore-marker")
            replacementMarker.writeText("1")
            saveExisting(File(root, MARKER_NAME), File(oldRoot, MARKER_NAME), undo)
            install(replacementMarker, File(root, MARKER_NAME), directory = false, required = true, undo)

            managedDirectories.forEach { relative ->
                replaceOwned(root, staging, oldRoot, relative, directory = true, undo)
            }
            managedFiles.forEach { relative ->
                replaceOwned(root, staging, oldRoot, relative, directory = false, undo)
            }
        } catch (failure: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            undo.asReversed().forEach { action ->
                try {
                    when (action) {
                        is Undo.Installed -> removeInstalled(action.path)
                        is Undo.Saved -> move(action.backup, action.original)
                    }
                } catch (rollbackFailure: Throwable) {
                    rollbackFailures += rollbackFailure
                }
            }
            if (rollbackFailures.isNotEmpty()) {
                throw BackupReplacementRollbackException(oldRoot, failure, rollbackFailures)
            }
            // Every saved original is back in place. Empty backup directories are disposable.
            oldRoot.deleteRecursively()
            throw failure
        }
    }

    private sealed interface Undo {
        data class Saved(val original: File, val backup: File) : Undo
        data class Installed(val path: File) : Undo
    }

    private fun replaceOwned(
        root: File,
        staging: File,
        oldRoot: File,
        relative: String,
        directory: Boolean,
        undo: MutableList<Undo>,
    ) {
        val current = File(root, relative)
        saveExisting(current, File(oldRoot, relative), undo)
        install(File(staging, relative), current, directory, required = false, undo)
    }

    private fun saveExisting(current: File, backup: File, undo: MutableList<Undo>) {
        if (!exists(current)) return
        move(current, backup)
        undo += Undo.Saved(current, backup)
    }

    private fun install(source: File, destination: File, directory: Boolean, required: Boolean, undo: MutableList<Undo>) {
        if (exists(source)) {
            check(!Files.isSymbolicLink(source.toPath()) && if (directory) source.isDirectory else source.isFile) {
                "Unexpected replacement path type: ${source.name}"
            }
            move(source, destination)
            undo += Undo.Installed(destination)
        } else if (required) {
            throw IOException("Required replacement file is missing: ${source.name}")
        } else if (directory) {
            ensureDirectory(destination.parentFile!!)
            check(destination.mkdir()) { "Unable to create managed directory: ${destination.path}" }
            undo += Undo.Installed(destination)
        }
    }

    private fun move(source: File, destination: File) {
        check(exists(source)) { "Recovery source is missing: ${source.path}" }
        check(!exists(destination)) { "Replacement destination already exists: ${destination.path}" }
        ensureDirectory(destination.parentFile!!)
        check(source.renameTo(destination)) { "Unable to move managed path: ${source.path}" }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create directory: ${directory.path}" }
    }

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun removeInstalled(file: File) {
        if (!exists(file)) return
        if (!file.isDirectory || Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Unable to remove installed file: ${file.path}" }
            return
        }
        // Do not follow a link inside an installed tree while undoing that tree.
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
