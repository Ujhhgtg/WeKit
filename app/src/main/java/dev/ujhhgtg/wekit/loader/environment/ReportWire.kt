package dev.ujhhgtg.wekit.loader.environment

import java.io.ByteArrayOutputStream

/**
 * Bounded wire parsing for an untrusted native payload; unknown fields retain their exact bytes.
 */
object ReportWire {
    data class Field(
        val number: Int,
        val wire: Int,
        val start: Int,
        val data: Int,
        val end: Int,
        val value: Long,
    )

    fun fields(bytes: ByteArray): List<Field> {
        var position = 0

        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(position < bytes.size) { "truncated varint" }
                val b = bytes[position++].toInt() and 255
                require(i != 9 || b <= 1) { "varint overflow" }
                value = value or ((b and 127).toLong() shl i * 7)
                if (b < 128) return value
            }
            error("varint overflow")
        }
        val fields = ArrayList<Field>()
        while (position < bytes.size) {
            val start = position
            val tag = varint()
            require(tag ushr 3 in 1..536870911L) { "invalid field tag" }
            val wire = (tag and 7).toInt()
            var data = position
            var value = 0L
            when (wire) {
                0 -> {
                    value = varint()
                }

                1,
                5 -> {
                    position += if (wire == 1) 8 else 4
                }

                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= bytes.size - position) {
                        "invalid field length"
                    }
                    data = position
                    position += length.toInt()
                }

                else -> {
                    error("unsupported wire type $wire")
                }
            }
            require(position <= bytes.size) { "truncated field" }
            fields += Field((tag ushr 3).toInt(), wire, start, data, position, value)
        }
        return fields
    }

    fun bytes(message: ByteArray, number: Int): ByteArray {
        val field = fields(message).single { it.number == number }
        require(field.wire == 2)
        return message.copyOfRange(field.data, field.end)
    }

    fun number(message: ByteArray, number: Int): Long {
        val field = fields(message).single { it.number == number }
        require(field.wire == 0)
        return field.value
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and -128L != 0L) {
            write(v.toInt() and 127 or 128)
            v = v ushr 7
        }
        write(v.toInt())
    }

    fun encode(number: Int, value: Long): ByteArray =
        ByteArrayOutputStream()
            .apply {
                varint(number.toLong() shl 3)
                varint(value)
            }
            .toByteArray()

    fun encode(number: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .apply {
                varint(number.toLong() shl 3 or 2)
                varint(value.size.toLong())
                write(value)
            }
            .toByteArray()

    fun rewrite(message: ByteArray, edit: (Field) -> ByteArray?): ByteArray {
        val out = ByteArrayOutputStream(message.size)
        for (field in fields(message)) {
            val replacement = edit(field)
            if (replacement == null) {
                out.write(message, field.start, field.end - field.start)
            } else {
                out.write(replacement)
            }
        }
        return out.toByteArray()
    }

    fun replace(message: ByteArray, number: Int, value: ByteArray): ByteArray {
        require(fields(message).count { it.number == number } == 1)
        return rewrite(message) { if (it.number == number) value else null }
    }
}
