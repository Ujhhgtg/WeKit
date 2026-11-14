package dev.ujhhgtg.wekit.utils.motion_photo

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MotionPhotoFilePublisherTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `publishes a new pair and consumes both stages`() {
        val photoStage = file("photo-stage.jpg", "new photo")
        val videoStage = file("video-stage.mp4", "new video")
        val photoOutput = directory / "output.jpg"
        val videoOutput = directory / "output.mp4"

        MotionPhotoFilePublisher.publishPair(photoStage, videoStage, photoOutput, videoOutput)

        assertEquals("new photo", photoOutput.readText())
        assertEquals("new video", videoOutput.readText())
        assertFalse(photoStage.exists())
        assertFalse(videoStage.exists())
        assertOnlyOutputs()
    }

    @Test
    fun `replaces an existing pair and cleans up its backups`() {
        val photoStage = file("photo-stage.jpg", "new photo")
        val videoStage = file("video-stage.mp4", "new video")
        val photoOutput = file("output.jpg", "old photo")
        val videoOutput = file("output.mp4", "old video")

        MotionPhotoFilePublisher.publishPair(photoStage, videoStage, photoOutput, videoOutput)

        assertEquals("new photo", photoOutput.readText())
        assertEquals("new video", videoOutput.readText())
        assertOnlyOutputs()
    }

    @Test
    fun `a second move failure restores both existing outputs without leftover backups`() {
        val photoStage = file("photo-stage.jpg", "new photo")
        val videoStage = file("video-stage.mp4", "new video")
        val photoOutput = file("output.jpg", "old photo")
        val videoOutput = file("output.mp4", "old video")
        photoStage.deleteExisting()

        assertThrows(IOException::class.java) {
            MotionPhotoFilePublisher.publishPair(photoStage, videoStage, photoOutput, videoOutput)
        }

        assertEquals("old photo", photoOutput.readText())
        assertEquals("old video", videoOutput.readText())
        assertOnlyOutputs()
    }

    @Test
    fun `a second move failure removes the newly published video when no previous pair exists`() {
        val photoStage = file("photo-stage.jpg", "new photo")
        val videoStage = file("video-stage.mp4", "new video")
        val photoOutput = directory / "output.jpg"
        val videoOutput = directory / "output.mp4"
        photoStage.deleteExisting()

        assertThrows(IOException::class.java) {
            MotionPhotoFilePublisher.publishPair(photoStage, videoStage, photoOutput, videoOutput)
        }

        assertFalse(photoOutput.exists())
        assertFalse(videoOutput.exists())
        assertEquals(emptySet<String>(), directory.listDirectoryEntries().map { it.name }.toSet())
    }

    private fun file(name: String, content: String) =
        (directory / name).apply { writeText(content) }

    private fun assertOnlyOutputs() {
        assertEquals(
            setOf("output.jpg", "output.mp4"),
            directory.listDirectoryEntries().map { it.name }.toSet(),
        )
    }
}
