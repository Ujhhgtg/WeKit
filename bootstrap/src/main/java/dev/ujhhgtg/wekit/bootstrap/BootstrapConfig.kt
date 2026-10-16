package dev.ujhhgtg.wekit.bootstrap

import dev.ujhhgtg.wekit.bootstrap.auth.StrictJson
import dev.ujhhgtg.wekit.bootstrap.auth.WireProtocol
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import dev.ujhhgtg.wekit.bootstrap.auth.WireJson

data class BootstrapConfig(val apiOrigin: String, val telegramBotUsername: String, val releaseKeys: Map<String, PublicKey>) {
    companion object {
        fun load(apk: File): BootstrapConfig {
            val bytes = ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/remote-loader.json") ?: error("bootstrap configuration missing")
                require(entry.size in 1..65536)
                zip.getInputStream(entry).use { it.readBytes() }
            }
            val json = WireJson.format.decodeFromJsonElement<ConfigDocument>(StrictJson.objectFrom(bytes))
            val origin = json.apiOrigin
            val uri = URI(origin)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.isEmpty())
            val username = json.telegramBotUsername
            require(username.matches(Regex("[A-Za-z0-9_]{5,32}")))
            val keys = json.releasePublicKeys
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKeys = keys.keys.associateWith { id ->
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                keyFactory.generatePublic(X509EncodedKeySpec(WireProtocol.decode(keys.getValue(id)))).also {
                    require(WireProtocol.isP256(it)) { "Release signing key must be P-256" }
                }
            }
            require(publicKeys.isNotEmpty())
            return BootstrapConfig(origin, username, publicKeys)
        }
    }
}

@Serializable
private data class ConfigDocument(val apiOrigin: String, val telegramBotUsername: String, val releasePublicKeys: Map<String, String>)
