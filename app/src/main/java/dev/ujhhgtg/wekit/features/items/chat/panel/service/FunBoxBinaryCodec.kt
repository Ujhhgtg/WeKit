package dev.ujhhgtg.wekit.features.items.chat.panel.service

import dev.ujhhgtg.wekit.utils.polyfills.readBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class FunBoxBinaryWriter(
    private val random: SecureRandom = SecureRandom(),
) {
    private val output = ByteArrayOutputStream()
    private val key = ByteArray(32).also(random::nextBytes)
    private var keyIndex = 0

    fun string(value: String?) = bytes(value.orEmpty().toByteArray())

    fun bytes(value: ByteArray?) {
        val data = value ?: ByteArray(0)
        int(data.size)
        val transformed = ByteArray(data.size) { index ->
            (data[index].toInt() xor (index + 0x80) % 0xff).toByte()
        }
        output.write(xor(transformed))
    }

    fun int(value: Int) {
        output.write(xor(ByteArray(4) { index -> (value ushr index * 8).toByte() }))
    }

    fun long(value: Long) {
        output.write(xor(ByteArray(8) { index -> (value ushr index * 8).toByte() }))
    }

    fun bool(value: Boolean) = output.write(if (value) 1 else 0)

    fun strings(values: List<String>) {
        int(values.size)
        values.forEach(::string)
    }

    fun <T> objects(values: List<T>, encode: FunBoxBinaryWriter.(T) -> Unit) {
        int(values.size)
        values.forEach { value -> bytes(FunBoxBinaryWriter(random).apply { encode(value) }.build()) }
    }

    fun build(): ByteArray {
        val encodedKey = ByteArray(key.size) { index ->
            (key[index].toInt() xor (index + 0x80) % 0xff).toByte()
        }
        return encodedKey + output.toByteArray()
    }

    private fun xor(value: ByteArray) = ByteArray(value.size) { index ->
        val result = (value[index].toInt() xor key[keyIndex].toInt()).toByte()
        keyIndex = (keyIndex + 1) % key.size
        result
    }
}

class FunBoxBinaryReader(data: ByteArray) {
    private val input = ByteArrayInputStream(data)
    private val key = ByteArray(32) { index ->
        (readRawByte().toInt() xor (index + 0x80) % 0xff).toByte()
    }
    private var keyIndex = 0

    fun string(): String = String(bytes())

    fun bytes(): ByteArray {
        val length = int()
        require(length in 0..MAX_FIELD_BYTES) { "无效字段长度: $length" }
        val data = readXor(length)
        if (data.all { it == 0.toByte() }) return ByteArray(length)
        return ByteArray(length) { index ->
            (data[index].toInt() xor (index + 0x80) % 0xff).toByte()
        }
    }

    fun int(): Int {
        val data = readXor(4)
        return data.indices.fold(0) { value, index -> value or (data[index].toInt() and 0xff shl index * 8) }
    }

    fun long(): Long {
        val data = readXor(8)
        return data.indices.fold(0L) { value, index -> value or (data[index].toLong() and 0xffL shl index * 8) }
    }

    fun bool(): Boolean = readRawByte().toInt() == 1

    fun strings(): List<String> = List(checkedCount()) { string() }

    fun <T> objects(decode: (FunBoxBinaryReader) -> T): List<T> =
        List(checkedCount()) { decode(FunBoxBinaryReader(bytes())) }

    private fun checkedCount(): Int = int().also { require(it in 0..MAX_LIST_ITEMS) { "无效列表数量: $it" } }

    private fun readXor(length: Int): ByteArray {
        val raw = input.readBytes(length)
        require(raw.size == length) { "响应数据不完整" }
        return ByteArray(length) { index ->
            val result = (raw[index].toInt() xor key[keyIndex].toInt()).toByte()
            keyIndex = (keyIndex + 1) % key.size
            result
        }
    }

    private fun readRawByte(): Byte = input.read().also { require(it >= 0) { "响应数据不完整" } }.toByte()

    companion object {
        private const val MAX_FIELD_BYTES = 64 * 1024 * 1024
        private const val MAX_LIST_ITEMS = 100_000
    }
}
