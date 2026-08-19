package dev.ujhhgtg.wekit.agent.ssh

import java.security.MessageDigest
import java.util.Base64

data class SshHostKey(val algorithm: String, val fingerprint: String)

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

sealed class SshHostKeyException(message: String, val observed: SshHostKey) : SecurityException(message) {
    class ConfirmationRequired(observed: SshHostKey) :
        SshHostKeyException("SSH host key requires explicit confirmation", observed)

    class Changed(observed: SshHostKey) :
        SshHostKeyException("SSH host key changed; explicit replacement is required", observed)
}

class SshAuthenticationException(message: String, cause: Throwable? = null) :
    SecurityException(message, cause)
