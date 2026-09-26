package dev.ujhhgtg.wekit.utils.serialization

import java.io.ByteArrayOutputStream

/**
 * Schema-less access to the top-level fields of a protobuf message.
 *
 * Used to read or rewrite single fields of host messages while keeping every other byte (including
 * fields WeKit doesn't model) intact, which a decode → encode round-trip through a partial model
 * would drop. Like protobuf parsers, reads return the last occurrence of a field.
 */
object ProtoWire {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    private class Field(val number: Int, val wireType: Int, val start: Int, val valueStart: Int, val end: Int)

    private fun readVarint(bytes: ByteArray, pos: IntArray): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos[0]++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun fields(bytes: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        val pos = intArrayOf(0)
        while (pos[0] < bytes.size) {
            val start = pos[0]
            val tag = readVarint(bytes, pos)
            val wireType = (tag and 7).toInt()
            val valueStart = pos[0]
            when (wireType) {
                WIRE_VARINT -> readVarint(bytes, pos)
                WIRE_FIXED64 -> pos[0] += 8
                WIRE_LEN -> {
                    val length = readVarint(bytes, pos).toInt()
                    pos[0] += length
                }
                WIRE_FIXED32 -> pos[0] += 4
                else -> error("unsupported protobuf wire type $wireType at offset $start")
            }
            require(pos[0] <= bytes.size) { "truncated protobuf field at offset $start" }
            fields += Field((tag ushr 3).toInt(), wireType, start, valueStart, pos[0])
        }
        return fields
    }

    fun varint(bytes: ByteArray, number: Int): Long? {
        val field = fields(bytes).lastOrNull { it.number == number && it.wireType == WIRE_VARINT } ?: return null
        return readVarint(bytes, intArrayOf(field.valueStart))
    }

    fun string(bytes: ByteArray, number: Int): String? {
        val field = fields(bytes).lastOrNull { it.number == number && it.wireType == WIRE_LEN } ?: return null
        val pos = intArrayOf(field.valueStart)
        val length = readVarint(bytes, pos).toInt()
        return String(bytes, pos[0], length, Charsets.UTF_8)
    }

    /**
     * Returns a copy of [bytes] whose field [number] is the string [value]: the first occurrence is
     * replaced in place and later ones removed, or the field is appended when absent.
     */
    fun withString(bytes: ByteArray, number: Int, value: String): ByteArray {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        val encoded = ByteArrayOutputStream().apply {
            writeVarint((number.toLong() shl 3) or WIRE_LEN.toLong())
            writeVarint(valueBytes.size.toLong())
            write(valueBytes)
        }.toByteArray()

        val matches = fields(bytes).filter { it.number == number }
        if (matches.isEmpty()) return bytes + encoded

        val out = ByteArrayOutputStream(bytes.size + encoded.size)
        var cursor = 0
        matches.forEachIndexed { index, field ->
            out.write(bytes, cursor, field.start - cursor)
            if (index == 0) out.write(encoded)
            cursor = field.end
        }
        out.write(bytes, cursor, bytes.size - cursor)
        return out.toByteArray()
    }
}
