package dev.ujhhgtg.wekit.agent.environment

import java.nio.file.Path
import java.nio.file.Files
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
        val script = configuration.launchScript(
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
        val hostArgv = configuration.hostLaunchArgv(Path.of("/system/bin/su"), listOf("/bin/bash"), emptyMap())
        assertEquals("/system/bin/su", hostArgv.first())
        assertFalse(hostArgv.last().contains("setsid"))
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
        val command = helper.cleanupCommand(4321)
        assertTrue(command.indexOf("kill -TERM") < command.indexOf("nsenter"))
        assertTrue(command.indexOf("nsenter") < command.indexOf("kill -KILL"))
        assertTrue(command.indexOf("kill -KILL") < command.indexOf("test ! -e /proc/4321"))
        ChrootMountRegistry.begin(rootfs)
        try {
            assertTrue(ChrootMountRegistry.isBusy(rootfs))
            assertThrows(IllegalStateException::class.java) { ChrootMountRegistry.beginDeletion(rootfs) }
        } finally {
            ChrootMountRegistry.end(rootfs)
        }
        assertFalse(ChrootMountRegistry.isBusy(rootfs))
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
