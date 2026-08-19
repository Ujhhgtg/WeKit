package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `process termination orders descendants before their parents`() {
        assertEquals(
            listOf(4, 3, 2),
            ProcessTermination.descendants(1, mapOf(2 to 1, 3 to 2, 4 to 3)),
        )
    }

    @Test
    fun `proot creation persists only after publish and removes published files on persistence failure`(@TempDir directory: Path) = runBlocking {
        val instanceRoot = directory.resolve("instance")
        val rootfs = instanceRoot.resolve("rootfs")
        var persistedAfterPublish = false
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = nativeSnapshot(directory.resolve("native").also(Files::createDirectories)),
            prootPackAvailable = { true },
            installProot = {
                Files.createDirectories(rootfs)
                ArchLinuxInstance(rootfs.toFile(), "version")
            },
            persistEnvironment = {
                persistedAfterPublish = Files.isDirectory(rootfs)
                error("database failure")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.createProotEnvironment("Arch", "instance") }
        }
        assertTrue(persistedAfterPublish)
        assertFalse(Files.exists(instanceRoot))
    }

    @Test
    fun `proot creation exposes missing extension pack without installing`(@TempDir directory: Path) = runBlocking {
        var installCalled = false
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = nativeSnapshot(directory),
            prootPackAvailable = { false },
            installProot = { installCalled = true; error("must not install") },
            persistEnvironment = { error("must not persist") },
        )

        val result = manager.createProotEnvironment("Arch", "instance")

        assertTrue(result is ProotEnvironmentCreationResult.MissingPack)
        assertFalse(installCalled)
    }

    @Test
    fun `manager initialization recovers persisted chroot runs`(@TempDir directory: Path) = runBlocking {
        val rootfs = Files.createDirectories(directory.resolve("arch/rootfs"))
        var recoveries = 0
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = nativeSnapshot(directory.resolve("native")),
            storedEnvironments = { listOf(environment(LinuxEnvironmentType.CHROOT).copy(rootfsPath = rootfs.toString())) },
            recoverChroot = { recoveredRootfs, _ ->
                assertEquals(rootfs, recoveredRootfs)
                recoveries++
                ChrootRecoveryResult(1, emptyMap())
            },
        )

        manager.initialize()
        assertEquals(1, recoveries)
    }

    @Test
    fun `deletion refuses persisted unresolved chroot run after restart`(@TempDir directory: Path) = runBlocking {
        val rootfs = Files.createDirectories(directory.resolve("arch/rootfs"))
        val stored = environment(LinuxEnvironmentType.CHROOT).copy(rootfsPath = rootfs.toString())
        var deleted = false
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = nativeSnapshot(directory.resolve("native")),
            getEnvironment = { stored },
            deleteEnvironment = { deleted = true; true },
            recoverChroot = { _, _ -> ChrootRecoveryResult(0, mapOf("run-id" to "identity cannot be proven")) },
        )

        val error = assertThrows(IllegalStateException::class.java) { runBlocking { manager.delete(stored.id) } }
        assertTrue(error.message!!.contains("identity cannot be proven"))
        assertFalse(deleted)
    }

    @Test
    fun `unresolved chroot metadata blocks new exec before backend launch`(@TempDir directory: Path) = runBlocking {
        val rootfs = Files.createDirectories(directory.resolve("arch/rootfs"))
        val stored = environment(LinuxEnvironmentType.CHROOT).copy(rootfsPath = rootfs.toString())
        var backendCreated = false
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = nativeSnapshot(directory.resolve("native")),
            getEnvironment = { stored },
            backendFactory = { backendCreated = true; error("must not create backend") },
            highRiskApproval = { _, _ -> true },
            recoverChroot = { _, _ -> ChrootRecoveryResult(0, mapOf("run-id" to "missing process identity")) },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.exec(stored.id, "true", 1_000) }
        }
        assertTrue(error.message!!.contains("missing process identity"))
        assertFalse(backendCreated)
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
