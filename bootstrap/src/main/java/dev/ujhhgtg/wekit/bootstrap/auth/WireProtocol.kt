package dev.ujhhgtg.wekit.bootstrap.auth

import java.util.Base64
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object WireProtocol {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val alphabet = Regex("[A-Za-z0-9_-]*")
    private val curveOrder = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    fun randomBytes(size: Int = 32) = ByteArray(size).also(random::nextBytes)
    fun requestId() = hex(randomBytes(16))
    fun encode(value: ByteArray): String = encoder.encodeToString(value)
    fun decode(value: String, size: Int? = null): ByteArray {
        require(value.matches(alphabet) && value.length % 4 != 1) { "invalid Base64url" }
        return decoder.decode(value).also {
            require(encode(it) == value && (size == null || it.size == size)) { "invalid Base64url length" }
        }
    }
    fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sha256(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))
    fun isP256(key: java.security.PublicKey): Boolean = key is java.security.interfaces.ECPublicKey &&
        key.params.curve.field.fieldSize == 256 && key.params.order == curveOrder

    fun requestBytes(
        method: String, path: String, body: ByteArray, token: String,
        challengeId: String, serverNonce: String, requestId: String,
        processNonce: String, range: String = "", grantId: String = "",
    ): ByteArray {
        val lines = listOf("WEKIT-REQUEST-V1", method, path, sha256(body),
            sha256(decode(token, 32)), challengeId, serverNonce, requestId,
            processNonce, range, grantId)
        require(lines.none { '\r' in it || '\n' in it }) { "invalid signature field" }
        return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    }

    /** AndroidKeyStore returns DER; the wire format is strictly fixed-width P1363. */
    fun derToRaw(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 255) == der.size - 2)
        var cursor = 2
        fun integer(): ByteArray {
            require(cursor + 2 <= der.size && der[cursor++] == 2.toByte())
            val length = der[cursor++].toInt() and 255
            require(length in 1..33 && cursor + length <= der.size)
            var value = der.copyOfRange(cursor, cursor + length)
            cursor += length
            require(value[0].toInt() >= 0) { "negative ECDSA integer" }
            if (value.size > 1 && value[0] == 0.toByte()) {
                require(value[1].toInt() < 0) { "nonminimal ECDSA integer" }
                value = value.copyOfRange(1, value.size)
            }
            require(value.size <= 32)
            val number = BigInteger(1, value)
            require(number.signum() > 0 && number < curveOrder)
            return ByteArray(32).also { value.copyInto(it, 32 - value.size) }
        }
        val result = integer() + integer()
        require(cursor == der.size)
        return result
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun integer(offset: Int): ByteArray {
            val value = BigInteger(1, raw.copyOfRange(offset, offset + 32))
            require(value.signum() > 0 && value < curveOrder)
            val bytes = value.toByteArray()
            return byteArrayOf(2, bytes.size.toByte()) + bytes
        }
        val pair = integer(0) + integer(32)
        return byteArrayOf(0x30, pair.size.toByte()) + pair
    }
}
