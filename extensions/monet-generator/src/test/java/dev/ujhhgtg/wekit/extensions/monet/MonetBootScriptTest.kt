package dev.ujhhgtg.wekit.extensions.monet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

class MonetBootScriptTest {

    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest(name = "bubble={0}, corners={1}, tab={2}")
    @MethodSource("styleChoices")
    fun `current scope enables exactly the selected overlays`(
        bubbleStyle: String,
        corners: Boolean,
        tabStyle: String,
    ) {
        val result = runService(config(bubbleStyle, corners, tabStyle, "CURRENT", 10), users = "0,10")

        assertEquals(0, result.exitCode, result.output)
        val enabled = result.calls.filter { it.startsWith("cmd overlay enable --user 10 ") }
            .map { it.substringAfterLast(' ') }
            .toSet()
        assertEquals(expectedEnabled(bubbleStyle, corners, tabStyle), enabled)
        assertEquals(KNOWN_PACKAGES, result.calls
            .filter { it.startsWith("cmd overlay disable --user 10 ") }
            .map { it.substringAfterLast(' ') }
            .toSet())
        assertFalse(result.calls.any { "--user 0" in it })
        assertTrue(result.calls.contains("am force-stop --user 10 com.tencent.mm"))
        assertFalse(result.calls.any { it.startsWith("pm install-existing ") })
    }

    @Test
    fun `all scope combines live users installs existing packages and continues after failure`() {
        val result = runService(
            config("MODERN", corners = false, "SOLID", "ALL", generatedUserId = 10),
            users = "10",
            dataUserIds = setOf(11),
            failOverlayUsers = setOf(10),
        )

        assertNotEquals(0, result.exitCode)
        KNOWN_PACKAGES.forEach { packageName ->
            assertTrue(result.calls.contains("pm install-existing --user 10 $packageName"))
            assertTrue(result.calls.contains("pm install-existing --user 11 $packageName"))
        }
        setOf(10, 11).forEach { userId ->
            val lastInstall = result.calls.indexOfLast { it.startsWith("pm install-existing --user $userId ") }
            val firstDisable = result.calls.indexOfFirst { it.startsWith("cmd overlay disable --user $userId ") }
            assertTrue(lastInstall in 0 until firstDisable)
        }
        assertTrue(result.calls.contains("cmd overlay enable --user 11 monet.com.tencent.mm"))
        assertTrue(result.calls.contains("cmd overlay enable --user 11 monet.solidtab.com.tencent.mm"))
        assertTrue(result.calls.contains("am force-stop --user 10 com.tencent.mm"))
        assertTrue(result.calls.contains("am force-stop --user 11 com.tencent.mm"))
        assertTrue(result.output.contains("user 10"))
        assertTrue(result.output.contains("failure"))
    }

    @Test
    fun `missing current user stops without falling back to user zero`() {
        val result = runService(
            config("CLASSIC", corners = true, "BLUR", "CURRENT", generatedUserId = 10),
            users = "0",
            dataUserIds = setOf(0),
        )

        assertNotEquals(0, result.exitCode)
        assertFalse(result.calls.any { "--user 0" in it })
        assertFalse(result.calls.any { it.startsWith("cmd overlay ") || it.startsWith("am force-stop ") })
        assertTrue(result.output.contains("configured CURRENT user 10 does not exist"))
    }

    @Test
    fun `missing selected package is logged and skipped without blocking other state`() {
        val result = runService(
            config("PRO", corners = false, "SOLID", "CURRENT", generatedUserId = 10),
            users = "10",
            missingPackages = setOf("monet.bubblepro.com.tencent.mm"),
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.calls.contains("cmd overlay enable --user 10 monet.bubblepro.com.tencent.mm"))
        assertTrue(result.calls.contains("cmd overlay enable --user 10 monet.com.tencent.mm"))
        assertTrue(result.calls.contains("cmd overlay enable --user 10 monet.solidtab.com.tencent.mm"))
        assertTrue(result.calls.contains("am force-stop --user 10 com.tencent.mm"))
        assertTrue(result.output.contains("package is unavailable: monet.bubblepro.com.tencent.mm"))
    }

    @Test
    fun `concurrent service and native boot callbacks restore state exactly once`() {
        val result = runInvocations(
            config("CLASSIC", corners = true, "BLUR", "CURRENT", generatedUserId = 10),
            users = "10",
            invocationNames = listOf("service.sh", "boot-completed.sh"),
            concurrent = true,
            commandDelay = "0.01",
        )

        assertEquals(0, result.exitCode, result.output)
        assertEquals(1, result.calls.count { it == "am force-stop --user 10 com.tencent.mm" })
        assertEquals(1, result.calls.count { it == "cmd overlay enable --user 10 monet.com.tencent.mm" })
        assertEquals(KNOWN_PACKAGES.size, result.calls.count { it.startsWith("cmd overlay disable --user 10 ") })
        assertEquals(4, result.calls.count { it.startsWith("cmd overlay enable --user 10 ") })
    }

    @Test
    fun `native boot callback after service completion does not restore twice`() {
        val result = runInvocations(
            config("PRO", corners = false, "SOLID", "CURRENT", generatedUserId = 10),
            users = "10",
            invocationNames = listOf("service.sh", "boot-completed.sh"),
            concurrent = false,
        )

        assertEquals(0, result.exitCode, result.output)
        assertEquals(1, result.calls.count { it == "am force-stop --user 10 com.tencent.mm" })
        assertEquals(1, result.calls.count { it == "cmd overlay enable --user 10 monet.com.tencent.mm" })
        assertEquals(KNOWN_PACKAGES.size, result.calls.count { it.startsWith("cmd overlay disable --user 10 ") })
        assertEquals(3, result.calls.count { it.startsWith("cmd overlay enable --user 10 ") })
    }

    @ParameterizedTest(name = "invalid config line: {0}")
    @MethodSource("invalidConfigLines")
    fun `invalid config values fail closed before package commands`(invalidLine: String) {
        val valid = config("MODERN", corners = true, "BLUR", "CURRENT", generatedUserId = 10)
        val key = invalidLine.substringBefore('=')
        val malicious = valid.lineSequence()
            .map { if (it.startsWith("$key=")) invalidLine else it }
            .joinToString("\n")
        val result = runService(malicious, users = "10")

        assertNotEquals(0, result.exitCode)
        assertFalse(result.calls.any { it.startsWith("pm path ") })
        assertFalse(result.calls.any { it.startsWith("cmd overlay ") || it.startsWith("am force-stop ") })
        assertTrue(result.output.contains("invalid module config"))
    }

    @Test
    fun `config content is never evaluated as shell code`() {
        val marker = tempDir.resolve("injected")
        val malicious = config("MODERN", corners = true, "SOLID", "CURRENT", generatedUserId = 10)
            .replace("bubble_style=MODERN", "bubble_style=\$(touch ${marker.absolutePath})")

        val result = runService(malicious, users = "10")

        assertNotEquals(0, result.exitCode)
        assertFalse(marker.exists())
        assertFalse(result.calls.any { it.startsWith("cmd overlay ") || it.startsWith("am force-stop ") })
    }

    private fun runService(
        config: String,
        users: String,
        dataUserIds: Set<Int> = emptySet(),
        missingPackages: Set<String> = emptySet(),
        failOverlayUsers: Set<Int> = emptySet(),
    ): ShellResult = runInvocations(
        config,
        users,
        dataUserIds,
        missingPackages,
        failOverlayUsers,
        invocationNames = listOf("service.sh"),
        concurrent = false,
    )

    private fun runInvocations(
        config: String,
        users: String,
        dataUserIds: Set<Int> = emptySet(),
        missingPackages: Set<String> = emptySet(),
        failOverlayUsers: Set<Int> = emptySet(),
        invocationNames: List<String>,
        concurrent: Boolean,
        commandDelay: String = "",
    ): ShellResult {
        val runDir = tempDir.resolve("run-${tempDir.list().orEmpty().size}").apply { mkdirs() }
        val moduleDir = runDir.resolve("module").apply { mkdirs() }
        val commandDir = runDir.resolve("bin").apply { mkdirs() }
        val dataUserDir = runDir.resolve("data-user").apply { mkdirs() }
        val bootStateDir = runDir.resolve("dev").apply { mkdirs() }
        dataUserIds.forEach { dataUserDir.resolve(it.toString()).mkdirs() }
        SCRIPT_NAMES.forEach { name ->
            PAYLOAD_DIR.resolve(name).copyTo(moduleDir.resolve(name))
        }
        moduleDir.resolve("config.conf").writeText(config)
        val callLog = runDir.resolve("calls.log")
        writeFakeCommands(commandDir)

        fun start(name: String): Process =
            ProcessBuilder("/bin/sh", moduleDir.resolve(name).absolutePath)
                .directory(moduleDir)
                .redirectErrorStream(true)
                .apply {
                environment()["PATH"] = "${commandDir.absolutePath}:/usr/bin:/bin"
                environment()["MONET_CALL_LOG"] = callLog.absolutePath
                environment()["MONET_FAKE_USERS"] = users
                environment()["MONET_DATA_USER_DIR"] = dataUserDir.absolutePath
                environment()["MONET_BOOT_STATE_DIR"] = bootStateDir.absolutePath
                environment()["MONET_MISSING_PACKAGES"] = missingPackages.joinToString(",")
                environment()["MONET_FAIL_OVERLAY_USERS"] = failOverlayUsers.joinToString(",")
                environment()["MONET_FAKE_COMMAND_DELAY"] = commandDelay
            }
            .start()

        val results = if (concurrent) {
            invocationNames.map(::start).map { process ->
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor() to output
            }
        } else {
            invocationNames.map { name ->
                val process = start(name)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor() to output
            }
        }
        return ShellResult(
            results.firstOrNull { it.first != 0 }?.first ?: 0,
            results.joinToString("\n") { it.second },
            if (callLog.isFile) callLog.readLines() else emptyList(),
        )
    }

    private fun writeFakeCommands(commandDir: File) {
        commandDir.resolve("getprop").writeExecutable(
            """
                #!/bin/sh
                [ "${'$'}1" = "sys.boot_completed" ] && printf '%s\n' 1
            """.trimIndent() + "\n",
        )
        commandDir.resolve("pm").writeExecutable(
            """
                #!/bin/sh
                printf 'pm %s\n' "${'$'}*" >> "${'$'}MONET_CALL_LOG"
                if [ "${'$'}1" = "list" ] && [ "${'$'}2" = "users" ]; then
                  old_ifs=${'$'}IFS
                  IFS=,
                  for user_id in ${'$'}MONET_FAKE_USERS; do
                    case "${'$'}user_id" in ''|*[!0-9]*) continue ;; esac
                    printf '    UserInfo{%s:User%s:13}\n' "${'$'}user_id" "${'$'}user_id"
                  done
                  IFS=${'$'}old_ifs
                  exit 0
                fi
                if [ "${'$'}1" = "path" ]; then
                  case ",${'$'}MONET_MISSING_PACKAGES," in
                    *,"${'$'}2",*) exit 1 ;;
                  esac
                  printf 'package:/system/fake/%s.apk\n' "${'$'}2"
                  exit 0
                fi
                if [ "${'$'}1" = "install-existing" ] && [ "${'$'}2" = "--user" ]; then
                  printf 'Package %s installed for user: %s\n' "${'$'}4" "${'$'}3"
                  exit 0
                fi
                exit 64
            """.trimIndent() + "\n",
        )
        commandDir.resolve("cmd").writeExecutable(
            """
                #!/bin/sh
                printf 'cmd %s\n' "${'$'}*" >> "${'$'}MONET_CALL_LOG"
                [ -z "${'$'}MONET_FAKE_COMMAND_DELAY" ] || /bin/sleep "${'$'}MONET_FAKE_COMMAND_DELAY"
                if [ "${'$'}1" = "overlay" ] && [ "${'$'}3" = "--user" ]; then
                  case ",${'$'}MONET_FAIL_OVERLAY_USERS," in
                    *,"${'$'}4",*) exit 1 ;;
                  esac
                  exit 0
                fi
                exit 64
            """.trimIndent() + "\n",
        )
        commandDir.resolve("am").writeExecutable(
            """
                #!/bin/sh
                printf 'am %s\n' "${'$'}*" >> "${'$'}MONET_CALL_LOG"
                exit 0
            """.trimIndent() + "\n",
        )
    }

    private fun File.writeExecutable(content: String) {
        writeText(content)
        assertTrue(setExecutable(true))
    }

    private fun config(
        bubbleStyle: String,
        corners: Boolean,
        tabStyle: String,
        scope: String,
        generatedUserId: Int,
    ): String = """
        bubble_style=$bubbleStyle
        multi_scene_corners_enabled=$corners
        tab_style=$tabStyle
        user_scope=$scope
        generated_user_id=$generatedUserId
    """.trimIndent() + "\n"

    private fun expectedEnabled(bubbleStyle: String, corners: Boolean, tabStyle: String): Set<String> =
        buildSet {
            add("monet.com.tencent.mm")
            if (bubbleStyle == "CLASSIC") add("monet.classicbubble.com.tencent.mm")
            if (bubbleStyle == "PRO") add("monet.bubblepro.com.tencent.mm")
            if (corners) add("monet.multiscenecorners.com.tencent.mm")
            add(if (tabStyle == "SOLID") "monet.solidtab.com.tencent.mm" else "monet.blurtab.com.tencent.mm")
        }

    private data class ShellResult(val exitCode: Int, val output: String, val calls: List<String>)

    private companion object {
        val PAYLOAD_DIR = File("../../app/embedded/monet")
        val SCRIPT_NAMES = listOf("common.sh", "service.sh", "boot-completed.sh")
        val KNOWN_PACKAGES = setOf(
            "monet.com.tencent.mm",
            "monet.classicbubble.com.tencent.mm",
            "monet.bubblepro.com.tencent.mm",
            "monet.multiscenecorners.com.tencent.mm",
            "monet.solidtab.com.tencent.mm",
            "monet.blurtab.com.tencent.mm",
        )

        @JvmStatic
        fun styleChoices(): Stream<Arguments> = Stream.of("MODERN", "CLASSIC", "PRO").flatMap { bubble ->
            Stream.of(false, true).flatMap { corners ->
                Stream.of("SOLID", "BLUR").map { tab -> Arguments.of(bubble, corners, tab) }
            }
        }

        @JvmStatic
        fun invalidConfigLines(): Stream<String> = Stream.of(
            "bubble_style=LEGACY",
            "multi_scene_corners_enabled=yes",
            "tab_style=AUTO",
            "user_scope=PRIMARY",
            "generated_user_id=10;id",
        )
    }
}
