package dev.ujhhgtg.wekit.agent.environment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `single replacement changes exactly one match`(@TempDir directory: Path) = runBlocking {
        val backend = NativeBackend(snapshot(directory))
        val file = directory.resolve("note.txt")
        Files.writeString(file, "before needle after")

        backend.edit(FileEditRequest("note.txt", "needle", "replacement"))

        assertEquals("before replacement after", Files.readString(file))
    }

    @Test
    fun `single replacement rejects ambiguous match and leaves file unchanged`(@TempDir directory: Path) = runBlocking {
        val backend = NativeBackend(snapshot(directory))
        val file = directory.resolve("note.txt")
        Files.writeString(file, "x y x")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { backend.edit(FileEditRequest("note.txt", "x", "z")) }
        }
        assertEquals("x y x", Files.readString(file))
    }

    @Test
    fun `failed atomic edit leaves the original file unchanged`(@TempDir directory: Path) = runBlocking {
        val backend = NativeBackend(snapshot(directory))
        val file = directory.resolve("note.txt")
        Files.writeString(file, "before")
        val writableMode = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))

        try {
            assertThrows(java.io.IOException::class.java) {
                runBlocking { backend.edit(FileEditRequest("note.txt", "before", "after")) }
            }
        } finally {
            Files.setPosixFilePermissions(directory, writableMode)
        }
        assertEquals("before", Files.readString(file))
    }

    @Test
    fun `new files use configured mode and existing files preserve their mode`(@TempDir directory: Path) = runBlocking {
        val configuredMode = PosixFilePermissions.fromString("rw-r-----")
        val backend = NativeBackend(snapshot(directory), defaultFilePermissions = configuredMode)
        val existing = directory.resolve("existing.txt")
        val existingMode = PosixFilePermissions.fromString("rw-rw----")
        Files.writeString(existing, "old")
        Files.setPosixFilePermissions(existing, existingMode)

        backend.edit(FileEditRequest("new.txt", null, "new"))
        backend.edit(FileEditRequest("existing.txt", "old", "updated"))

        assertEquals(configuredMode, Files.getPosixFilePermissions(directory.resolve("new.txt")))
        assertEquals(existingMode, Files.getPosixFilePermissions(existing))
    }

    @Test
    fun `exec bounds output and spills the complete result`(@TempDir directory: Path) = runBlocking {
        val process = FakeOwnedProcess(stdout = "123456789")
        val backend = NativeBackend(
            snapshot(directory),
            maxOutputBytes = 4,
            startProcess = { _, _, _ -> process },
        )

        val result = backend.exec("ignored", 1_000)

        assertEquals(0, result.exitCode)
        assertEquals(false, result.timedOut)
        assertEquals("1234", result.stdout)
        assertEquals(true, result.spillPath != null)
        assertEquals("--- stdout ---\n123456789\n--- stderr ---\n", Files.readString(Path.of(result.spillPath!!)))
    }

    @Test
    fun `exec classifies a process exceeding its deadline as timed out`(@TempDir directory: Path) = runBlocking {
        val process = FakeOwnedProcess(running = true)
        val backend = NativeBackend(
            snapshot(directory),
            startProcess = { _, _, _ -> process },
        )

        val result = backend.exec("ignored", 50)

        assertEquals(null, result.exitCode)
        assertEquals(true, result.timedOut)
        assertTrue(process.terminated)
    }

    private class FakeOwnedProcess(
        stdout: String = "",
        stderr: String = "",
        running: Boolean = false,
    ) : OwnedProcessHandle {
        override val outputStream = ByteArrayOutputStream()
        override val inputStream: InputStream = ByteArrayInputStream(stdout.toByteArray())
        override val errorStream: InputStream = ByteArrayInputStream(stderr.toByteArray())
        private var exitCode: Int? = if (running) null else 0
        var terminated = false
            private set

        override fun pollExit(): Int? = exitCode

        override suspend fun terminateGroup(graceMillis: Long) {
            yield()
            coroutineContext.ensureActive()
            terminated = true
            exitCode = 143
        }

        override fun close() = Unit
    }

    private fun snapshot(directory: Path) = EnvironmentSnapshot(
        NATIVE_ENVIRONMENT_ID, "Native", LinuxEnvironmentType.NATIVE, "test", "test",
        "/bin/sh", directory.toString(), null, "test",
    )
}
