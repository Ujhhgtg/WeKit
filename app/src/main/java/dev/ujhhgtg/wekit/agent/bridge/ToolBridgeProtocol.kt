package dev.ujhhgtg.wekit.agent.bridge

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Small, dependency-free framing shared by Android and the native/portable clients. */
object ToolBridgeProtocol {
    const val VERSION = "WBT/1"
    const val TOKEN_BYTES = 32
    const val TOKEN_LENGTH = TOKEN_BYTES * 2
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    data class Frame(val token: String, val payload: String)

    fun encode(token: String, payload: String): ByteArray {
        validateToken(token)
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "payload too large" }
        return "$VERSION $token ${bytes.size}\n".toByteArray(StandardCharsets.US_ASCII) + bytes
    }

    fun read(input: InputStream): Frame {
        val buffered = java.io.BufferedInputStream(input)
        val line = readHeaderLine(buffered)
        val fields = line.split(' ')
        require(fields.size == 3 && fields[0] == VERSION) { "malformed bridge header" }
        val token = fields[1]
        validateToken(token)
        val length = requireNotNull(fields[2].toIntOrNull()) { "invalid payload length" }
        require(length in 0..MAX_PAYLOAD_BYTES) { "payload length out of bounds" }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = buffered.read(payload, offset, length - offset)
            require(count >= 0) { "truncated bridge payload" }
            offset += count
        }
        return Frame(token, payload.toString(StandardCharsets.UTF_8))
    }

    fun write(output: OutputStream, token: String, payload: String) {
        output.write(encode(token, payload))
        output.flush()
    }

    fun validateToken(token: String) {
        require(token.length == TOKEN_LENGTH && token.all { it in "0123456789abcdefABCDEF" }) {
            "invalid bridge token"
        }
    }

    private fun readHeaderLine(input: InputStream): String {
        val bytes = ByteArray(128)
        var size = 0
        while (true) {
            val value = input.read()
            require(value >= 0) { "truncated bridge header" }
            require(value < 128 || value == '\n'.code) { "bridge header too long" }
            if (value == '\n'.code) return bytes.copyOf(size).toString(StandardCharsets.US_ASCII)
            require(size < bytes.size) { "bridge header too long" }
            bytes[size++] = value.toByte()
        }
    }
}
