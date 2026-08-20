package dev.ujhhgtg.wekit.agent.ssh

import dev.ujhhgtg.wekit.agent.environment.SshConfiguration
import dev.ujhhgtg.wekit.agent.environment.SshConnectionManager
import dev.ujhhgtg.wekit.agent.environment.SshIndeterminateExecutionException
import dev.ujhhgtg.wekit.agent.environment.SshBackend
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import java.net.InetAddress
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SshOpenSshIntegrationTest {
    @Test
    fun `OpenSSH password exec sftp pty and reverse forwarding`() = runBlocking {
        val port = (System.getProperty("wekit.sshTest.port") ?: System.getenv("WEKIT_SSH_TEST_PORT"))?.toIntOrNull()
        assumeTrue(port != null, "set wekit.sshTest.port to run SSH interoperability")
        val sshPort = requireNotNull(port)
        val fingerprint = requireNotNull(
            System.getProperty("wekit.sshTest.fingerprint") ?: System.getenv("WEKIT_SSH_TEST_FINGERPRINT")
        )
        val algorithm = System.getProperty("wekit.sshTest.algorithm")
            ?: System.getenv("WEKIT_SSH_TEST_ALGORITHM")
            ?: "ssh-ed25519"
        val rejected = SshConnectionManager(
            SshConfiguration("127.0.0.1", sshPort, "wekit", SshHostKey(algorithm, "SHA256:wrong")),
            SshCredentials.Password("wekit-password"),
        )
        try {
            assertTrue(runCatching { rejected.execute("true", 10_000) }.exceptionOrNull() is SshHostKeyException.Changed)
        } finally {
            rejected.close()
        }
        val manager = SshConnectionManager(
            SshConfiguration("127.0.0.1", sshPort, "wekit", SshHostKey(algorithm, fingerprint)),
            SshCredentials.Password("wekit-password"),
        )
        try {
            val exec = manager.execute("/bin/bash -lc 'printf exec-ok'", 10_000)
            assertEquals(0, exec.exitCode)
            assertEquals("exec-ok", exec.stdout.toString(StandardCharsets.UTF_8))

            val home = manager.homeDirectory()
            val path = "$home/weagent-sftp-test"
            val missing = manager.readFile(path, 1024)
            manager.atomicWrite(path, missing, "first".toByteArray())
            val first = manager.readFile(path, 1024)
            manager.atomicWrite(path, first, "second".toByteArray())
            assertEquals("second", manager.readFile(path, 1024).bytes.toString(StandardCharsets.UTF_8))

            val backend = SshBackend(
                EnvironmentSnapshot(
                    id = "ssh-test",
                    displayName = "SSH test",
                    type = LinuxEnvironmentType.SSH,
                    operatingSystem = "Linux",
                    architecture = "test",
                    shell = "/bin/bash",
                    workingDirectory = home,
                    bridgeLocation = null,
                    privilegesAndCapabilities = "test account",
                ),
                manager,
            )
            assertTrue(backend.ensureBridge().executablePath.endsWith("/.local/bin/invoke_tool"))

            val terminal = manager.openTerminal("printf pty-ok; exit", emptyMap(), 100, 30)
            withTimeout(10_000) { terminal.waitForExit() }
            val terminalBytes = ByteArrayOutputStream()
            while (true) {
                val bytes = terminal.read(4096)
                if (bytes == null) {
                    delay(10)
                } else if (bytes.isEmpty()) {
                    break
                } else {
                    terminalBytes.write(bytes)
                }
            }
            val terminalOutput = terminalBytes.toString(StandardCharsets.UTF_8.name())
            assertTrue(terminalOutput.contains("pty-ok"))
            terminal.close()

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val forward = manager.openReverseForward(server.localPort)
                try {
                    val responder = async(kotlinx.coroutines.Dispatchers.IO) {
                        server.accept().use { socket ->
                            val received = socket.getInputStream().readNBytes(4)
                            socket.getOutputStream().write("pong".toByteArray())
                            received.toString(StandardCharsets.UTF_8)
                        }
                    }
                    val forwarded = manager.execute(
                        "/bin/bash -lc 'exec 3<>/dev/tcp/127.0.0.1/${forward.remotePort}; printf ping >&3; dd bs=1 count=4 status=none <&3'",
                        10_000,
                    )
                    assertEquals("ping", responder.await())
                    assertEquals("pong", forwarded.stdout.toString(StandardCharsets.UTF_8))
                } finally {
                    forward.close()
                }
            }
            manager.execute("rm -f '$path'", 10_000)

            val privateKeyPath = System.getenv("WEKIT_SSH_TEST_PRIVATE_KEY")
            if (privateKeyPath != null) {
                val keyManager = SshConnectionManager(
                    SshConfiguration("127.0.0.1", sshPort, "wekit", SshHostKey(algorithm, fingerprint)),
                    SshCredentials.PrivateKey(
                        Files.readString(Path.of(privateKeyPath)),
                        System.getenv("WEKIT_SSH_TEST_PRIVATE_KEY_PASSPHRASE"),
                    ),
                )
                try {
                    assertEquals(0, keyManager.execute("true", 10_000).exitCode)
                } finally {
                    keyManager.close()
                }
            }
            Unit
        } finally {
            manager.close()
        }
    }

    @Test
    fun `OpenSSH disconnect after submission is indeterminate and never replayed`() = runBlocking {
        val container = System.getenv("WEKIT_SSH_TEST_DISCONNECT_CONTAINER")
        assumeTrue(container != null, "set WEKIT_SSH_TEST_DISCONNECT_CONTAINER to run disconnect interoperability")
        val port = requireNotNull(System.getenv("WEKIT_SSH_TEST_PORT")).toInt()
        val fingerprint = requireNotNull(System.getenv("WEKIT_SSH_TEST_FINGERPRINT"))
        val algorithm = System.getenv("WEKIT_SSH_TEST_ALGORITHM") ?: "ssh-ed25519"
        val manager = SshConnectionManager(
            SshConfiguration("127.0.0.1", port, "wekit", SshHostKey(algorithm, fingerprint)),
            SshCredentials.Password("wekit-password"),
        )
        try {
            assertEquals(0, manager.execute("true", 10_000).exitCode)
            val failure = supervisorScope {
                val submitted = async { manager.execute("sleep 30", 60_000) }
                delay(500)
                assertEquals(0, ProcessBuilder("podman", "stop", container).start().waitFor())
                runCatching { submitted.await() }.exceptionOrNull()
            }
            assertTrue(failure is SshIndeterminateExecutionException)

            assertEquals(0, ProcessBuilder("podman", "start", container).start().waitFor())
            delay(2_000)
            assertEquals("reconnected", manager.execute("printf reconnected", 10_000).stdout.toString(StandardCharsets.UTF_8))
            Unit
        } finally {
            manager.close()
        }
    }
}
