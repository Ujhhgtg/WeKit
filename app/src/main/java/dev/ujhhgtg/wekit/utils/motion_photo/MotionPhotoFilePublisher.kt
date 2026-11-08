package dev.ujhhgtg.wekit.utils.motion_photo

import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import dev.ujhhgtg.wekit.utils.fs.tryDelete
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/** Publishes an already validated pair, restoring previous outputs if either move fails. */
object MotionPhotoFilePublisher {
    fun publishPair(photoStage: Path, videoStage: Path, photoOutput: Path, videoOutput: Path) {
        val photoBackup = backup(photoOutput, ".jpg")
        val videoBackup =
            try {
                backup(videoOutput, ".mp4")
            } catch (failure: IOException) {
                photoBackup?.tryDelete()
                throw failure
            }

        try {
            videoStage.moveReplacing(videoOutput)
            photoStage.moveReplacing(photoOutput)
        } catch (failure: IOException) {
            restore(photoOutput, photoBackup, failure)
            restore(videoOutput, videoBackup, failure)
            throw failure
        }
        photoBackup?.tryDelete()
        videoBackup?.tryDelete()
    }

    private fun backup(output: Path, suffix: String): Path? {
        if (!output.exists()) return null
        // Scoped storage determines media type from the extension, including temporary files.
        val backup =
            createTempFile(output.toAbsolutePath().parent, "wekit-livephoto-backup-", suffix)
        try {
            output.copyTo(backup, overwrite = true)
        } catch (failure: IOException) {
            backup.tryDelete()
            throw failure
        }
        return backup
    }

    private fun restore(output: Path, backup: Path?, failure: IOException) {
        try {
            if (backup == null) {
                output.deleteIfExists()
            } else {
                backup.moveReplacing(output)
            }
        } catch (restoreFailure: IOException) {
            // Keep a backup that could not be restored, so the old output remains recoverable.
            failure.addSuppressed(restoreFailure)
        }
    }
}
