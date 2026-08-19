package dev.ujhhgtg.wekit.agent.ssh

import dev.ujhhgtg.wekit.agent.environment.SshConfiguration
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentManager
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import dev.ujhhgtg.wekit.agent.environment.NATIVE_ENVIRONMENT_ID
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
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
        manager.confirmSshHostKey("ssh", observed)
        assertEquals(observed.algorithm, stored.sshHostKeyAlgorithm)
        assertEquals(observed.fingerprint, stored.sshHostKeyFingerprint)
        assertEquals(SshHostKeyDecision.MATCH, SshHostKeyVerifier(observed).verify(observed))
        assertTrue(SshHostKeyVerifier(observed).verify(SshHostKey(observed.algorithm, "SHA256:changed")) == SshHostKeyDecision.CHANGED)
    }

    @Test
    fun `configuration rejects invalid endpoints and incomplete pins`() {
        assertThrows(IllegalArgumentException::class.java) { SshConfiguration("", 22, "user", null) }
        assertThrows(IllegalArgumentException::class.java) { SshConfiguration("host", 0, "user", null) }
        assertThrows(IllegalArgumentException::class.java) {
            SshConfiguration("host", 22, "user", SshHostKey("", "SHA256:key"))
        }
    }
}
