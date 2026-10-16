package dev.ujhhgtg.wekit.bootstrap.auth

import dev.ujhhgtg.wekit.bootstrap.BootstrapConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** All keys/signatures in this file are public deterministic interoperability fixtures. */
class ReleaseManifestTest {
    private val envelopeText = """{"keyId":"test-release-key","manifest":"eyJwYXlsb2FkSWQiOiJ0ZXN0LXBheWxvYWQtdjEiLCJidXNpbmVzc1ZlcnNpb24iOiJ0ZXN0LTEiLCJzb3VyY2VDb21taXQiOiIwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIiwiZW50cnlDbGFzcyI6InRlc3QuUGF5bG9hZEVudHJ5IiwiYXBrU2hhMjU2IjoiNjRjYjgyYmMzODI5NTU1MDU5ZTdlN2QwY2JjMDcwYmIzNzBjYTNjZWZjNjcxZTkxOWE1MTA4NjI5NGY4NDJmZiIsImFwa1NpemUiOjMxLCJjaXBoZXJTaGEyNTYiOiIwYmJhOTAzNmI3YTVhNGM0ODczZDE4NDM0ZDg3MTVjY2E1YWFlZGVkZTczYWY3MWNmYjBlMjc2ZjIyM2MwNzM2IiwiY2lwaGVyU2l6ZSI6NDcsImVuY3J5cHRpb24iOnsiYWxnb3JpdGhtIjoiQUVTLTI1Ni1HQ00iLCJjaHVua1NpemUiOjEwNDg1NzYsIm5vbmNlUHJlZml4Ijoib0tHaW82U2xwcWMiLCJjaHVua0NvdW50IjoxfSwiY3JlYXRlZEF0IjoiMjAyNi0xMC0xMVQwMDowMDowMFoifQ","signature":"yd2EkF-y72TutHrSnJkY9UALp_qTBIu3nZerM-GUlU3QHTPoCL4C7i48aa3x8Rm0RWAq2ghiZQQJp1nTUAEh-g"}"""
    private val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec("3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5".unhex())) as ECPublicKey
    private val config = BootstrapConfig("https://example.invalid", "test_wekit_bot", mapOf("test-release-key" to publicKey), emptySet())
    private fun envelope() = WireJson.format.parseToJsonElement(envelopeText).jsonObject

    @Test
    fun `verifies frozen Python Node workerd release envelope using original manifest bytes`() {
        val release = ReleaseManifest.verify(envelopeText.toByteArray(), config)
        assertEquals("test-payload-v1", release.descriptor.payloadId)
        assertEquals("64cb82bc3829555059e7e7d0cbc070bb370ca3cefc671e919a51086294f842ff", release.descriptor.apkSha256)
        assertEquals(31L, release.descriptor.apkSize)
    }

    @Test
    fun `rejects modified signed bytes unknown signing key and DER encoded wire signatures`() {
        val bytes = Base64.getUrlDecoder().decode(envelope().string("manifest"))
        bytes[10] = (bytes[10].toInt() xor 1).toByte()
        val altered = envelope().with("manifest", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        assertThrows(IllegalStateException::class.java) { ReleaseManifest.verify(altered, config) }
        assertThrows(IllegalStateException::class.java) { ReleaseManifest.verify(envelope().with("keyId", "unknown-key"), config) }
        val der = "3046022100c9dd84905fb2ef64eeb47ad29c9918f5400ba7fa93048bb79d97ab33e194954d022100d01d33e808be02ee2e3c69adf1f119b445602ada0862650409a759d3500121fa".unhex()
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifest.verify(envelope().with("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(der)), config)
        }
    }

    @Test
    fun `rejects duplicate manifest keys even when signed by the trusted test key`() {
        val original = String(Base64.getUrlDecoder().decode(envelope().string("manifest")), Charsets.UTF_8)
        val duplicate = original.dropLast(1) + ",\"\\u0070ayloadId\":\"different\"}"
        assertThrows(IllegalArgumentException::class.java) { ReleaseManifest.verify(sign(duplicate), config) }
    }

    @Test
    fun `rejects inconsistent signed size chunk count and nonintegral numeric encodings`() {
        val original = String(Base64.getUrlDecoder().decode(envelope().string("manifest")), Charsets.UTF_8)
        for (malformed in listOf(
            original.replace("\"cipherSize\":47", "\"cipherSize\":48"),
            original.replace("\"chunkCount\":1", "\"chunkCount\":2"),
            original.replace("\"apkSize\":31", "\"apkSize\":31.0"),
            original.replace("\"apkSize\":31", "\"apkSize\":\"31\""),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseManifest.verify(sign(malformed), config) }
        }
    }

    @Test
    fun `trusted signatures do not make bare primitives valid string fields`() {
        val original = String(Base64.getUrlDecoder().decode(envelope().string("manifest")), Charsets.UTF_8)
        for (literal in listOf("123", "true", "null")) {
            val malformed = original.replace("\"businessVersion\":\"test-1\"", "\"businessVersion\":$literal")
            assertThrows(IllegalArgumentException::class.java) { ReleaseManifest.verify(sign(malformed), config) }
        }
        val numericKey = JsonObject(envelope() + ("keyId" to JsonPrimitive(123)))
        val numericAliasConfig = config.copy(releaseKeys = mapOf("123" to publicKey))
        assertThrows(IllegalArgumentException::class.java) { ReleaseManifest.verify(numericKey, numericAliasConfig) }
    }

    @Test
    fun `release signing domain cannot be replaced with an ordinary ECDSA message`() {
        val bytes = Base64.getUrlDecoder().decode(envelope().string("manifest"))
        val signer = Signature.getInstance("SHA256withECDSA")
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.ONE, publicKey.params))
        signer.initSign(privateKey); signer.update(bytes)
        val raw = WireProtocol.derToRaw(signer.sign())
        assertThrows(IllegalStateException::class.java) {
            ReleaseManifest.verify(envelope().with("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(raw)), config)
        }
    }

    private fun sign(manifest: String): JsonObject {
        val signer = Signature.getInstance("SHA256withECDSA")
        val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger.ONE, publicKey.params))
        signer.initSign(key)
        signer.update("WEKIT-RELEASE-V1\u0000".toByteArray())
        signer.update(manifest.toByteArray())
        return buildJsonObject {
            put("keyId", JsonPrimitive("test-release-key"))
            put("manifest", JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(manifest.toByteArray())))
            put("signature", JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(WireProtocol.derToRaw(signer.sign()))))
        }
    }
    private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
