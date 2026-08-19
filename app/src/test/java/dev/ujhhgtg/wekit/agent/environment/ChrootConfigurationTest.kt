package dev.ujhhgtg.wekit.agent.environment

import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChrootConfigurationTest {
    @Test
    fun `configuration rejects path traversal and host paths outside shared storage`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChrootConfiguration(Path.of("/instances/arch/rootfs"), "/root/../data")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChrootConfiguration(
                Path.of("/instances/arch/rootfs"), "/root",
                listOf(ChrootBind(Path.of("/data/local/tmp"), "/storage/tmp")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChrootConfiguration(
                Path.of("/instances/arch/rootfs"), "/root",
                listOf(ChrootBind(Path.of("/storage/emulated/0"), "/host")),
            )
        }
    }

    @Test
    fun `mount arguments contain only pseudo mounts and allowlisted binds`() {
        val configuration = ChrootConfiguration(
            Path.of("/instances/arch/rootfs"), "/root",
            listOf(ChrootBind(Path.of("/storage/emulated/0/Documents"), "/storage/Documents")),
        )

        assertEquals(
            listOf(
                listOf("mount", "-t", "proc", "proc", "/proc"),
                listOf("mount", "-t", "sysfs", "sysfs", "/sys"),
                listOf("mount", "--rbind", "/dev", "/dev"),
                listOf("mount", "--bind", "/storage/emulated/0/Documents", "/storage/Documents"),
            ),
            configuration.mountArguments(),
        )
    }

    @Test
    fun `launcher keeps argv opaque and installs bridge environment in clean guest env`() {
        val configuration = ChrootConfiguration(Path.of("/instances/arch/rootfs"), "/root")
        val run = ChrootRun(UUID.randomUUID().toString(), Path.of("/instances/arch/chroot-runs/test"))
        val script = configuration.launchScript(
            run,
            listOf("/bin/bash", "-lc", "printf '%s' 'a; b'"),
            mapOf("WEAGENT_BRIDGE_PORT" to "42831", "WEAGENT_BRIDGE_TOKEN" to "secret", "PATH" to "/host/bin"),
        )

        assertTrue(script.contains("'WEAGENT_BRIDGE_PORT=42831'"))
        assertTrue(script.contains("'WEAGENT_BRIDGE_TOKEN=secret'"))
        assertTrue(script.contains("'printf '\\''%s'\\'' '\\''a; b'\\'''"))
        assertFalse(script.contains("PATH=/host/bin"))
        assertTrue(script.contains("mount --make-rprivate /"))
        assertTrue(script.contains("trap cleanup EXIT HUP INT TERM"))
        assertTrue(script.contains("test -r '/instances/arch/rootfs/etc/resolv.conf'"))
        val hostArgv = configuration.hostLaunchArgv(run, Path.of("/system/bin/su"), listOf("/bin/bash"), emptyMap())
        assertEquals("/system/bin/su", hostArgv.first())
        assertFalse(hostArgv.last().contains("setsid"))
        assertTrue(hostArgv.last().contains(run.cmdlineMarker))
    }

    @Test
    fun `chroot capability metadata is explicitly high risk and names root host access`() {
        val snapshot = environmentEntity(LinuxEnvironmentType.CHROOT).toSnapshot()

        assertTrue(snapshot.privilegesAndCapabilities.contains("HIGH RISK"))
        assertTrue(snapshot.privilegesAndCapabilities.contains("device root"))
        assertTrue(snapshot.privilegesAndCapabilities.contains("host-filesystem"))
    }

    @Test
    fun `creation refuses missing high risk approval before installing`() {
        var installed = false
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = EnvironmentSnapshot(
                id = NATIVE_ENVIRONMENT_ID, displayName = "Native", type = LinuxEnvironmentType.NATIVE,
                operatingSystem = "Android", architecture = "arm64", shell = "/system/bin/sh",
                workingDirectory = "/private", bridgeLocation = null, privilegesAndCapabilities = "app UID",
            ),
            prootPackAvailable = { true },
            installProot = { installed = true; error("must not install") },
            persistEnvironment = { error("must not persist") },
            highRiskApproval = { _, _ -> false },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.createChrootEnvironment("Root Arch", instanceId = "root-arch") }
        }
        assertFalse(installed)
    }

    @Test
    fun `creation proceeds only after narrow high risk approval`() = runBlocking {
        var approvals = 0
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = EnvironmentSnapshot(
                id = NATIVE_ENVIRONMENT_ID, displayName = "Native", type = LinuxEnvironmentType.NATIVE,
                operatingSystem = "Android", architecture = "arm64", shell = "/system/bin/sh",
                workingDirectory = "/private", bridgeLocation = null, privilegesAndCapabilities = "app UID",
            ),
            prootPackAvailable = { false },
            highRiskApproval = { operation, _ -> approvals++; operation == "create rooted chroot environment" },
        )

        assertTrue(manager.createChrootEnvironment("Root Arch", instanceId = "root-arch") is ChrootEnvironmentCreationResult.MissingPack)
        assertEquals(1, approvals)
    }

    @Test
    fun `published rootfs must remain canonically contained and complete`(@TempDir directory: Path) {
        val instances = Files.createDirectory(directory.resolve("instances"))
        val rootfs = publishedRootfs(instances, "arch")
        assertEquals(rootfs.toRealPath(), ArchLinuxInstanceLayout.validatePublishedRootfs(rootfs, instances))
        assertThrows(IllegalArgumentException::class.java) {
            ArchLinuxInstanceLayout.validatePublishedRootfs(Path.of("/"), instances)
        }

        val outside = publishedRootfs(directory, "outside")
        val escaped = instances.resolve("escape")
        Files.createSymbolicLink(escaped, outside.parent)
        assertThrows(IllegalArgumentException::class.java) {
            ArchLinuxInstanceLayout.validatePublishedRootfs(escaped.resolve("rootfs"), instances)
        }
        Files.delete(rootfs.parent.resolve(ArchLinuxInstanceInstaller.PUBLISHED_MARKER))
        assertThrows(IllegalArgumentException::class.java) {
            ArchLinuxInstanceLayout.validatePublishedRootfs(rootfs, instances)
        }
    }

    @Test
    fun `cleanup orders term unmount kill and registry stays busy until confirmation`() {
        val rootfs = Path.of("/instances/arch/rootfs")
        val helper = ChrootRootHelper(ChrootConfiguration(rootfs, "/root"))
        val run = ChrootRun("11111111-1111-1111-1111-111111111111", Path.of("/instances/arch/chroot-runs/11111111-1111-1111-1111-111111111111"))
        val command = helper.cleanupCommand(run, 4321, "98765", "22222222-2222-2222-2222-222222222222")
        assertTrue(command.contains("/proc/4321/stat"))
        assertTrue(command.contains("= '98765' &&"))
        assertTrue(command.indexOf("kill -TERM") < command.indexOf("nsenter"))
        assertTrue(command.indexOf("nsenter") < command.indexOf("kill -KILL"))
        assertTrue(command.indexOf("kill -KILL") < command.indexOf("while test -e"))
        ChrootMountRegistry.begin(rootfs, "run-a")
        try {
            assertTrue(ChrootMountRegistry.isBusy(rootfs))
            assertThrows(IllegalStateException::class.java) { ChrootMountRegistry.beginDeletion(rootfs) }
        } finally {
            ChrootMountRegistry.end(rootfs, "run-a")
        }
        assertFalse(ChrootMountRegistry.isBusy(rootfs))
    }

    @Test
    fun `cleanup signals only exact cmdline start time and boot identity`(@TempDir directory: Path) {
        val rootfs = Files.createDirectories(directory.resolve("arch/rootfs"))
        val configuration = ChrootConfiguration(rootfs, "/root")
        val helper = ChrootRootHelper(configuration)
        val run = configuration.createRun()
        val procRoot = Files.createDirectory(directory.resolve("proc"))
        val bootIdFile = directory.resolve("boot-id")
        val bootId = "22222222-2222-2222-2222-222222222222"
        Files.writeString(bootIdFile, bootId)

        fun execute(marker: String, currentBootId: String = bootId, currentStartTime: String = "98765"): Pair<Int, Boolean> {
            val process = Files.createDirectories(procRoot.resolve("4321"))
            Files.writeString(process.resolve("stat"), "4321 (sh) S " + (4..21).joinToString(" ") + " $currentStartTime")
            Files.write(process.resolve("cmdline"), ("/system/bin/sh\u0000$marker\u0000").toByteArray())
            Files.writeString(bootIdFile, currentBootId)
            val signaled = directory.resolve("signaled")
            Files.deleteIfExists(signaled)
            val command = helper.cleanupCommand(
                run, 4321, "98765", bootId, procRoot, bootIdFile,
                termCommand = "touch ${ChrootConfiguration.shell(signaled.toString())}; rm -rf ${ChrootConfiguration.shell(process.toString())}",
                killCommand = "exit 99",
            )
            val code = ProcessBuilder("/bin/sh", "-c", command).start().waitFor()
            return code to Files.exists(signaled)
        }

        assertEquals(75 to false, execute("wekit-chroot-run-${UUID.randomUUID()}"))
        assertEquals(75 to false, execute(run.cmdlineMarker, "33333333-3333-3333-3333-333333333333"))
        assertEquals(75 to false, execute(run.cmdlineMarker, currentStartTime = "98766"))
        assertEquals(0 to true, execute(run.cmdlineMarker))
    }

    @Test
    fun `missing pid metadata is removable only before mounts`(@TempDir directory: Path) = runBlocking {
        val instance = Files.createDirectory(directory.resolve("arch"))
        val configuration = ChrootConfiguration(Files.createDirectory(instance.resolve("rootfs")), "/root")
        val helper = ChrootRootHelper(configuration)
        val safe = configuration.createRun()
        Files.writeString(safe.stageFile, "NAMESPACE")
        helper.cleanupNamespace(safe)
        assertFalse(Files.exists(safe.directory))

        val uncertain = configuration.createRun()
        Files.writeString(uncertain.stageFile, "MOUNT")
        assertThrows(ChrootFailure.Cleanup::class.java) { runBlocking { helper.cleanupNamespace(uncertain) } }
        assertTrue(Files.exists(uncertain.directory))
        assertTrue(ChrootMountRegistry.isBusy(configuration.rootfs))
    }

    @Test
    fun `registry releases only matching run token and persisted runs remain busy`(@TempDir directory: Path) {
        val rootfs = Files.createDirectories(directory.resolve("arch/rootfs"))
        ChrootMountRegistry.begin(rootfs, "run-a")
        ChrootMountRegistry.begin(rootfs, "run-b")
        ChrootMountRegistry.end(rootfs, "run-a")
        assertTrue(ChrootMountRegistry.isBusy(rootfs))
        ChrootMountRegistry.end(rootfs, "run-b")
        assertFalse(ChrootMountRegistry.isBusy(rootfs))

        ChrootConfiguration(rootfs, "/root").createRun()
        assertTrue(ChrootMountRegistry.isBusy(rootfs))
        assertThrows(IllegalStateException::class.java) { ChrootMountRegistry.beginDeletion(rootfs) }
    }

    @Test
    fun `launch metadata is nonce isolated from stale legacy files`(@TempDir directory: Path) {
        val instance = Files.createDirectory(directory.resolve("arch"))
        val configuration = ChrootConfiguration(Files.createDirectory(instance.resolve("rootfs")), "/root")
        Files.writeString(instance.resolve("chroot.pid"), "1")
        Files.writeString(instance.resolve("chroot.stage"), "EXEC")

        val first = configuration.createRun()
        val second = configuration.createRun()

        assertFalse(first.directory == second.directory)
        assertEquals(first.nonce, Files.readString(first.directory.resolve("nonce")))
        assertEquals(second.nonce, Files.readString(second.directory.resolve("nonce")))
        assertFalse(Files.exists(first.pidFile))
        assertFalse(Files.exists(second.pidFile))
        assertEquals("1", Files.readString(instance.resolve("chroot.pid")))
    }

    @Test
    fun `run metadata remains until confirmed cleanup removes it`(@TempDir directory: Path) {
        val instance = Files.createDirectory(directory.resolve("arch"))
        val configuration = ChrootConfiguration(Files.createDirectory(instance.resolve("rootfs")), "/root")
        val helper = ChrootRootHelper(configuration)
        val run = configuration.createRun()
        Files.writeString(run.pidFile, "4321")
        Files.writeString(run.startTimeFile, "98765")
        Files.writeString(run.stageFile, "MOUNT")

        assertEquals(listOf(run.nonce), configuration.pendingRuns().map(ChrootRun::nonce))
        assertTrue(Files.exists(run.stageFile))
        helper.removeRunMetadata(run)
        assertFalse(Files.exists(run.directory))
        assertTrue(configuration.pendingRuns().isEmpty())
    }

    @Test
    fun `rooted atomic edit uses chroot helper argv and preserves mode before rename`() {
        val helper = ChrootRootHelper(ChrootConfiguration(Path.of("/instances/arch/rootfs"), "/root"))
        val command = helper.editCommand("/root/a b.txt", Path.of("/instances/arch/outputs/input"))
        assertTrue(command.contains("cp -- '/instances/arch/outputs/input' '/instances/arch/rootfs/tmp/.weagent-input'"))
        assertTrue(command.contains("chroot '/instances/arch/rootfs' /bin/sh -c "))
        assertTrue(command.contains("stat -c %a"))
        assertTrue(command.contains("chmod \"\$mode\""))
        assertTrue(command.contains("mv -f --"))
        assertTrue(command.endsWith("'/root/a b.txt' '/tmp/.weagent-input'"))
    }

    @Test
    fun `SELinux denial wins over namespace and mount stage classification`() {
        assertTrue(classifyChrootFailure("NAMESPACE", 70, "unshare: Operation not permitted") is ChrootFailure.Selinux)
        assertTrue(classifyChrootFailure("NAMESPACE", 70, "mount: avc: denied { mounton }") is ChrootFailure.Selinux)
        assertTrue(classifyChrootFailure("MOUNT", 71, "mount --make-rprivate: Permission denied") is ChrootFailure.Selinux)
        assertTrue(classifyChrootFailure("MOUNT", 71, "mount failed") is ChrootFailure.Mount)
        assertEquals(null, classifyChrootFailure("EXEC", 126, "/bin/bash: file: Permission denied"))
        assertEquals(null, classifyChrootFailure("EXEC", 126, "chroot: Operation not permitted"))
    }

    private fun publishedRootfs(instances: Path, name: String): Path {
        val instance = Files.createDirectories(instances.resolve(name))
        Files.writeString(instance.resolve(ArchLinuxInstanceInstaller.PUBLISHED_MARKER), "1")
        val rootfs = Files.createDirectories(instance.resolve("rootfs"))
        listOf("rootfs/bin/bash", "rootfs/usr/bin/invoke_tool", "bin/proot", "bin/loader").forEach { relative ->
            val file = instance.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "x")
            file.toFile().setExecutable(true)
        }
        listOf("etc/resolv.conf").forEach { relative ->
            val file = rootfs.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "x")
        }
        return rootfs
    }

    private fun environmentEntity(type: LinuxEnvironmentType) =
        dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity(
            id = "arch", name = "Arch", type = type, workingDirectory = "/root",
            rootfsPath = "/instances/arch/rootfs", bridgePath = "/usr/bin/invoke_tool",
        )
}
