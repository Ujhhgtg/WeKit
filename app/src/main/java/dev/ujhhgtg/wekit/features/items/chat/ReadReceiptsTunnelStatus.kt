package dev.ujhhgtg.wekit.features.items.chat

import java.util.Collections
import java.util.UUID

enum class ReadReceiptsTunnelMode {
    QUICK,
    TOKEN,
    BROWSER_LOGIN,
}

enum class ReadReceiptsTunnelState {
    STOPPED,
    STARTING,
    CONNECTED,
    RECONNECTING,
    NEEDS_USER_ACTION,
    FAILED,
    STOPPING,
}

enum class ReadReceiptsTunnelErrorCode {
    VISIBLE_SETTINGS_REQUIRED,
    TOKEN_REQUIRED,
    TOKEN_INVALID,
    BROWSER_CREDENTIAL_INVALID,
    CREDENTIAL_SAVE_FAILED,
    START_HANDOFF_TIMEOUT,
    STOP_TIMEOUT,
    SERVICE_UNAVAILABLE,
    HEALTH_CHECK_FAILED,
    UNEXPECTED_FAILURE,
}

data class ReadReceiptsTunnelStatus(
    val state: ReadReceiptsTunnelState,
    val publicUrl: String? = null,
    val errorCode: ReadReceiptsTunnelErrorCode? = null,
    val needsNotificationSettings: Boolean = false,
)

class ReadReceiptsTunnelException(
    val errorCode: ReadReceiptsTunnelErrorCode,
    diagnostic: String,
    cause: Throwable? = null,
) : IllegalStateException(diagnostic, cause)

data class CloudflareLoginState(
    val authorizationUrl: String?,
    val state: ReadReceiptsTunnelState,
    val error: String?,
) {
    init {
        require(authorizationUrl == null || authorizationUrl.length <= MAX_AUTHORIZATION_URL_CHARS)
        require(error == null || error.length <= MAX_ERROR_CHARS)
    }

    private companion object {
        const val MAX_AUTHORIZATION_URL_CHARS = 2048
        const val MAX_ERROR_CHARS = 256
    }
}

data class NativeCloudflareLoginStatus(
    val generation: Long,
    val loginState: CloudflareLoginState,
    val accountId: String,
    val selectedTunnelId: String?,
    val selectedHostname: String?,
) {
    init {
        require(generation > 0)
    }
}

data class NativeExistingTunnelList(
    val generation: Long,
    val tunnels: List<ExistingTunnel>,
    val error: String?,
) {
    init {
        require(generation > 0)
    }
}

@ConsistentCopyVisibility
data class ExistingTunnel private constructor(
    val id: String,
    val name: String,
    val hostnames: List<String>,
) {
    init {
        require(canonicalTunnelId(id) == id)
        require(name.isNotEmpty() && name == name.trim() && name.utf8Size() <= MAX_NAME_BYTES)
        require(name.none(Char::isISOControl))
        require(hostnames.size <= MAX_HOSTNAMES)
        require(hostnames.distinct().size == hostnames.size)
        require(hostnames.all { canonicalConfiguredHostname(it) == it })
    }

    companion object {
        private const val MAX_NAME_BYTES = 128
        private const val MAX_HOSTNAMES = 100
        private val UUID_PATTERN =
            Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

        fun create(id: String, name: String, hostnames: List<String>): ExistingTunnel? {
            if (hostnames.size > MAX_HOSTNAMES) return null
            val canonicalId = canonicalTunnelId(id) ?: return null
            val canonicalName = name.trim()
            if (
                canonicalName.isEmpty() || canonicalName.utf8Size() > MAX_NAME_BYTES ||
                canonicalName.any(Char::isISOControl)
            ) {
                return null
            }
            val canonicalHostnames = hostnames.map { canonicalConfiguredHostname(it) ?: return null }
            if (canonicalHostnames.distinct().size != canonicalHostnames.size) return null
            return ExistingTunnel(
                canonicalId,
                canonicalName,
                Collections.unmodifiableList(ArrayList(canonicalHostnames)),
            )
        }

        fun isCanonicalId(value: String): Boolean = canonicalTunnelId(value) == value

        private fun canonicalTunnelId(value: String): String? {
            if (!UUID_PATTERN.matches(value)) return null
            return runCatching {
                UUID.fromString(value).takeUnless { it == UUID(0, 0) }?.toString()
            }.getOrNull()
        }

        private fun canonicalConfiguredHostname(value: String): String? {
            if (value.isEmpty() || value != value.trim() || value.any(Char::isWhitespace)) return null
            val withoutFinalDot = value.removeSuffix(".")
            if (withoutFinalDot.isEmpty() || withoutFinalDot.contains("//")) return null
            return ReadReceiptsTunnelHostnames.canonicalPublicRoot("https://$withoutFinalDot")
                ?.removePrefix("https://")
        }

        private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
    }
}
