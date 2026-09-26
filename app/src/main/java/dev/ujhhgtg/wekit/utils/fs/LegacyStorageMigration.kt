package dev.ujhhgtg.wekit.utils.fs

import android.content.Context
import dev.ujhhgtg.wekit.data.LegacyDocumentMigration
import dev.ujhhgtg.wekit.data.JsonDataMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.LegacyStorageMigration.run
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo

/**
 * One-shot migration from the pre-private-root module directories.
 *
 * The two old private roots and [KnownPaths.moduleRoot] all live below the host's
 * filesDir.  They are therefore migrated by renaming directory entries in place.  In
 * particular, an environment instance (including its rootfs) is moved as one directory;
 * migration never walks or opens files inside a rootfs.  Only the legacy external-storage
 * tree is copied, because it may be on a different filesystem and may not support rename.
 */
object LegacyStorageMigration {
    private const val TAG = "LegacyStorageMigration"
    private const val MARKER = ".legacy-storage-v2.done"

    /**
     * Result of the user-requested legacy-data cleanup.
     *
     * [removedPaths] contains only old WeKit paths whose migrated counterpart was present.  Any
     * source entry which cannot be paired with a destination is returned in [retainedPaths] and
     * is deliberately left untouched so a cleanup click can never discard an incomplete
     * migration.  [blockedReason] is set when the one-shot migration has not published its marker
     * yet; in that case no path is inspected or deleted.
     */
    data class LegacyCleanupResult(
        val removedPaths: List<String>,
        val retainedPaths: List<String>,
        val blockedReason: String? = null,
    ) {
        val removedCount: Int get() = removedPaths.size
    }

    fun run(context: Context) {
        // Imported data has its own sources; never mix in an older local external tree.
        if (!LegacyPaths.localSourcesAllowed) return
        val root = KnownPaths.moduleRoot
        root.toFile().mkdirs()
        val marker = root.resolve(MARKER).toFile()
        if (marker.isFile) return
        withLegacyLock(root) {
            if (marker.isFile) return@withLegacyLock
            runCatching {
                // External storage can be a separate mount, so retain copy/verify semantics
                // for this source.  It is intentionally separate from private-root moves.
                migrateExternal(root)
                migratePrivateRoot(LegacyPaths.privateExtensionRoot, root.resolve("extensions"))
                migratePrivateRoot(
                    LegacyPaths.privateAgentRoot,
                    root.resolve("agent"),
                    excluded = setOf("weagent.db", "weagent.db-wal", "weagent.db-shm", "weagent.db-journal"),
                )
                marker.writeText("1")
            }.onFailure { WeLogger.e(TAG, "legacy storage migration failed; sources were retained", it) }
        }
    }

    /**
     * Remove old WeKit storage after the one-shot migration has completed.
     *
     * This is intentionally separate from [run]: migration keeps its sources for rollback, while
     * this method is only called from an explicit settings action.  It never walks or deletes the
     * current `filesDir/wekit` tree.  Instead, each known legacy entry is removed only when the
     * corresponding new entry exists; unknown files and incomplete entries remain available for
     * recovery.  The exact legacy roots are module-owned paths, but they are not treated as a
     * blanket recursive-delete target because a failed/cancelled migration can leave user data
     * there.
     */
    fun cleanupOldData(context: Context): LegacyCleanupResult {
        // Avoid creating a fresh module root merely to report that migration has not run.  The
        // context is used as the authority for the new root; LegacyPaths still resolves the old
        // roots through HostInfo, which is initialized before settings can be shown.
        val root = File(context.filesDir, "wekit")
        val marker = File(root, MARKER)
        if (!marker.isFile && LegacyPaths.localSourcesAllowed) {
            return LegacyCleanupResult(
                removedPaths = emptyList(),
                retainedPaths = emptyList(),
                blockedReason = "legacy storage migration has not completed",
            )
        }
        if (!File(root, "wekit.db").isFile) {
            return LegacyCleanupResult(
                removedPaths = emptyList(),
                retainedPaths = emptyList(),
                blockedReason = "unified database is not available",
            )
        }
        if (!LegacyDocumentMigration.isCompleted()) {
            return LegacyCleanupResult(
                removedPaths = emptyList(),
                retainedPaths = emptyList(),
                blockedReason = "legacy document migration has not completed",
            )
        }

        val incomplete = runBlocking(Dispatchers.IO) { JsonDataMigration.incomplete() }
        if (incomplete.isNotEmpty()) {
            return LegacyCleanupResult(
                removedPaths = emptyList(), retainedPaths = emptyList(),
                blockedReason = "JSON migration is incomplete: ${incomplete.joinToString()}",
            )
        }

        val removed = ArrayList<String>()
        val retained = ArrayList<String>()
        withLegacyLock(root.toPath()) {
            // The marker can only be trusted while holding the same lock used by run().
            // A completed full restore deliberately supersedes the local legacy tree, even if
            // that tree never finished its original path migration. Both sources remain explicit.
            if (!marker.isFile && LegacyPaths.localSourcesAllowed) return@withLegacyLock
            listOf(
                LegacyPaths.externalModuleRoot.toFile(),
                LegacyPaths.privateExtensionRoot.toFile(),
                LegacyPaths.privateAgentRoot.toFile(),
            ).forEach { legacyRoot ->
                if (legacyRoot.exists() && legacyRoot.deleteRecursively()) {
                    removed += legacyRoot.absolutePath
                } else if (legacyRoot.exists()) {
                    retained += legacyRoot.absolutePath
                }
            }
            val mmkv = File(context.filesDir, "mmkv")
            listOf("wekit_prefs", "wekit_prefs.crc").forEach { name ->
                val legacyPrefs = File(mmkv, name)
                if (legacyPrefs.exists() && legacyPrefs.delete()) removed += legacyPrefs.absolutePath
                else if (legacyPrefs.exists()) retained += legacyPrefs.absolutePath
            }
            // The document migration has completed, so every runtime store reads the unified
            // database; the legacy JSON files are kept only as rollback copies. They were
            // never in the managed-files allow-list before, so a missing file is normal.
            LegacyDocumentMigration.cleanupFiles { file ->
                if (file.delete()) removed += file.absolutePath else retained += file.absolutePath
            }
            runBlocking(Dispatchers.IO) { JsonDataMigration.deleteArchivedDocuments() }
        }
        return LegacyCleanupResult(removed, retained)
    }

    private fun withLegacyLock(root: Path, block: () -> Unit) {
        root.toFile().mkdirs()
        FileChannel.open(
            root.resolve(".legacy-storage.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { block() }
        }
    }

    /**
     * Delete a legacy tree only entry-by-entry, and only where the destination counterpart exists.
     * Symlinks are retained: following them during a cleanup would make the safety boundary depend
     * on attacker-controlled link targets.
     */
    private fun cleanupTree(
        source: File,
        destination: File,
        removed: MutableList<String>,
        retained: MutableList<String>,
        deleteLegacyDatabase: Boolean = false,
    ) {
        if (!source.exists()) return
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) {
            retained += source.absolutePath
            return
        }
        if (source.isFile) {
            if (deleteLegacyDatabase && source.name.startsWith("weagent.db")) {
                if (source.delete()) removed += source.absolutePath else retained += source.absolutePath
            } else {
                cleanupFileIfDestinationExists(source, destination, removed, retained)
            }
            return
        }
        // A rootfs is an opaque runtime payload.  Never recurse through it from a settings
        // cleanup action: guest permissions can make individual entries unreadable, and a large
        // tree must not turn an explicit cleanup click into another long filesystem walk.
        if (source.name == "rootfs") {
            retained += source.absolutePath
            return
        }
        if (!source.isDirectory || !destination.isDirectory) {
            retained += source.absolutePath
            return
        }
        source.listFiles().orEmpty().forEach { child ->
            cleanupTree(
                source = child,
                destination = File(destination, child.name),
                removed = removed,
                retained = retained,
                deleteLegacyDatabase = deleteLegacyDatabase,
            )
        }
        if (source.listFiles().orEmpty().isEmpty() && source.delete()) {
            removed += source.absolutePath
        } else if (source.exists()) {
            retained += source.absolutePath
        }
    }

    private fun cleanupFileIfDestinationExists(
        source: File,
        destination: File,
        removed: MutableList<String>,
        retained: MutableList<String>,
    ) {
        if (!source.exists()) return
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) {
            retained += source.absolutePath
        } else if (destination.isFile && source.delete()) {
            removed += source.absolutePath
        } else {
            retained += source.absolutePath
        }
    }

    private fun removeEmptyParents(root: File, removed: MutableList<String>) {
        if (!root.isDirectory || root.listFiles().orEmpty().isNotEmpty()) return
        if (root.delete()) removed += root.absolutePath
    }

    private fun migrateExternal(root: Path) {
        val source = LegacyPaths.externalModuleRoot.toFile()
        if (!source.isDirectory) return
        val directories = listOf(
            "assets", "scripts_java", "scripts_python", "python", "agent/skills", "themes",
            "sticker_panel", "voice_panel", "extensions/script-deps",
        )
        directories.forEach { relative ->
            migrateExternalEntry(File(source, relative), root.resolve(relative).toFile())
        }
        val files = listOf(
            "conversation_groups.json", "chat_folders.json", "moments_custom_bottom_details.json",
            "feature_flag_overrides.json", "red_packet_settings.json", "red_packet_group_members.json",
            "auto_accept_transfer_settings.json", "custom_avatars_map.json",
            "auto_like_moments_settings.json", "auto_repost_moments_settings.json",
        )
        files.forEach { name -> migrateExternalEntry(File(source, name), root.resolve(name).toFile()) }
        migrateExternalEntry(
            File(source, "virtual_voip_video.mp4"),
            root.resolve("assets/virtual_voip_video.mp4").toFile(),
        )
    }

    /** The emulated external root and filesDir share the device filesystem on Android. */
    private fun migrateExternalEntry(source: File, destination: File) {
        if (!source.exists() || destination.exists()) return
        destination.parentFile?.mkdirs()
        runCatching {
            source.toPath().moveTo(destination.toPath(), ATOMIC_MOVE)
        }.onFailure {
            // Keep a verified copy fallback for devices that expose the two paths through
            // different mounts or do not support atomic moves for this filesystem.
            migrateExternalCopy(source, destination)
        }
    }

    /** Move an old private root into the unified root without opening any file contents. */
    private fun migratePrivateRoot(source: Path, destination: Path, excluded: Set<String> = emptySet()) {
        if (!source.exists()) return
        if (!destination.exists() && excluded.isEmpty()) {
            destination.parent?.createDirectories()
            moveAtomically(source, destination)
            return
        }
        if (!destination.exists()) destination.createDirectories()
        check(source.isDirectory() && destination.isDirectory()) {
            "cannot merge legacy private root $source into non-directory $destination"
        }
        // Rootfs trees are opaque runtime payloads. If a previous attempt already published the
        // destination instance, leave the legacy copy untouched rather than traversing guest
        // files; a complete rootfs is always moved as one directory when its destination is new.
        if (source.fileName.toString() == "rootfs") return
        // A destination may already contain files from an interrupted/older migration. Move
        // every non-conflicting entry atomically; conflicting destination entries are retained
        // and the legacy source is deliberately left in place for recovery.
        source.listDirectoryEntries().forEach { child ->
            if (child.fileName.toString() in excluded) return@forEach
            val target = destination.resolve(child.fileName.toString())
            if (!target.exists()) {
                moveAtomically(child, target)
            } else if (child.isDirectory() && target.isDirectory()) {
                migratePrivateRoot(child, target, excluded)
                if (child.listDirectoryEntries().isEmpty()) child.deleteIfExists()
            }
        }
        if (source.listDirectoryEntries().isEmpty()) source.deleteIfExists()
    }

    private fun moveAtomically(source: Path, destination: Path) {
        destination.parent?.createDirectories()
        try {
            source.moveTo(destination, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Both private roots are below filesDir, so this fallback remains a same-filesystem
            // rename on Android filesystems that do not advertise ATOMIC_MOVE.
            source.moveTo(destination)
        }
    }

    /** Copy and verify a legacy external-storage file or directory. */
    private fun migrateExternalCopy(source: File, destination: File) {
        if (!source.exists()) return
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                migrateExternalCopy(child, File(destination, child.name))
            }
            return
        }
        if (destination.exists()) return
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.migrating")
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
        }
        check(temporary.length() == source.length()) { "legacy file copy was truncated: $source" }
        check(sha256(temporary) == sha256(source)) { "legacy file copy failed verification: $source" }
        check(temporary.renameTo(destination)) { "cannot publish migrated file: $destination" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
