package dev.ujhhgtg.wekit.preferences

import dev.ujhhgtg.wekit.data.MmkvReadonlyReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Builds MMKV file pairs byte-for-byte the way MMKV 2.4.2 writes them (CodedOutputData varints,
 * setDataForKey's data holder on string/bytes values, the full-writeback size placeholder, the
 * MMKVMetaInfo crc file) and checks the read-only decoder against them.
 */
class MmkvReadonlyReaderTest {
    @TempDir
    lateinit var tempDir: File

    private class TupleWriter {
        val region = ByteArrayOutputStream()

        // The old facade wrote a "$shadow$type" marker after every value, mirroring MmkvPrefsImpl
        private fun markerOf(key: String, marker: Int) {
            val payload = ByteArrayOutputStream()
            writeVarint32(payload, marker)
            tuple("$key\$shadow\$type", payload.toByteArray())
        }

        fun string(key: String, value: String) {
            // set(string) stores a data holder: an extra varint length inside the value
            val encoded = value.toByteArray(Charsets.UTF_8)
            val holder = ByteArrayOutputStream()
            writeVarint32(holder, encoded.size)
            holder.writeBytes(encoded)
            tuple(key, holder.toByteArray())
            markerOf(key, 0x80 + 31)
        }

        fun bytes(key: String, value: ByteArray) {
            val holder = ByteArrayOutputStream()
            writeVarint32(holder, value.size)
            holder.writeBytes(value)
            tuple(key, holder.toByteArray())
            markerOf(key, 0x80 + 33)
        }

        fun int(key: String, value: Int) {
            val payload = ByteArrayOutputStream()
            writeVarint(payload, value.toLong()) // writeInt32: negatives sign-extend to varint64
            tuple(key, payload.toByteArray())
            markerOf(key, 0x80 + 4)
        }

        fun long(key: String, value: Long) {
            val payload = ByteArrayOutputStream()
            writeVarint(payload, value) // writeInt64 is always a varint64
            tuple(key, payload.toByteArray())
            markerOf(key, 0x80 + 6)
        }

        fun bool(key: String, value: Boolean) {
            tuple(key, byteArrayOf((if (value) 1 else 0).toByte()))
            markerOf(key, 0x80 + 2)
        }

        fun float(key: String, value: Float) {
            val bits = java.lang.Float.floatToIntBits(value)
            tuple(key, byteArrayOf(
                (bits and 0xFF).toByte(), (bits ushr 8 and 0xFF).toByte(),
                (bits ushr 16 and 0xFF).toByte(), (bits ushr 24 and 0xFF).toByte(),
            ))
            markerOf(key, 0x80 + 7)
        }

        fun stringSet(key: String, values: List<String>) {
            val items = ByteArrayOutputStream()
            values.forEach { item ->
                val encoded = item.toByteArray(Charsets.UTF_8)
                writeVarint32(items, encoded.size)
                items.writeBytes(encoded)
            }
            val payload = ByteArrayOutputStream()
            writeVarint32(payload, items.size()) // writeUInt32 container size is a varint too
            payload.writeBytes(items.toByteArray())
            tuple(key, payload.toByteArray())
            markerOf(key, 0x80 + 32)
        }

        fun marker(key: String, marker: Int) {
            val payload = ByteArrayOutputStream()
            writeVarint32(payload, marker)
            tuple("$key\$shadow\$type", payload.toByteArray())
        }

        fun tombstone(key: String) = tuple(key, ByteArray(0))

        fun tuple(key: String, value: ByteArray) {
            prefixed(key.toByteArray(Charsets.UTF_8))
            prefixed(value)
        }

        private fun prefixed(bytes: ByteArray) {
            writeVarint32(region, bytes.size)
            region.writeBytes(bytes)
        }

        private fun writeVarint32(output: ByteArrayOutputStream, value: Int) {
            require(value >= 0) { "test writer only emits non-negative varint32" }
            var v = value
            while (v and 0x7F.inv() != 0) {
                output.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            output.write(v)
        }

        private fun writeVarint(output: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v ushr 7 != 0L) {
                output.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            output.write((v and 0x7F).toInt())
        }
    }

    /** Data file `[u32 actualSize][placeholder varint][tuples]` plus the MMKVMetaInfo crc file. */
    private fun writeFiles(tuples: ByteArray, metaVersion: Long = 3): Pair<File, File> {
        val placeholder = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x02) // varint32 of 0x200000, always 4 bytes
        val region = placeholder + tuples
        val actualSize = region.size
        val crc = CRC32().apply { update(region, 0, region.size) }.value

        val data = ByteArrayOutputStream()
        writeLe32(data, actualSize.toLong())
        data.writeBytes(region)

        val meta = ByteArrayOutputStream()
        writeLe32(meta, crc)
        writeLe32(meta, metaVersion)
        writeLe32(meta, 0) // sequence
        repeat(16) { meta.write(0) } // AES IV, zeroes for the unencrypted case
        writeLe32(meta, actualSize.toLong())

        val dataFile = File(tempDir, "wekit_prefs").apply { writeBytes(data.toByteArray()) }
        val crcFile = File(tempDir, "wekit_prefs.crc").apply { writeBytes(meta.toByteArray()) }
        return dataFile to crcFile
    }

    private fun writeLe32(output: ByteArrayOutputStream, value: Long) {
        output.write((value and 0xFF).toInt())
        output.write((value ushr 8 and 0xFF).toInt())
        output.write((value ushr 16 and 0xFF).toInt())
        output.write((value ushr 24 and 0xFF).toInt())
    }

    private fun read(tuples: ByteArray, metaVersion: Long = 3) =
        writeFiles(tuples, metaVersion).let { (data, crc) -> MmkvReadonlyReader.read(data, crc) }

    @Test
    fun decodesAllLegacyValueTypes() {
        val writer = TupleWriter()
        writer.string("string_key", "普通文本 mixed ✨")
        writer.string("empty_string", "")
        writer.int("int_small", 5)
        writer.int("int_large", 300)
        writer.int("int_negative", -5)
        writer.long("long_max", Long.MAX_VALUE)
        writer.long("long_min", Long.MIN_VALUE)
        writer.bool("bool_true", true)
        writer.bool("bool_false", false)
        writer.float("float_value", -2.25f)
        writer.bytes("bytes_key", byteArrayOf(0, 1, 2, 0xFF.toByte(), 3))
        writer.stringSet("set_key", listOf("alpha", "beta", "中文"))

        val entries = read(writer.region.toByteArray()).associateBy { it.key }

        assertEquals("普通文本 mixed ✨", MmkvReadonlyReader.decode(entries.getValue("string_key")))
        assertEquals("", MmkvReadonlyReader.decode(entries.getValue("empty_string")))
        assertEquals(5, MmkvReadonlyReader.decode(entries.getValue("int_small")))
        assertEquals(300, MmkvReadonlyReader.decode(entries.getValue("int_large")))
        assertEquals(-5, MmkvReadonlyReader.decode(entries.getValue("int_negative")))
        assertEquals(Long.MAX_VALUE, MmkvReadonlyReader.decode(entries.getValue("long_max")))
        assertEquals(Long.MIN_VALUE, MmkvReadonlyReader.decode(entries.getValue("long_min")))
        assertEquals(true, MmkvReadonlyReader.decode(entries.getValue("bool_true")))
        assertEquals(false, MmkvReadonlyReader.decode(entries.getValue("bool_false")))
        assertEquals(-2.25f, MmkvReadonlyReader.decode(entries.getValue("float_value")))
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 0xFF.toByte(), 3),
            MmkvReadonlyReader.decode(entries.getValue("bytes_key")) as ByteArray,
        )
        assertEquals(setOf("alpha", "beta", "中文"), MmkvReadonlyReader.decode(entries.getValue("set_key")))
        assertEquals(0x80 + 31, entries.getValue("string_key").marker)
        assertEquals(0x80 + 4, entries.getValue("int_small").marker)
        assertTrue(entries.keys.none { it.endsWith("\$shadow\$type") })
    }

    @Test
    fun appliesReplacementsAndTombstonesInOrder() {
        val writer = TupleWriter()
        writer.string("changed", "old")
        writer.string("gone", "value")
        writer.string("changed", "new")
        writer.tombstone("gone")
        writer.string("gone", "returned")

        val entries = read(writer.region.toByteArray()).associateBy { it.key }

        assertEquals(2, entries.size)
        assertEquals("new", MmkvReadonlyReader.decode(entries.getValue("changed")))
        assertEquals("returned", MmkvReadonlyReader.decode(entries.getValue("gone")))
    }

    @Test
    fun readsActualSizeFromDataHeaderWhenMetaVersionBelow3() {
        val writer = TupleWriter()
        writer.string("key", "value")

        val (data, crc) = writeFiles(writer.region.toByteArray(), metaVersion = 1)
        // Old-style files carry no authoritative actualSize in the meta file
        val raw = crc.readBytes()
        for (offset in 28..31) raw[offset] = 0
        crc.writeBytes(raw)

        val entries = MmkvReadonlyReader.read(data, crc)
        assertEquals("value", MmkvReadonlyReader.decode(entries.single()))
    }

    @Test
    fun readsActualSizeFromMetaWhenVersion3OrLater() {
        val writer = TupleWriter()
        writer.string("key", "value")

        // A crash between the region write and the data header update leaves header and meta
        // diverged; MMKV trusts the meta file from version 3 on, so the reader must too.
        val (data, crc) = writeFiles(writer.region.toByteArray(), metaVersion = 3)
        val raw = data.readBytes()
        raw[0] = 0
        raw[1] = 0
        data.writeBytes(raw)

        val entries = MmkvReadonlyReader.read(data, crc)
        assertEquals("value", MmkvReadonlyReader.decode(entries.single()))
    }

    @Test
    fun rejectsCrcMismatch() {
        val writer = TupleWriter()
        writer.string("key", "value")

        val (data, crc) = writeFiles(writer.region.toByteArray())
        val raw = data.readBytes()
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        data.writeBytes(raw)

        assertThrows<IllegalArgumentException> { MmkvReadonlyReader.read(data, crc) }
    }

    @Test
    fun rejectsActualSizeOutsideTheDataFile() {
        val writer = TupleWriter()
        writer.string("key", "value")

        val (data, crc) = writeFiles(writer.region.toByteArray())
        val raw = crc.readBytes()
        raw[28] = 0x40 // meta actualSize = 0x40000000, far beyond the file
        crc.writeBytes(raw)

        assertThrows<IllegalArgumentException> { MmkvReadonlyReader.read(data, crc) }
    }

    @Test
    fun rejectsIncompleteFilePair() {
        val writer = TupleWriter()
        writer.string("key", "value")

        val (data, crc) = writeFiles(writer.region.toByteArray())
        assertTrue(crc.delete())
        assertThrows<IllegalArgumentException> { MmkvReadonlyReader.read(data, crc) }
    }

    @Test
    fun rejectsMalformedHolderLength() {
        val writer = TupleWriter()
        // value claims a holder length longer than what follows
        writer.tuple("key", byteArrayOf(0x10, 0x61))
        writer.marker("key", 0x80 + 33)

        val entries = read(writer.region.toByteArray())
        assertThrows<IllegalArgumentException> { MmkvReadonlyReader.decode(entries.single()) }
    }

    @Test
    fun preservesUnknownTypeMarkersAsRawEntries() {
        val writer = TupleWriter()
        writer.tuple("mystery", byteArrayOf(1, 2, 3))
        writer.marker("mystery", 0x80 + 41)

        val entries = read(writer.region.toByteArray())
        val entry = entries.single()
        assertNull(MmkvReadonlyReader.decode(entry))
        assertEquals("legacy:${0x80 + 41}", MmkvReadonlyReader.typeName(entry.marker))
    }
}
