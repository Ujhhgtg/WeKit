package dev.ujhhgtg.wekit.agent.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal data class PreparedDatabaseLocation(
    val file: File,
    val migratedNow: Boolean,
    val externalFallback: Boolean,
    val failure: Throwable? = null,
)

/**
 * Moves the WeAgent database from FUSE-emulated external storage to private storage in two
 * phases: [prepare] copies and atomically publishes the new file, [commit] deletes the old
 * external copy only after Room opened the new one successfully, and [rollback] undoes the
 * copy if it did not. Any failure leaves the external source untouched and reports it as an
 * [PreparedDatabaseLocation.externalFallback] instead.
 */
internal class WeAgentDatabaseRelocator(
    private val source: File,
    private val destination: File,
    private val recoverSource: (File) -> Unit,
) {
    private val temp: File = File(destination.parentFile, destination.name + ".migrating")

    fun prepare(): PreparedDatabaseLocation {
        if (destination.isFile) {
            return PreparedDatabaseLocation(destination, migratedNow = false, externalFallback = false)
        }
        if (!source.isFile) {
            // Fresh install: no external copy to migrate and recoverSource() would throw
            // on the missing file, so go straight to a brand-new private database.
            return PreparedDatabaseLocation(destination, migratedNow = false, externalFallback = false)
        }
        return try {
            destination.parentFile!!.mkdirs()
            recoverSource(source)
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            PreparedDatabaseLocation(destination, migratedNow = true, externalFallback = false)
        } catch (t: Throwable) {
            temp.delete()
            destination.delete()
            destination.deleteSidecars()
            PreparedDatabaseLocation(source, migratedNow = false, externalFallback = true, failure = t)
        }
    }

    fun commit(prepared: PreparedDatabaseLocation) {
        source.delete()
        source.deleteSidecars()
    }

    fun rollback(prepared: PreparedDatabaseLocation) {
        if (!prepared.migratedNow) return
        temp.delete()
        destination.delete()
        destination.deleteSidecars()
    }

    private fun File.deleteSidecars() {
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            File(path + suffix).delete()
        }
    }
}
