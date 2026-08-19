package dev.ujhhgtg.wekit.agent.terminal

import dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstanceInstaller
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChrootTerminalApprovalTest {
    @Test
    fun `rooted terminal denial occurs before launcher resolution`(@TempDir directory: Path) {
        val rootfs = publishedRootfs(directory)
        var launcherResolved = false
        val backend = EnvironmentTerminalBackend(
            native = RecordingBackend(),
            approveChrootStart = { false },
            chrootInstancesRoot = directory,
            resolveRootLauncher = { launcherResolved = true; Path.of("/system/bin/su") },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { backend.start(snapshot(rootfs), listOf("/bin/bash"), null, emptyMap(), 80, 24) }
        }
        assertFalse(launcherResolved)
    }

    @Test
    fun `rooted terminal acceptance uses absolute launcher without nested session`(@TempDir directory: Path) = runBlocking {
        val rootfs = publishedRootfs(directory)
        val native = RecordingBackend()
        var approvals = 0
        val backend = EnvironmentTerminalBackend(
            native = native,
            approveChrootStart = { approvals++; true },
            chrootInstancesRoot = directory,
            resolveRootLauncher = { Path.of("/system/bin/su") },
        )

        val started = backend.start(snapshot(rootfs), listOf("/bin/bash", "-l"), null, emptyMap(), 80, 24)
        assertEquals(1, approvals)
        assertEquals("/system/bin/su", native.argv.single().first())
        assertFalse(native.argv.single().last().contains("setsid"))
        started.session.close()
    }

    private fun publishedRootfs(instances: Path): Path {
        val instance = Files.createDirectories(instances.resolve("arch"))
        Files.writeString(instance.resolve(ArchLinuxInstanceInstaller.PUBLISHED_MARKER), "1")
        listOf("bin/proot", "bin/loader", "rootfs/bin/bash", "rootfs/usr/bin/invoke_tool").forEach { relative ->
            val file = instance.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "x")
            assertTrue(file.toFile().setExecutable(true))
        }
        val resolv = instance.resolve("rootfs/etc/resolv.conf")
        Files.createDirectories(resolv.parent)
        Files.writeString(resolv, "nameserver 1.1.1.1\n")
        return instance.resolve("rootfs")
    }

    private fun snapshot(rootfs: Path) = EnvironmentSnapshot(
        id = "arch", displayName = "Arch", type = LinuxEnvironmentType.CHROOT,
        operatingSystem = "Arch", architecture = "arm64", shell = "/bin/bash",
        workingDirectory = "/root", bridgeLocation = "/usr/bin/invoke_tool",
        privilegesAndCapabilities = "HIGH RISK", rootfsPath = rootfs.toString(),
    )

    private class RecordingBackend : TerminalBackend {
        val argv = mutableListOf<List<String>>()
        override suspend fun start(
            environment: EnvironmentSnapshot,
            argv: List<String>,
            workingDirectory: String?,
            environmentVariables: Map<String, String>,
            cols: Int,
            rows: Int,
        ): TerminalBackendStart {
            this.argv += argv
            return TerminalBackendStart(Session(), environment)
        }
    }

    private class Session : TerminalBackendSession {
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun read(maxBytes: Int): ByteArray = ByteArray(0)
        override suspend fun resize(cols: Int, rows: Int) = Unit
        override suspend fun waitForExit(): Int = 0
        override suspend fun kill() = Unit
        override suspend fun close() = Unit
    }
}
