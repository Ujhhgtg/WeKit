package dev.ujhhgtg.wekit.agent.environment

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditFileTest {
    @Test
    fun `creation only accepts missing or empty files`(@TempDir directory: Path) = runBlocking {
        val backend = NativeBackend(snapshot(directory))
        backend.edit(FileEditRequest("new.txt", null, "created"))
        assertEquals("created", Files.readString(directory.resolve("new.txt")))
        Files.writeString(directory.resolve("occupied.txt"), "existing")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { backend.edit(FileEditRequest("occupied.txt", null, "replace")) }
        }
    }

    @Test
    fun `replace all changes every exact match`(@TempDir directory: Path) = runBlocking {
        val backend = NativeBackend(snapshot(directory))
        val file = directory.resolve("note.txt")
        Files.writeString(file, "x y x")
        backend.edit(FileEditRequest("note.txt", "x", "z", replaceAll = true))
        assertEquals("z y z", Files.readString(file))
    }

    private fun snapshot(directory: Path) = EnvironmentSnapshot(
        NATIVE_ENVIRONMENT_ID, "Native", LinuxEnvironmentType.NATIVE, "test", "test",
        "/system/bin/sh", directory.toString(), null, "test",
    )
}
