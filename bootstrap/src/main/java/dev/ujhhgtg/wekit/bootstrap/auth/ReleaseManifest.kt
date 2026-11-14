package dev.ujhhgtg.wekit.bootstrap.auth

import dev.ujhhgtg.wekit.bootstrap.BootstrapConfig
import dev.ujhhgtg.wekit.bootstrap.payload.PayloadDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.security.Signature
import java.security.interfaces.ECPublicKey

data class VerifiedRelease(val envelopeBytes: ByteArray, val descriptor: PayloadDescriptor, val businessVersion: String, val manifestSha256: String)

object ReleaseManifest {
    fun verify(envelopeBytes: ByteArray, config: BootstrapConfig): VerifiedRelease {
        val envelopeObject = StrictJson.objectFrom(envelopeBytes)
        listOf("keyId", "manifest", "signature").forEach(envelopeObject::string)
        val envelope = WireJson.format.decodeFromJsonElement<ReleaseEnvelope>(envelopeObject)
        val publicKey = config.releaseKeys[envelope.keyId] ?: error("Unknown release signing key")
        require(WireProtocol.isP256(publicKey))
        val manifestBytes = WireProtocol.decode(envelope.manifest)
        require(manifestBytes.size <= 32768)
        val rawSignature = WireProtocol.decode(envelope.signature, 64)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(publicKey)
        signature.update("WEKIT-RELEASE-V1\u0000".toByteArray(Charsets.UTF_8))
        signature.update(manifestBytes)
        check(signature.verify(WireProtocol.rawToDer(rawSignature))) { "Release signature verification failed" }
        val manifestObject = StrictJson.objectFrom(manifestBytes, 32768)
        // kotlinx.serialization accepts quoted numeric primitives; this wire format requires JSON numbers.
        listOf("payloadId", "businessVersion", "sourceCommit", "entryClass", "apkSha256", "cipherSha256", "createdAt")
            .forEach(manifestObject::string)
        manifestObject.integer("apkSize")
        manifestObject.integer("cipherSize")
        manifestObject.obj("encryption").let {
            it.string("algorithm")
            it.string("noncePrefix")
            it.integer("chunkSize")
            it.integer("chunkCount")
        }
        val json = WireJson.format.decodeFromJsonElement<ReleaseDocument>(manifestObject)
        val encryption = json.encryption
        require(encryption.algorithm == "AES-256-GCM")
        val payloadId = json.payloadId
        require(payloadId.matches(Regex("[a-z0-9_-]{1,64}")))
        val entry = json.entryClass
        require(entry.length <= 512 && entry.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")))
        require(encryption.chunkCount > 0 && encryption.chunkSize == PayloadDescriptor.CHUNK_SIZE)
        val descriptor = PayloadDescriptor(payloadId, entry, json.apkSha256, json.apkSize, json.cipherSha256,
            json.cipherSize, WireProtocol.decode(encryption.noncePrefix, 8), encryption.chunkCount)
        require(json.sourceCommit.matches(Regex("[0-9a-f]{40}")))
        require(json.createdAt.endsWith('Z'))
        java.time.Instant.parse(json.createdAt)
        return VerifiedRelease(envelopeBytes.copyOf(), descriptor, json.businessVersion, WireProtocol.sha256(manifestBytes))
    }

    fun verify(envelope: JsonObject, config: BootstrapConfig) = verify(WireJson.bytes(envelope), config)
}
