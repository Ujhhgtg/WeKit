package dev.ujhhgtg.wekit.loader.environment

import java.util.zip.CRC32

/** Native-derived codec for the two accessFlags/checksum pairs in each field-206 method. */
class NormsgMethodCodec(message: ByteArray) {
    private val seconds =
        ReportWire.number(message, 88).also { require(it in 0..UINT32_MAX) }.toInt()
    private val micros = ReportWire.number(message, 89).also { require(it in 0..999999) }.toInt()
    private val seed =
        (0..3).fold(seconds) { value, i ->
            if (value ushr i * 8 and 255 == 0) value or (128 shl i * 8) else value
        }

    data class Flags(val getter: Long?, val field: Long?)

    fun decode(method: ByteArray): Flags =
        Flags(
            read(method, 1, 2, GETTER_SALT),
            read(method, 3, 4, FIELD_SALT),
        )

    /**
     * Leave failed reads (-1), entrypoint details, status and unknown fields byte-for-byte intact.
     */
    fun restore(method: ByteArray, original: Flags): ByteArray {
        val current = decode(method)
        var changed = method
        original.getter?.let { value ->
            if (current.getter != -1L && current.getter != value) {
                changed = write(changed, 1, 2, value, GETTER_SALT)
            }
        }
        original.field?.let { value ->
            if (current.field != -1L && current.field != value) {
                changed = write(changed, 3, 4, value, FIELD_SALT)
            }
        }
        return changed
    }

    private fun read(method: ByteArray, number: Int, check: Int, salt: Int): Long {
        val value = transform(ReportWire.number(method, number), salt, decrypt = true)
        require(ReportWire.number(method, check) == checksum(value, salt)) {
            "normsg method flags checksum mismatch: $number/$check"
        }
        return value
    }

    private fun write(
        method: ByteArray,
        number: Int,
        check: Int,
        value: Long,
        salt: Int,
    ): ByteArray {
        // JNI jint is sign-extended into the native report's 64-bit carrier, not zero-extended.
        require(value == value.toInt().toLong()) {
            "method accessFlags is not a sign-extended jint"
        }
        val changed =
            ReportWire.replace(
                method,
                number,
                ReportWire.encode(number, transform(value, salt, decrypt = false)),
            )
        return ReportWire.replace(changed, check, ReportWire.encode(check, checksum(value, salt)))
    }

    private fun transform(value: Long, salt: Int, decrypt: Boolean): Long {
        var result = 0L
        for (i in 0..1) {
            val word = (value ushr i * 32).toInt()
            val mix = Integer.rotateRight(seed, i) xor Integer.rotateLeft(salt, i)
            val changed =
                if (decrypt) {
                    Integer.rotateRight(Integer.rotateRight(word, i + 1) xor mix, i + 1)
                } else {
                    Integer.rotateLeft(Integer.rotateLeft(word, i + 1) xor mix, i + 1)
                }
            result = result or (changed.toLong() and UINT32_MAX shl i * 32)
        }
        return result
    }

    private fun checksum(value: Long, salt: Int): Long {
        val selector = (value and 3).toInt()
        val prefix = Integer.rotateLeft(micros, selector) xor Integer.rotateRight(salt, selector)
        return CRC32()
            .apply {
                update((prefix.toLong() and UINT32_MAX).toString().toByteArray(Charsets.US_ASCII))
                update(ByteArray(8) { (value ushr it * 8).toByte() })
            }
            .value
    }

    companion object {
        private const val UINT32_MAX = 0xffff_ffffL
        private const val GETTER_SALT = 0xab2513b3.toInt()
        private const val FIELD_SALT = 0xd32513c1.toInt()
    }
}
