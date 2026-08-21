package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.utils.fs.asPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProotCommandTest {
    @Test
    fun `argv keeps command opaque and exposes only approved binds`() {
        val argv = ProotCommand.execArgv(
            "/instance/bin/proot".asPath, "/instance/rootfs".asPath, "/root",
            "printf '%s' 'a; b'", mapOf("WEAGENT_BRIDGE_TOKEN" to "token", "PATH" to "/host/bin"),
        )
        assertEquals("printf '%s' 'a; b'", argv.last())
        assertEquals(listOf("/bin/bash", "-c"), argv.takeLast(3).take(2))
        assertTrue(argv.containsAll(listOf("/dev:/dev", "/proc:/proc", "/sys:/sys")))
        assertFalse(argv.any { it.contains("/data/") || it == "PATH=/host/bin" })
        assertTrue(argv.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin"))
        assertTrue(argv.contains("PWD=/root"))
        assertTrue(argv.contains("WEAGENT_BRIDGE_TOKEN=token"))
    }

    @Test
    fun `storage bind guest path is explicit`() {
        val argv = ProotCommand.launchArgv(
            "/proot".asPath, "/rootfs".asPath, "/root", listOf("/bin/bash", "-l"), emptyMap(),
            listOf(ProotCommand.Bind("/storage/emulated/0/Documents".asPath, "/storage/Documents")),
        )
        assertTrue(argv.contains("/storage/emulated/0/Documents:/storage/Documents"))
    }

    @Test
    fun `rejects relative guest cwd`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProotCommand.launchArgv("/proot".asPath, "/rootfs".asPath, "../host", listOf("bash"), emptyMap())
        }
    }

    @Test
    fun `pid wrapper preserves every proot argument as an opaque positional value`() {
        val argv = listOf("/proot", "-r", "/path with spaces", "/bin/bash", "a; b")

        val wrapped = processWithPidFile("/tmp/process.pid".asPath, argv)

        assertEquals(argv, wrapped.takeLast(argv.size))
        assertEquals("/tmp/process.pid", wrapped[4])
    }
}
