package dev.ujhhgtg.wekit.loader.environment

import java.util.zip.CRC32

/** Native-derived codec for the root-manager bitmap (231) and its seeded CRC32 (232). */
class NormsgRootCodec(message: ByteArray) {
    private val seconds = ReportWire.number(message, 88).also { require(it in 0..UINT32_MAX) }.toInt()
    private val micros = ReportWire.number(message, 89).also { require(it in 0..999999) }.toInt()
    private val seed = (0..3).fold(seconds) { value, i ->
        if (value ushr i * 8 and 255 == 0) value or (128 shl (i * 8)) else value
    }

    fun decode(message: ByteArray): Long {
        val value = transform(ReportWire.number(message, 231), decrypt = true)
        require(ReportWire.number(message, 232) == checksum(value)) { "normsg root bitmap checksum mismatch" }
        return value
    }

    fun encode(value: Long, template: ByteArray): ByteArray {
        val updated = ReportWire.replace(template, 231, ReportWire.encode(231, transform(value, decrypt = false)))
        return ReportWire.replace(updated, 232, ReportWire.encode(232, checksum(value)))
    }

    private fun checksum(value: Long): Long {
        val selector = (value and 3).toInt()
        val prefix = Integer.rotateLeft(micros, selector) xor Integer.rotateRight(SALT, selector)
        return CRC32().apply {
            update((prefix.toLong() and UINT32_MAX).toString().toByteArray(Charsets.US_ASCII))
            update(ByteArray(8) { (value ushr it * 8).toByte() })
        }.value
    }

    private fun transform(value: Long, decrypt: Boolean): Long {
        var out = 0L
        for (i in 0..1) {
            val word = (value ushr i * 32).toInt()
            val mix = Integer.rotateRight(seed, i) xor Integer.rotateLeft(SALT, i)
            val changed = if (decrypt) {
                Integer.rotateRight(Integer.rotateRight(word, i + 1) xor mix, i + 1)
            } else {
                Integer.rotateLeft(Integer.rotateLeft(word, i + 1) xor mix, i + 1)
            }
            out = out or (changed.toLong() and UINT32_MAX shl i * 32)
        }
        return out
    }

    companion object {
        private const val UINT32_MAX = 0xffff_ffffL
        private const val SALT = 0xab5c1724.toInt()
    }
}
