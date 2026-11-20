package dev.ujhhgtg.wekit.data

import kotlin.io.path.moveTo
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class PreparedDatabaseLocation(
    val file: File,
    val migratedNow: Boolean,
    val externalFallback: Boolean,
    val failure: Throwable? = null,
)

/**
 * Copies a legacy WeAgent database to the unified private database in two phases. The source is
 * deliberately retained: migration cleanup is a separate user-confirmed operation.
 */
class LegacyDatabaseRelocator(
    private val source: File,
    private val destination: File,
    private val recoverSource: (File) -> Unit,
) {
    /**
     * The lock is deliberately separate from the published database.  A process may be killed
     * while copying, so the lock must not be inferred from a marker file that another process can
     * remove.  File locks are advisory on Android, but every WeKit process uses this lock before
     * touching the destination during first-run relocation.
     */
    private val lockFile: File
        get() = File(destination.parentFile, destination.name + ".migration.lock")

    fun <T> withExclusiveLock(block: () -> T): T {
        destination.parentFile?.mkdirs()
        return FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { block() }
        }
    }

    fun prepare(): PreparedDatabaseLocation = withExclusiveLock { prepareLocked() }

    /** Must be called while [withExclusiveLock] is held. */
    fun prepareLocked(): PreparedDatabaseLocation {
        // Re-check after taking the inter-process lock. Another process may have published the
        // destination while this process was waiting for the lock.
        if (destination.isFile) {
            return PreparedDatabaseLocation(destination, migratedNow = false, externalFallback = false)
        }
        // A killed relocation can leave sidecars behind even though the main file was never
        // published. They belong to no usable database and must not be picked up by Room on the
        // next attempt.
        destination.deleteSidecars()
        if (!source.isFile) {
            // Fresh install: no external copy to migrate and recoverSource() would throw
            // on the missing file, so go straight to a brand-new private database.
            destination.parentFile!!.mkdirs()
            return PreparedDatabaseLocation(destination, migratedNow = false, externalFallback = false)
        }
        var temp: File? = null
        return try {
            destination.parentFile!!.mkdirs()
            recoverSource(source)
            // The recovery callback is responsible for checkpointing WAL. Do not copy a main
            // database while a rollback journal or WAL still contains pages; doing so would make
            // the destination silently disagree with the source. An SQLite shared-memory file
            // may legitimately remain after a successful checkpoint and is not copied.
            val wal = File(source.path + "-wal")
            check(!wal.isFile || wal.length() == 0L) {
                "legacy database WAL still contains uncheckpointed pages"
            }
            val journal = File(source.path + "-journal")
            check(!journal.isFile || journal.length() == 0L) {
                "legacy database rollback journal is still active"
            }
            // Every invocation gets its own temporary path. A fixed ".migrating" path allowed a
            // failed process to delete another process's in-progress copy.
            temp = File.createTempFile(destination.name + ".migrating-", ".tmp", destination.parentFile)
            source.inputStream().use { input ->
                FileOutputStream(temp!!).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            temp!!.toPath().moveTo(destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            PreparedDatabaseLocation(destination, migratedNow = true, externalFallback = false)
        } catch (t: Throwable) {
            temp?.delete()
            PreparedDatabaseLocation(source, migratedNow = false, externalFallback = true, failure = t)
        }
    }

    fun commit(prepared: PreparedDatabaseLocation) {
        // Keep the legacy source and sidecars until the user explicitly clears migrated copies.
    }

    fun rollback(prepared: PreparedDatabaseLocation) = withExclusiveLock {
        rollbackLocked(prepared)
    }

    /** Must be called while [withExclusiveLock] is held. */
    fun rollbackLocked(prepared: PreparedDatabaseLocation) {
        if (!prepared.migratedNow) return
        destination.delete()
        destination.deleteSidecars()
    }

    private fun File.deleteSidecars() {
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            File(path + suffix).delete()
        }
    }
}
