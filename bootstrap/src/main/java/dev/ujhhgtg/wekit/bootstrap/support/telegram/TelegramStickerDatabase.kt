package dev.ujhhgtg.wekit.bootstrap.support.telegram

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile

@Serializable
data class TelegramInstalledStickerSet(
    val name: String,
    val title: String,
)

object TelegramStickerDatabase {
    private const val MSG_STICKER_SET = 0x6e153f16
    private const val MSG_STICKER_SET_LAYER = 0xb60a24a6.toInt()
    private const val STICKER_SET_CURRENT = 0x2dd14edc
    private val installedBlobIds = intArrayOf(1, 2, 5, 6)

    fun readInstalledSets(path: Path): Result<List<TelegramInstalledStickerSet>> = runCatching {
        require(path.isRegularFile()) { "Telegram 数据库文件不可读" }
        val parsed = linkedMapOf<String, TelegramInstalledStickerSet>()
        val cachedNames = linkedSetOf<String>()
        val database = SQLiteDatabase.openDatabase(
            path.absolutePathString(),
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        database.use { db ->
            if (tableExists(db, "stickers_v2")) {
                installedBlobIds.forEach { id ->
                    db.rawQuery("SELECT data FROM stickers_v2 WHERE id = ?", arrayOf(id.toString())).use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            parseInstalledStickerBlob(cursor.getBlob(0)).forEach { set ->
                                parsed.putIfAbsent(set.name.lowercase(), set)
                            }
                        }
                    }
                }
            }
            if (tableExists(db, "stickersets2")) {
                db.rawQuery(
                    "SELECT DISTINCT short_name FROM stickersets2 WHERE short_name != '' ORDER BY short_name",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)?.let(cachedNames::add)
                    }
                }
            }
        }
        cachedNames.forEach { name ->
            parsed.putIfAbsent(name.lowercase(), TelegramInstalledStickerSet(name = name, title = name))
        }
        parsed.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun parseInstalledStickerBlob(blob: ByteArray): List<TelegramInstalledStickerSet> {
        if (blob.size < 4) return emptyList()
        val count = readInt32LittleEndian(blob, 0).coerceAtLeast(0)
        if (count == 0) return emptyList()
        val results = mutableListOf<TelegramInstalledStickerSet>()
        val seenNames = hashSetOf<String>()
        var position = 4
        while (position <= blob.size - 8 && results.size < count) {
            val constructor = readInt32LittleEndian(blob, position)
            if (constructor == MSG_STICKER_SET || constructor == MSG_STICKER_SET_LAYER) {
                tryParseSet(blob, position)?.takeIf { seenNames.add(it.name) }?.let(results::add)
            }
            position += 4
        }
        return results
    }

    private fun tryParseSet(blob: ByteArray, position: Int): TelegramInstalledStickerSet? = runCatching {
        TlBinaryReader(blob, position).run {
            val messageConstructor = readInt32()
            require(messageConstructor == MSG_STICKER_SET || messageConstructor == MSG_STICKER_SET_LAYER)
            require(readInt32() == STICKER_SET_CURRENT)
            val flags = readInt32()
            if (flags and 1 != 0) readInt32()
            readInt64()
            readInt64()
            val title = readString().trim()
            val name = readString().trim()
            require(title.isNotBlank() && name.isNotBlank())
            TelegramInstalledStickerSet(name = name, title = title)
        }
    }.getOrNull()

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private class TlBinaryReader(bytes: ByteArray, offset: Int) {
        private val input = DataInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset))

        fun readInt32(): Int = Integer.reverseBytes(input.readInt())

        fun readInt64(): Long = java.lang.Long.reverseBytes(input.readLong())

        fun readString(): String = readTlBytes().toString(Charsets.UTF_8)

        private fun readTlBytes(): ByteArray {
            val first = input.read()
            if (first < 0) throw EOFException()
            val (length, headerSize) = if (first < 254) {
                first to 1
            } else {
                val b1 = input.readUnsignedByte()
                val b2 = input.readUnsignedByte()
                val b3 = input.readUnsignedByte()
                (b1 or (b2 shl 8) or (b3 shl 16)) to 4
            }
            require(length in 0..MAX_TL_STRING_BYTES)
            val data = ByteArray(length)
            input.readFully(data)
            val padding = (4 - ((length + headerSize) and 3)) and 3
            if (padding > 0) input.skipBytes(padding)
            return data
        }
    }

    private const val MAX_TL_STRING_BYTES = 1024 * 1024
}
