package moe.ouom.wekit.utils

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 通用 JSON -> Protobuf 字节流转换器
 */
object ProtoJsonBuilder {

    private const val WIRETYPE_VARINT = 0
    private const val WIRETYPE_LENGTH_DELIMITED = 2

    fun makeBytes(json: JSONObject): ByteArray {
        val output = ByteArrayOutputStream()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val tag = key.toIntOrNull() ?: continue
            val value = json.get(key)
            writeTagData(output, tag, value)
        }
        return output.toByteArray()
    }

    private fun writeTagData(os: ByteArrayOutputStream, tag: Int, value: Any?) {
        when (value) {
            is Int -> writeVarintTag(os, tag, value.toLong())
            is Long -> writeVarintTag(os, tag, value)
            is Boolean -> writeVarintTag(os, tag, if (value) 1L else 0L)
            is String -> writeStringTag(os, tag, value)
            is JSONObject -> {
                val nestedBytes = makeBytes(value)
                writeLengthDelimitedTag(os, tag, nestedBytes)
            }

            is JSONArray -> {
                for (i in 0 until value.length()) {
                    writeTagData(os, tag, value.get(i))
                }
            }
            // 兼容部分数字可能被识别为 String 的情况
            else -> if (value != null) writeStringTag(os, tag, value.toString())
        }
    }

    private fun writeVarintTag(os: ByteArrayOutputStream, tag: Int, value: Long) {
        writeRawVarint32(os, (tag shl 3) or WIRETYPE_VARINT)
        writeRawVarint64(os, value)
    }

    private fun writeStringTag(os: ByteArrayOutputStream, tag: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeLengthDelimitedTag(os, tag, bytes)
    }

    private fun writeLengthDelimitedTag(os: ByteArrayOutputStream, tag: Int, bytes: ByteArray) {
        writeRawVarint32(os, (tag shl 3) or WIRETYPE_LENGTH_DELIMITED)
        writeRawVarint32(os, bytes.size)
        os.write(bytes)
    }

    private fun writeRawVarint32(os: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                os.write(v)
                return
            } else {
                os.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private fun writeRawVarint64(os: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0L) {
                os.write(v.toInt())
                return
            } else {
                os.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }
}