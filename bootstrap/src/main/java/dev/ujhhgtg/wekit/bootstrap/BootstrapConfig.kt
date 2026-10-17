package dev.ujhhgtg.wekit.bootstrap

import dev.ujhhgtg.wekit.bootstrap.payload.LoaderNatives
import dev.ujhhgtg.wekit.bootstrap.auth.WireProtocol
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/** Immutable protocol values supplied by the native loader, never by bootstrap DEX constants. */
data class BootstrapConfig(
    val telegramBotUsername: String,
    val releaseKeys: Map<String, PublicKey>,
) {
    companion object {
        /** The native library must be loaded before this property is read. */
        val DEFAULT: BootstrapConfig
            get() {
                val values = LoaderNatives.configuration()
                require(values.size == 9) { "native bootstrap configuration is incomplete" }
                val keyFactory = KeyFactory.getInstance("EC")
                val keyId = values[2]
                require(keyId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                val key = keyFactory.generatePublic(X509EncodedKeySpec(WireProtocol.decode(values[3]))).also {
                    require(WireProtocol.isP256(it)) { "release signing key must be P-256" }
                }
                return BootstrapConfig(values[1], mapOf(keyId to key))
            }
    }
}
