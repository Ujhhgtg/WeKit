package dev.ujhhgtg.wekit.bootstrap

import dev.ujhhgtg.wekit.bootstrap.auth.WireProtocol
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * [tlsPins] are base64 SHA-256 hashes of SubjectPublicKeyInfo for CA keys that may anchor the
 * [apiOrigin] TLS chain.
 */
data class BootstrapConfig(
    val apiOrigin: String,
    val telegramBotUsername: String,
    val releaseKeys: Map<String, PublicKey>,
    val tlsPins: Set<String>,
) {
    companion object {
        private const val API_ORIGIN = "https://auth.wekit.ujhhgtg.dev"
        private const val TELEGRAM_BOT_USERNAME = "ujhhgtg_wekit_bot"

        /** Keep in sync with `releasePublicKeys` in assets/remote-loader.json, which the server deploy reads. */
        private val RELEASE_PUBLIC_KEYS = mapOf(
            "release-20261011-1" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE_5JoFKeoY989hXH2Vj5ZTtl7Az2QB63WSDpjkn-0K6LLfS0KZYa-v-n7rpuSQ6WUNU-w3VwqNtp6lhpY77BKqw",
        )

        /**
         * Root CA keys rather than leaf or intermediate keys: the leaf rotates every few months, and the
         * edge provider moves between Google Trust Services and Let's Encrypt. A root pin still rejects
         * every certificate chained to a user-installed or foreign system CA. A cross-signed root shares
         * its key with the self-signed root, so these also cover GlobalSign-cross-signed GTS chains.
         * A CA outside this set needs a module update before the API is reachable again.
         */
        private val TLS_PINS = setOf(
            "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
            "QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=", // GTS Root R3
            "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", // GTS Root R4
            "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1
            "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2
        )

        val DEFAULT: BootstrapConfig by lazy {
            val uri = URI(API_ORIGIN)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.isEmpty())
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKeys = RELEASE_PUBLIC_KEYS.mapValues { (id, encoded) ->
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                keyFactory.generatePublic(X509EncodedKeySpec(WireProtocol.decode(encoded))).also {
                    require(WireProtocol.isP256(it)) { "Release signing key must be P-256" }
                }
            }
            BootstrapConfig(API_ORIGIN, TELEGRAM_BOT_USERNAME, publicKeys, TLS_PINS)
        }
    }
}
