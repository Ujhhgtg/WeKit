package dev.ujhhgtg.wekit.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Minimal, read-only decoder for the unencrypted MMKV files written by the old WeKit facade.
 *
 * This deliberately does not load MMKV's native library and never opens a file for writing.
 * A read takes a stable snapshot (the CRC metadata must match) and applies the records in
 * order, so a later tombstone or replacement has the same meaning as MMKV's in-memory
 * dictionary.
 *
 * Layout, verified against the MMKV 2.4.2 sources (Core/MMKV_IO.cpp, Core/MiniPBCoder.cpp):
 * the data file is `[u32 actualSize][region]`; the region starts with MMKV's full-writeback
 * size placeholder (a varint) followed by tuples `[varint keyLen][key][varint valueLen][value]`,
 * where an empty value is a tombstone. The meta (.crc) file is `MMKVMetaInfo`: crc@0,
 * version@4, sequence@8, IV@12..27, actualSize@28 — the latter is authoritative only from
 * `MMKVVersionActualSize` (3) on, before that the data file header is. Bool values are one
 * raw byte, int32/int64 are varints (negative values sign-extend to the 10-byte varint64
 * form), floats are little-endian fixed32, string/bytes values carry an extra varint length
 * prefix inside the value ("data holder"), and string sets are a varint container size
 * followed by varint-length strings.
 */
object MmkvReadonlyReader {
    private const val MAX_FILE_SIZE = 64 * 1024 * 1024L
    private const val META_VERSION_OFFSET = 4
    private const val META_ACTUAL_SIZE_OFFSET = 28
    private const val DATA_HEADER_SIZE = 4
    private const val TYPE_SUFFIX = $$"$shadow$type"

    private const val TYPE_BOOL = 0x80 + 2
    private const val TYPE_INT = 0x80 + 4
    private const val TYPE_LONG = 0x80 + 6
    private const val TYPE_FLOAT = 0x80 + 7
    private const val TYPE_STRING = 0x80 + 31
    private const val TYPE_STRING_SET = 0x80 + 32
    private const val TYPE_BYTES = 0x80 + 33

    data class RawEntry(val key: String, val marker: Int?, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RawEntry

            if (marker != other.marker) return false
            if (key != other.key) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = marker ?: 0
            result = 31 * result + key.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /** Reads one MMKV file pair. Any invalid/truncated/changed snapshot throws. */
    fun read(file: File, crcFile: File): List<RawEntry> {
        require(file.isFile && crcFile.isFile) { "MMKV file pair is incomplete" }
        require(file.length() in 1..MAX_FILE_SIZE) { "MMKV file is missing or unexpectedly large" }
        require(crcFile.length() in 1..MAX_FILE_SIZE) { "MMKV metadata is missing or unexpectedly large" }

        val main = file.readBytes()
        val metadata = crcFile.readBytes()
        val version = metadata.readUInt32OrNull(META_VERSION_OFFSET)
        val actualSize = if (version != null && version >= 3 && metadata.size >= META_ACTUAL_SIZE_OFFSET + 4) {
            metadata.readUInt32(META_ACTUAL_SIZE_OFFSET).toInt()
        } else {
            main.readUInt32(0).toInt()
        }
        require(actualSize >= 0 && actualSize <= main.size - DATA_HEADER_SIZE) {
            "MMKV actual size is outside the data file"
        }

        val expectedCrc = metadata.readUInt32OrNull(0)
        if (expectedCrc != null && expectedCrc != 0L) {
            val crc = CRC32().apply { update(main, DATA_HEADER_SIZE, actualSize) }.value
            require(crc == expectedCrc) { "MMKV CRC mismatch" }
        }

        if (actualSize == 0) return emptyList()
        val cursor = Cursor(main, DATA_HEADER_SIZE, DATA_HEADER_SIZE + actualSize)
        // The first item is MMKV's full-writeback size holder and is not a key/value record.
        cursor.readVarint()
        val values = LinkedHashMap<String, ByteArray>()
        while (!cursor.atEnd) {
            val key = cursor.readBytes().toString(Charsets.UTF_8)
            val value = cursor.readBytes()
            if (value.isEmpty()) values.remove(key) else values[key] = value
        }

        val markerByKey = values
            .filterKeys { it.endsWith(TYPE_SUFFIX) }
            .mapKeys { it.key.removeSuffix(TYPE_SUFFIX) }
            .mapValues { decodeInt(it.value) }
        return values
            .asSequence()
            .filterNot { it.key.endsWith(TYPE_SUFFIX) }
            .map { (key, bytes) -> RawEntry(key, markerByKey[key], bytes) }
            .toList()
    }

    /** Decodes only the value types used by the legacy WeKit facade. */
    fun decode(entry: RawEntry): Any? = when (entry.marker) {
        TYPE_BOOL -> decodeBool(entry.bytes)
        TYPE_INT -> decodeInt(entry.bytes)
        TYPE_LONG -> decodeLong(entry.bytes)
        TYPE_FLOAT -> decodeFloat(entry.bytes)
        TYPE_STRING -> decodeHolder(entry.bytes).toString(Charsets.UTF_8)
        TYPE_STRING_SET -> decodeStringSet(entry.bytes)
        TYPE_BYTES -> decodeHolder(entry.bytes)
        else -> null
    }

    fun typeName(marker: Int?): String? = when (marker) {
        TYPE_BOOL -> "bool"
        TYPE_INT -> "int"
        TYPE_LONG -> "long"
        TYPE_FLOAT -> "float"
        TYPE_STRING -> "string"
        TYPE_STRING_SET -> "string_set"
        TYPE_BYTES -> "bytes"
        null -> null
        else -> "legacy:$marker"
    }

    /**
     * Strips the varint length prefix MMKV stores inside string/bytes values
     * (setDataForKey's data holder, unwrapped by readString/readData on MMKV's side).
     */
    private fun decodeHolder(bytes: ByteArray): ByteArray = Cursor(bytes, 0, bytes.size).readBytes()

    private fun decodeStringSet(bytes: ByteArray): Set<String> {
        val cursor = Cursor(bytes, 0, bytes.size)
        cursor.readVarint() // encoded payload length
        val result = LinkedHashSet<String>()
        while (!cursor.atEnd) result += cursor.readBytes().toString(Charsets.UTF_8)
        return result
    }

    private fun decodeBool(bytes: ByteArray): Boolean {
        require(bytes.isNotEmpty()) { "invalid MMKV bool" }
        return bytes[0] != 0.toByte()
    }

    private fun decodeInt(bytes: ByteArray): Int = decodeLong(bytes).toInt()

    private fun decodeFloat(bytes: ByteArray): Float {
        require(bytes.size >= 4) { "invalid MMKV float" }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun decodeLong(bytes: ByteArray): Long {
        var result = 0L
        var shift = 0
        for (byte in bytes) {
            result = result or (byte.toLong() and 0x7f shl shift)
            if (byte.toInt() and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "invalid MMKV varint" }
        }
        error("truncated MMKV varint")
    }

    private class Cursor(
        private val bytes: ByteArray,
        private var position: Int,
        private val end: Int,
    ) {
        val atEnd: Boolean get() = position == end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (position < end) {
                val value = bytes[position++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "invalid MMKV varint" }
            }
            error("truncated MMKV varint")
        }

        fun readBytes(): ByteArray {
            val size = readVarint()
            require(size in 0..(end - position).toLong()) { "invalid MMKV record length" }
            val start = position
            position += size.toInt()
            return bytes.copyOfRange(start, position)
        }
    }

    private fun ByteArray.readUInt32(offset: Int): Long {
        require(offset >= 0 && offset + 4 <= size) { "MMKV metadata is truncated" }
        return this[offset].toLong() and 0xff or
                (this[offset + 1].toLong() and 0xff shl 8) or
                (this[offset + 2].toLong() and 0xff shl 16) or
                (this[offset + 3].toLong() and 0xff shl 24)
    }

    private fun ByteArray.readUInt32OrNull(offset: Int): Long? =
        if (offset >= 0 && offset + 4 <= size) readUInt32(offset) else null
}
