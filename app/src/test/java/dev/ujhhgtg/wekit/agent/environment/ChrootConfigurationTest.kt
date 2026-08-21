package dev.ujhhgtg.wekit.agent.environment

import kotlin.io.path.writeText
import dev.ujhhgtg.wekit.utils.fs.asPath
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
            ChrootConfiguration("/instances/arch/rootfs".asPath, "/root/../data")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChrootConfiguration(
                "/instances/arch/rootfs".asPath, "/root",
                listOf(ChrootBind("/data/local/tmp".asPath, "/storage/tmp")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChrootConfiguration(
                "/instances/arch/rootfs".asPath, "/root",
                listOf(ChrootBind("/storage/emulated/0".asPath, "/host")),
            )
        }
    }

    @Test
    fun `mount arguments contain only pseudo mounts and allowlisted binds`() {
        val configuration = ChrootConfiguration(
            "/instances/arch/rootfs".asPath, "/root",
            listOf(ChrootBind("/storage/emulated/0/Documents".asPath, "/storage/Documents")),
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
        val configuration = ChrootConfiguration("/instances/arch/rootfs".asPath, "/root")
        val run = ChrootRun(UUID.randomUUID().toString(), "/instances/arch/chroot-runs/test".asPath)
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
        assertTrue(script.indexOf("/ns/mnt") < script.indexOf("NAMESPACE >"))
        assertTrue(script.contains(run.mountNamespaceFile.toString()))
        assertTrue(script.contains("trap cleanup EXIT HUP INT TERM"))
        assertTrue(script.contains("test -r '/instances/arch/rootfs/etc/resolv.conf'"))
        val hostArgv = configuration.hostLaunchArgv(run, "/system/bin/su".asPath, listOf("/bin/bash"), emptyMap())
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
            ArchLinuxInstanceLayout.validatePublishedRootfs("/".asPath, instances)
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
    fun `cleanup command delegates exact identity to process bound native helper`() {
        val rootfs = "/instances/arch/rootfs".asPath
        val helper = ChrootRootHelper(ChrootConfiguration(rootfs, "/root"))
        val run = ChrootRun("11111111-1111-1111-1111-111111111111", "/instances/arch/chroot-runs/11111111-1111-1111-1111-111111111111".asPath)
        val command = helper.cleanupCommand(
            "/data/user/0/dev.ujhhgtg.wekit/files/chroot_cleanup".asPath, run, 4321, "98765",
            "22222222-2222-2222-2222-222222222222", "4026533001",
        )
        assertTrue(command.startsWith("'/data/user/0/dev.ujhhgtg.wekit/files/chroot_cleanup' 'cleanup' '4321' '98765'"))
        assertTrue(command.contains("'${run.cmdlineMarker}'"))
        assertTrue(command.contains("'4026533001' '/instances/arch/rootfs'"))
        assertFalse(command.contains("kill"))
        assertFalse(command.contains("nsenter"))
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
    fun `missing pid metadata is removable only when launch never began`(@TempDir directory: Path) = runBlocking {
        val instance = Files.createDirectory(directory.resolve("arch"))
        val configuration = ChrootConfiguration(Files.createDirectory(instance.resolve("rootfs")), "/root")
        val helper = ChrootRootHelper(configuration)
        val safe = configuration.createRun()
        helper.cleanupNamespace(safe)
        assertFalse(Files.exists(safe.directory))

        val uncertain = configuration.createRun()
        uncertain.stageFile.writeText("NAMESPACE")
        assertThrows(ChrootFailure.Cleanup::class.java) { runBlocking { helper.cleanupNamespace(uncertain) } }
        assertTrue(Files.exists(uncertain.directory))
        assertTrue(ChrootMountRegistry.isBusy(configuration.rootfs))

        val unknown = configuration.createRun()
        Files.delete(unknown.stageFile)
        assertThrows(ChrootFailure.Cleanup::class.java) { runBlocking { helper.cleanupNamespace(unknown) } }
        assertTrue(Files.exists(unknown.directory))
    }

    @Test
    fun `active token and persisted runs each prevent another launch`(@TempDir directory: Path) {
        val rootfs = Files.createDirectories(directory.resolve("arch/rootfs"))
        ChrootMountRegistry.begin(rootfs, "run-a")
        assertThrows(IllegalStateException::class.java) { ChrootMountRegistry.begin(rootfs, "run-b") }
        ChrootMountRegistry.end(rootfs, "run-a")
        assertFalse(ChrootMountRegistry.isBusy(rootfs))

        ChrootConfiguration(rootfs, "/root").createRun()
        assertTrue(ChrootMountRegistry.isBusy(rootfs))
        assertThrows(IllegalStateException::class.java) { ChrootMountRegistry.beginDeletion(rootfs) }
    }

    @Test
    fun `launch metadata is nonce isolated from stale legacy files`(@TempDir directory: Path) {
        val instance = Files.createDirectory(directory.resolve("arch"))
        val configuration = ChrootConfiguration(Files.createDirectory(instance.resolve("rootfs")), "/root")
        instance.resolve("chroot.pid").writeText("1")
        instance.resolve("chroot.stage").writeText("EXEC")

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
        run.pidFile.writeText("4321")
        run.startTimeFile.writeText("98765")
        run.stageFile.writeText("MOUNT")

        assertEquals(listOf(run.nonce), configuration.pendingRuns().map(ChrootRun::nonce))
        assertTrue(Files.exists(run.stageFile))
        helper.removeRunMetadata(run)
        assertFalse(Files.exists(run.directory))
        assertTrue(configuration.pendingRuns().isEmpty())
    }

    @Test
    fun `missing namespace identity retains metadata and busy state`(@TempDir directory: Path) {
        val instance = Files.createDirectory(directory.resolve("arch"))
        val configuration = ChrootConfiguration(Files.createDirectory(instance.resolve("rootfs")), "/root")
        val run = configuration.createRun()
        run.pidFile.writeText("4321")
        run.startTimeFile.writeText("98765")
        run.bootIdFile.writeText("22222222-2222-2222-2222-222222222222")
        run.stageFile.writeText("EXEC")

        assertThrows(ChrootFailure.Cleanup::class.java) {
            runBlocking { ChrootRootHelper(configuration).cleanupNamespace(run) }
        }
        assertTrue(Files.exists(run.directory))
        assertTrue(ChrootMountRegistry.isBusy(configuration.rootfs))
    }

    @Test
    fun `rooted atomic edit uses chroot helper argv and preserves mode before rename`() {
        val helper = ChrootRootHelper(ChrootConfiguration("/instances/arch/rootfs".asPath, "/root"))
        val command = helper.editCommand("/root/a b.txt", "/instances/arch/outputs/input".asPath)
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
        instance.resolve(ArchLinuxInstanceInstaller.PUBLISHED_MARKER).writeText("1")
        val rootfs = Files.createDirectories(instance.resolve("rootfs"))
        listOf("rootfs/bin/bash", "rootfs/usr/bin/invoke_tool", "bin/proot", "bin/loader").forEach { relative ->
            val file = instance.resolve(relative)
            Files.createDirectories(file.parent)
            file.writeText("x")
            file.toFile().setExecutable(true)
        }
        listOf("etc/resolv.conf").forEach { relative ->
            val file = rootfs.resolve(relative)
            Files.createDirectories(file.parent)
            file.writeText("x")
        }
        return rootfs
    }

    private fun environmentEntity(type: LinuxEnvironmentType) =
        dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity(
            id = "arch", name = "Arch", type = type, workingDirectory = "/root",
            rootfsPath = "/instances/arch/rootfs", bridgePath = "/usr/bin/invoke_tool",
        )
}
