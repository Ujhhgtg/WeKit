package dev.ujhhgtg.wekit.agent.environment

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.createChrootEnvironment("Root Arch", highRiskApproved = false, instanceId = "root-arch") }
        }
        assertFalse(installed)
    }

    private fun environmentEntity(type: LinuxEnvironmentType) =
        dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity(
            id = "arch", name = "Arch", type = type, workingDirectory = "/root",
            rootfsPath = "/instances/arch/rootfs", bridgePath = "/usr/bin/invoke_tool",
        )
}
