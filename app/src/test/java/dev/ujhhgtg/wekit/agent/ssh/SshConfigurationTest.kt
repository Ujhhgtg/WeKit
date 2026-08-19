package dev.ujhhgtg.wekit.agent.ssh

import dev.ujhhgtg.wekit.agent.environment.SshConfiguration
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentManager
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.NATIVE_ENVIRONMENT_ID
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SshConfigurationTest {
    @Test
    fun `credentials round trip without exposing secret fields`() {
        val credentials = SshCredentials.PrivateKey("-----BEGIN PRIVATE KEY-----\nkey", "secret")
        assertEquals(credentials, SshCredentialCodec.decode(SshCredentialCodec.encode(credentials)))
        assertEquals(SshCredentials.Password("password"), SshCredentialCodec.decode(SshCredentialCodec.encode(SshCredentials.Password("password"))))
    }

    @Test
    fun `host key is never trusted on first use and changes are blocked`() {
        val first = SshHostKey("ssh-ed25519", "SHA256:first")
        val changed = SshHostKey("ssh-ed25519", "SHA256:changed")
        assertEquals(SshHostKeyDecision.CONFIRMATION_REQUIRED, SshHostKeyVerifier(null).verify(first))
        assertEquals(SshHostKeyDecision.MATCH, SshHostKeyVerifier(first).verify(first))
        assertEquals(SshHostKeyDecision.CHANGED, SshHostKeyVerifier(first).verify(changed))
    }

    @Test
    fun `explicitly confirmed observed host key is persisted`() = kotlinx.coroutines.runBlocking {
        val observed = SshHostKey("ssh-ed25519", "SHA256:observed")
        val endpoint = SshEndpoint("host", 22, "user")
        val original = LinuxEnvironmentEntity(
            id = "ssh", name = "SSH", type = LinuxEnvironmentType.SSH, workingDirectory = "/tmp",
            sshHost = "host", sshPort = 22, sshUsername = "user",
        )
        var stored = original
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = EnvironmentSnapshot(
                id = NATIVE_ENVIRONMENT_ID,
                displayName = "native",
                type = LinuxEnvironmentType.NATIVE,
                operatingSystem = "test",
                architecture = "test",
                shell = "/bin/sh",
                workingDirectory = "/tmp",
                bridgeLocation = null,
                privilegesAndCapabilities = "test",
            ),
            getEnvironment = { stored },
            persistEnvironment = { stored = it },
        )
        manager.confirmSshHostKey("ssh", endpoint, observed)
        assertEquals(observed.algorithm, stored.sshHostKeyAlgorithm)
        assertEquals(observed.fingerprint, stored.sshHostKeyFingerprint)
        assertEquals(SshHostKeyDecision.MATCH, SshHostKeyVerifier(observed).verify(observed))
        assertTrue(SshHostKeyVerifier(observed).verify(SshHostKey(observed.algorithm, "SHA256:changed")) == SshHostKeyDecision.CHANGED)
    }

    @Test
    fun `confirmation is rejected if endpoint changed after observation`() = kotlinx.coroutines.runBlocking {
        val observed = SshHostKey("ssh-ed25519", "SHA256:observed")
        var stored = LinuxEnvironmentEntity(
            id = "ssh", name = "SSH", type = LinuxEnvironmentType.SSH, workingDirectory = "/tmp",
            sshHost = "new-host", sshPort = 22, sshUsername = "user",
        )
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = testNativeSnapshot(),
            getEnvironment = { stored },
            persistEnvironment = { stored = it },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager.confirmSshHostKey("ssh", SshEndpoint("old-host", 22, "user"), observed)
            }
        }

        assertTrue(error.message!!.contains("endpoint changed"))
        assertEquals(null, stored.sshHostKeyFingerprint)
    }

    @Test
    fun `editing SSH endpoint clears the trusted host key`() = kotlinx.coroutines.runBlocking {
        var stored = LinuxEnvironmentEntity(
            id = "ssh", name = "SSH", type = LinuxEnvironmentType.SSH, workingDirectory = "/tmp",
            sshHost = "old-host", sshPort = 22, sshUsername = "user",
            sshAuthenticationType = "PASSWORD",
            sshHostKeyAlgorithm = "ssh-ed25519", sshHostKeyFingerprint = "SHA256:trusted",
        )
        val manager = LinuxEnvironmentManager(
            nativeSnapshot = testNativeSnapshot(),
            getEnvironment = { stored },
            persistEnvironment = { stored = it },
        )

        manager.upsert(stored.copy(sshHost = "new-host"))

        assertEquals(null, stored.sshHostKeyAlgorithm)
        assertEquals(null, stored.sshHostKeyFingerprint)
    }

    private fun testNativeSnapshot() = EnvironmentSnapshot(
        id = NATIVE_ENVIRONMENT_ID,
        displayName = "native",
        type = LinuxEnvironmentType.NATIVE,
        operatingSystem = "test",
        architecture = "test",
        shell = "/bin/sh",
        workingDirectory = Path.of("/tmp").toString(),
        bridgeLocation = null,
        privilegesAndCapabilities = "test",
    )

    @Test
    fun `configuration rejects invalid endpoints and incomplete pins`() {
        assertThrows(IllegalArgumentException::class.java) { SshConfiguration("", 22, "user", null) }
        assertThrows(IllegalArgumentException::class.java) { SshConfiguration("host", 0, "user", null) }
        assertThrows(IllegalArgumentException::class.java) {
            SshConfiguration("host", 22, "user", SshHostKey("", "SHA256:key"))
        }
    }
}
