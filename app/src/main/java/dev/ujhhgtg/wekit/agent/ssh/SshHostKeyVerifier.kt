package dev.ujhhgtg.wekit.agent.ssh

import java.security.MessageDigest
import java.util.Base64

data class SshHostKey(val algorithm: String, val fingerprint: String)

data class SshEndpoint(val host: String, val port: Int, val username: String)

enum class SshHostKeyDecision { MATCH, CONFIRMATION_REQUIRED, CHANGED }

class SshHostKeyVerifier(private val confirmed: SshHostKey?) {
    fun verify(observed: SshHostKey): SshHostKeyDecision = when {
        confirmed == null -> SshHostKeyDecision.CONFIRMATION_REQUIRED
        confirmed == observed -> SshHostKeyDecision.MATCH
        else -> SshHostKeyDecision.CHANGED
    }

    companion object {
        fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}

sealed class SshHostKeyException(
    message: String,
    val endpoint: SshEndpoint,
    val observed: SshHostKey,
) : SecurityException(message) {
    class ConfirmationRequired(endpoint: SshEndpoint, observed: SshHostKey) :
        SshHostKeyException("SSH host key requires explicit confirmation", endpoint, observed)

    class Changed(endpoint: SshEndpoint, observed: SshHostKey) :
        SshHostKeyException("SSH host key changed; explicit replacement is required", endpoint, observed)
}

class SshAuthenticationException(message: String, cause: Throwable? = null) :
    SecurityException(message, cause)
