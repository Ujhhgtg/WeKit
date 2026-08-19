package dev.ujhhgtg.wekit.agent.ssh

import dev.ujhhgtg.wekit.agent.environment.SshConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `configuration rejects invalid endpoints and incomplete pins`() {
        assertThrows(IllegalArgumentException::class.java) { SshConfiguration("", 22, "user", null) }
        assertThrows(IllegalArgumentException::class.java) { SshConfiguration("host", 0, "user", null) }
        assertThrows(IllegalArgumentException::class.java) {
            SshConfiguration("host", 22, "user", SshHostKey("", "SHA256:key"))
        }
    }
}
