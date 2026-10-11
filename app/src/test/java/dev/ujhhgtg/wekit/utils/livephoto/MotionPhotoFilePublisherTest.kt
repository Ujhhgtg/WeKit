package dev.ujhhgtg.wekit.utils.livephoto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class MotionPhotoFilePublisherTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `publishes a new pair and consumes both stages`() {
        val photoStage = file("photo-stage.jpg", "new photo")
        val videoStage = file("video-stage.mp4", "new video")
        val photoOutput = File(directory, "output.jpg")
        val videoOutput = File(directory, "output.mp4")

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
        photoStage.delete()

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
        val photoOutput = File(directory, "output.jpg")
        val videoOutput = File(directory, "output.mp4")
        photoStage.delete()

        assertThrows(IOException::class.java) {
            MotionPhotoFilePublisher.publishPair(photoStage, videoStage, photoOutput, videoOutput)
        }

        assertFalse(photoOutput.exists())
        assertFalse(videoOutput.exists())
        assertEquals(emptySet<String>(), directory.list()!!.toSet())
    }

    private fun file(name: String, content: String) = File(directory, name).apply { writeText(content) }

    private fun assertOnlyOutputs() {
        assertEquals(setOf("output.jpg", "output.mp4"), directory.list()!!.toSet())
    }
}
