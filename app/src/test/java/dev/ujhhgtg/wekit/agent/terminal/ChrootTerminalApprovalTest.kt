package dev.ujhhgtg.wekit.agent.terminal

import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.agent.environment.ArchLinuxInstanceInstaller
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.ChrootMountRegistry
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
    fun `proot terminal uses package managed executables`(@TempDir directory: Path) = runBlocking {
        val rootfs = Files.createDirectories(directory.resolve("instance/rootfs"))
        val native = RecordingBackend()
        val backend = EnvironmentTerminalBackend(
            native = native,
            chrootInstancesRoot = directory,
            resolveProotLauncher = { "/package/lib/libproot.so".asPath },
            resolveProotLoader = { "/package/lib/libproot_loader.so".asPath },
        )

        backend.start(
            snapshot(rootfs).copy(type = LinuxEnvironmentType.PROOT),
            listOf("/bin/bash"),
            null,
            emptyMap(),
            80,
            24,
        )

        assertEquals("/package/lib/libproot.so", native.argv.single().first())
        assertEquals(
            "/package/lib/libproot_loader.so",
            native.environments.single().getValue("PROOT_LOADER"),
        )
    }

    @Test
    fun `rooted terminal denial occurs before launcher resolution`(@TempDir directory: Path) {
        val rootfs = publishedRootfs(directory)
        var launcherResolved = false
        val backend = EnvironmentTerminalBackend(
            native = RecordingBackend(),
            approveChrootStart = { false },
            chrootInstancesRoot = directory,
            resolveRootLauncher = { launcherResolved = true; "/system/bin/su".asPath },
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
            resolveRootLauncher = { "/system/bin/su".asPath },
            cleanupChrootRun = { helper, run -> helper.removeRunMetadata(run) },
        )

        val started = backend.start(snapshot(rootfs), listOf("/bin/bash", "-l"), null, emptyMap(), 80, 24)
        assertEquals(1, approvals)
        assertEquals("/system/bin/su", native.argv.single().first())
        assertFalse(native.argv.single().last().contains("setsid"))
        started.session.close()
    }

    @Test
    fun `early startup failure after launch handoff retains uncertain run`(@TempDir directory: Path) {
        val rootfs = publishedRootfs(directory)
        val stale = rootfs.parent.resolve("chroot.pid")
        stale.writeText("1")
        var cleanedNonce: String? = null
        val backend = EnvironmentTerminalBackend(
            native = FailingBackend(),
            approveChrootStart = { true },
            chrootInstancesRoot = directory,
            resolveRootLauncher = { "/system/bin/su".asPath },
            cleanupChrootRun = { helper, run ->
                cleanedNonce = run.nonce
                assertFalse(Files.exists(run.pidFile))
                assertEquals("LAUNCHING", Files.readString(run.stageFile))
                error("launch outcome is uncertain")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { backend.start(snapshot(rootfs), listOf("/bin/bash"), null, emptyMap(), 80, 24) }
        }
        assertTrue(cleanedNonce != null)
        assertEquals("1", Files.readString(stale))
        assertTrue(ChrootMountRegistry.isBusy(rootfs))
    }

    @Test
    fun `unresolved metadata blocks terminal before launcher resolution`(@TempDir directory: Path) {
        val rootfs = publishedRootfs(directory)
        val configuration = dev.ujhhgtg.wekit.agent.environment.ChrootConfiguration(rootfs, "/root")
        val pending = configuration.createRun()
        pending.stageFile.writeText("NAMESPACE")
        var launcherResolved = false
        val backend = EnvironmentTerminalBackend(
            native = RecordingBackend(),
            approveChrootStart = { true },
            chrootInstancesRoot = directory,
            resolveRootLauncher = { launcherResolved = true; "/system/bin/su".asPath },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { backend.start(snapshot(rootfs), listOf("/bin/bash"), null, emptyMap(), 80, 24) }
        }
        assertFalse(launcherResolved)
        assertTrue(Files.exists(pending.directory))
    }

    @Test
    fun `failed close keeps metadata and busy state for successful retry`(@TempDir directory: Path) = runBlocking {
        val rootfs = publishedRootfs(directory)
        var cleanups = 0
        var runDirectory: Path? = null
        val backend = EnvironmentTerminalBackend(
            native = RecordingBackend(),
            approveChrootStart = { true },
            chrootInstancesRoot = directory,
            resolveRootLauncher = { "/system/bin/su".asPath },
            cleanupChrootRun = { helper, run ->
                cleanups++
                runDirectory = run.directory
                if (cleanups == 1) error("busy mount")
                helper.removeRunMetadata(run)
            },
        )
        val session = backend.start(snapshot(rootfs), listOf("/bin/bash"), null, emptyMap(), 80, 24).session

        assertThrows(IllegalStateException::class.java) { runBlocking { session.close() } }
        assertTrue(Files.isDirectory(runDirectory))
        assertTrue(ChrootMountRegistry.isBusy(rootfs))
        session.close()
        assertEquals(2, cleanups)
        assertFalse(Files.exists(runDirectory))
        assertFalse(ChrootMountRegistry.isBusy(rootfs))
    }

    private fun publishedRootfs(instances: Path): Path {
        val instance = Files.createDirectories(instances.resolve("arch"))
        instance.resolve(ArchLinuxInstanceInstaller.PUBLISHED_MARKER).writeText("1")
        listOf("rootfs/bin/bash", "rootfs/usr/bin/invoke_tool").forEach { relative ->
            val file = instance.resolve(relative)
            Files.createDirectories(file.parent)
            file.writeText("x")
            assertTrue(file.toFile().setExecutable(true))
        }
        val resolv = instance.resolve("rootfs/etc/resolv.conf")
        Files.createDirectories(resolv.parent)
        resolv.writeText("nameserver 1.1.1.1\n")
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
        val environments = mutableListOf<Map<String, String>>()
        override suspend fun start(
            environment: EnvironmentSnapshot,
            argv: List<String>,
            workingDirectory: String?,
            environmentVariables: Map<String, String>,
            cols: Int,
            rows: Int,
        ): TerminalBackendStart {
            this.argv += argv
            environments += environmentVariables
            return TerminalBackendStart(Session(), environment)
        }
    }

    private class FailingBackend : TerminalBackend {
        override suspend fun start(
            environment: EnvironmentSnapshot,
            argv: List<String>,
            workingDirectory: String?,
            environmentVariables: Map<String, String>,
            cols: Int,
            rows: Int,
        ): TerminalBackendStart = error("PTY startup failed")
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
