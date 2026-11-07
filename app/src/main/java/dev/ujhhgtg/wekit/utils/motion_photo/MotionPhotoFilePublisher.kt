package dev.ujhhgtg.wekit.utils.motion_photo

import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Publishes an already validated pair, restoring previous outputs if either move fails. */
object MotionPhotoFilePublisher {
    fun publishPair(photoStage: File, videoStage: File, photoOutput: File, videoOutput: File) {
        val photoBackup = backup(photoOutput, ".jpg")
        val videoBackup =
            try {
                backup(videoOutput, ".mp4")
            } catch (failure: IOException) {
                photoBackup?.delete()
                throw failure
            }

        try {
            videoStage.toPath().moveReplacing(videoOutput.toPath())
            photoStage.toPath().moveReplacing(photoOutput.toPath())
        } catch (failure: IOException) {
            restore(photoOutput, photoBackup, failure)
            restore(videoOutput, videoBackup, failure)
            throw failure
        }
        photoBackup?.delete()
        videoBackup?.delete()
    }

    private fun backup(output: File, suffix: String): File? {
        if (!output.exists()) return null
        // Scoped storage determines media type from the extension, including temporary files.
        val backup =
            File.createTempFile("wekit-livephoto-backup-", suffix, output.absoluteFile.parentFile)
        try {
            Files.copy(output.toPath(), backup.toPath(), REPLACE_EXISTING)
        } catch (failure: IOException) {
            backup.delete()
            throw failure
        }
        return backup
    }

    private fun restore(output: File, backup: File?, failure: IOException) {
        try {
            if (backup == null) {
                Files.deleteIfExists(output.toPath())
            } else {
                backup.toPath().moveReplacing(output.toPath())
            }
        } catch (restoreFailure: IOException) {
            // Keep a backup that could not be restored, so the old output remains recoverable.
            failure.addSuppressed(restoreFailure)
        }
    }
}
