package dev.ujhhgtg.wekit.utils.fs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PathUtilsTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `moveReplacing replaces an existing target`() {
        val source = directory.resolve("source").apply { writeText("new") }
        val target = directory.resolve("target").apply { writeText("old") }

        assertEquals(target, source.moveReplacing(target))
        assertEquals("new", target.readText())
    }

    @Test
    fun `stream copy helpers preserve content`() {
        val path = directory.resolve("data")
        assertEquals(7L, path.copyFrom(ByteArrayInputStream("content".encodeToByteArray())))

        val output = ByteArrayOutputStream()
        assertEquals(7L, path.copyTo(output))
        assertEquals("content", output.toString(Charsets.UTF_8.name()))
    }
}
