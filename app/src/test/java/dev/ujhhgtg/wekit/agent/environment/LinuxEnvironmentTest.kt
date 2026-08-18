package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LinuxEnvironmentTest {
    @Test
    fun `missing global default resolves to native`() {
        assertEquals(
            NATIVE_ENVIRONMENT_ID,
            WeAgentRepository.resolveEffectiveLinuxEnvironmentId(null, null, emptySet()),
        )
    }

    @Test
    fun `concrete session environment wins over global default`() {
        assertEquals(
            "session-env",
            WeAgentRepository.resolveEffectiveLinuxEnvironmentId(
                "session-env",
                "default-env",
                setOf("session-env", "default-env"),
            ),
        )
    }

    @Test
    fun `deleted global default resolves to native`() {
        assertEquals(
            NATIVE_ENVIRONMENT_ID,
            WeAgentRepository.resolveEffectiveLinuxEnvironmentId(null, "deleted", emptySet()),
        )
    }

    @Test
    fun `environment type is immutable at repository boundary`() {
        val existing = environment(LinuxEnvironmentType.PROOT)
        assertThrows(IllegalArgumentException::class.java) {
            WeAgentRepository.validateLinuxEnvironmentUpdate(existing, existing.copy(type = LinuxEnvironmentType.CHROOT))
        }
    }

    @Test
    fun `native edit replaces exactly and rejects relative traversal`(@TempDir directory: Path) = runBlocking {
        val file = directory.resolve("note.txt")
        Files.writeString(file, "before")
        val backend = NativeBackend(nativeSnapshot(directory))

        backend.edit(FileEditRequest("note.txt", "before", "after"))

        assertEquals("after", Files.readString(file))
        assertThrows(IllegalArgumentException::class.java) { backend.resolvePath("../outside") }
    }

    @Test
    fun `native timeout tree orders descendants before their parents`() {
        assertEquals(
            listOf(4, 3, 2),
            NativeBackend.ProcessTree.descendants(
                rootPid = 1,
                parentOf = mapOf(2 to 1, 3 to 2, 4 to 3),
            ),
        )
    }

    private fun environment(type: LinuxEnvironmentType) = LinuxEnvironmentEntity(
        id = "environment",
        name = "Environment",
        type = type,
        workingDirectory = "/home/user",
        rootfsPath = "/rootfs",
    )

    private fun nativeSnapshot(directory: Path) = EnvironmentSnapshot(
        id = NATIVE_ENVIRONMENT_ID,
        displayName = "Native Android",
        type = LinuxEnvironmentType.NATIVE,
        operatingSystem = "Android/Toybox",
        architecture = "test",
        shell = "/system/bin/sh",
        workingDirectory = directory.toString(),
        bridgeLocation = null,
        privilegesAndCapabilities = "test process",
    )
}
