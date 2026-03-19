package moe.ouom.wekit.hooks.api.net

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import moe.ouom.wekit.utils.hexToBytes
import moe.ouom.wekit.utils.logging.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@Suppress("unused")
class WeProtoData {

    private val fields = mutableListOf<Field>()
    var packetPrefix: ByteArray = ByteArray(0)
        private set

    private enum class LenView { AUTO, SUB, UTF8, HEX }

    private data class Field(
        val fieldNumber: Int,
        val wireType: Int,
        var value: Any,
    )

    private class LenValue(raw: ByteArray?) {
        var raw: ByteArray = raw ?: ByteArray(0)
        var utf8: String? = null
        var subMessage: WeProtoData? = null
        var view: LenView = LenView.AUTO
    }

    companion object {
        fun hasPacketPrefix(b: ByteArray?) =
            b != null && b.size >= 4 && (b[0].toInt() and 0xFF) == 0

        fun getUnpPackage(b: ByteArray?): ByteArray? = when {
            b == null -> null
            b.size < 4 -> b
            (b[0].toInt() and 0xFF) == 0 -> b.copyOfRange(4, b.size)
            else -> b
        }

        fun bytesToHex(bytes: ByteArray?): String {
            if (bytes == null || bytes.isEmpty()) return ""
            return bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }

        private fun stripNonHex(s: String?): String =
            s?.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ?: ""

        private fun tryDecodeUtf8Roundtrip(b: ByteArray?): String? {
            if (b == null) return null
            return runCatching {
                val s = String(b, StandardCharsets.UTF_8)
                s.takeIf { s.toByteArray(StandardCharsets.UTF_8).contentEquals(b) }
            }.getOrNull()
        }

        private fun tryParseSubMessageStrong(bytes: ByteArray?): WeProtoData? {
            if (bytes == null || bytes.isEmpty()) return null
            return runCatching {
                val sub = WeProtoData()
                sub.parseMessageBytes(bytes)
                if (sub.fields.isEmpty()) return null
                val re = sub.toMessageBytes()
                sub.takeIf { re.contentEquals(bytes) }
            }.getOrNull()
        }

        private fun analyzeLenValue(lv: LenValue) {
            tryParseSubMessageStrong(lv.raw)?.let {
                lv.subMessage = it
                lv.utf8 = null
                lv.view = LenView.SUB
                return
            }
            tryDecodeUtf8Roundtrip(lv.raw)?.let {
                lv.utf8 = it
                lv.subMessage = null
                lv.view = LenView.UTF8
                return
            }
            lv.utf8 = null
            lv.subMessage = null
            lv.view = LenView.HEX
        }
    }

    private fun ensureSubParsedStrong(lv: LenValue): WeProtoData? {
        lv.subMessage?.let { return it }
        return tryParseSubMessageStrong(lv.raw)?.also { lv.subMessage = it }
    }

    private fun ensureUtf8Decoded(lv: LenValue): String? {
        lv.utf8?.let { return it }
        return tryDecodeUtf8Roundtrip(lv.raw)?.also { lv.utf8 = it }
    }

    fun clear() {
        fields.clear()
        packetPrefix = ByteArray(0)
    }

    fun setPacketPrefix(prefix: ByteArray?) {
        packetPrefix = prefix?.copyOf() ?: ByteArray(0)
    }

    fun fromBytes(b: ByteArray?) {
        clear()
        if (b == null) return
        if (hasPacketPrefix(b)) {
            packetPrefix = b.copyOfRange(0, 4)
            parseMessageBytes(b.copyOfRange(4, b.size))
        } else {
            parseMessageBytes(b)
        }
    }

    fun fromMessageBytes(b: ByteArray?) {
        clear()
        packetPrefix = ByteArray(0)
        parseMessageBytes(b)
    }

    private fun parseMessageBytes(b: ByteArray?) {
        if (b == null) return
        val input = CodedInputStream.newInstance(b)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            if (wireType == 4 || wireType == 3 || wireType > 5) throw IOException("Unexpected wireType: $wireType")
            when (wireType) {
                0 -> fields.add(Field(fieldNumber, wireType, input.readInt64()))
                1 -> fields.add(Field(fieldNumber, wireType, input.readFixed64()))
                2 -> {
                    val lv = LenValue(input.readByteArray())
                    analyzeLenValue(lv)
                    fields.add(Field(fieldNumber, wireType, lv))
                }

                5 -> fields.add(Field(fieldNumber, wireType, input.readFixed32()))
            }
        }
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        for (f in fields) {
            val k = f.fieldNumber.toString()
            val jsonVal = fieldValueToJsonValue(f)
            if (!obj.has(k)) {
                obj.put(k, jsonVal)
            } else {
                val existing = obj.get(k)
                val arr =
                    existing as? JSONArray ?: JSONArray().also { it.put(existing); obj.put(k, it) }
                arr.put(jsonVal)
            }
        }
        return obj
    }

    private fun fieldValueToJsonValue(f: Field): Any {
        if (f.wireType != 2) return f.value
        val lv = f.value as LenValue
        return when (lv.view) {
            LenView.AUTO, LenView.SUB -> {
                ensureSubParsedStrong(lv)?.also { lv.view = LenView.SUB }?.toJsonObject()
                    ?: ensureUtf8Decoded(lv)?.also { lv.view = LenView.UTF8 }
                    ?: ("hex->" + bytesToHex(lv.raw)).also { lv.view = LenView.HEX }
            }

            LenView.UTF8 -> {
                ensureUtf8Decoded(lv)
                    ?: ensureSubParsedStrong(lv)?.toJsonObject()
                    ?: ("hex->" + bytesToHex(lv.raw))
            }

            LenView.HEX -> "hex->" + bytesToHex(lv.raw)
        }
    }

    fun toMessageBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = CodedOutputStream.newInstance(bos)
        return runCatching {
            for (f in fields) {
                when (f.wireType) {
                    0 -> {
                        val v = f.value as Long
                        if (v >= 0) out.writeUInt64(
                            f.fieldNumber,
                            v
                        ) else out.writeInt64(f.fieldNumber, v)
                    }

                    1 -> out.writeFixed64(f.fieldNumber, f.value as Long)
                    2 -> {
                        val lv = f.value as LenValue
                        if (lv.subMessage != null) {
                            val newRaw = lv.subMessage!!.toMessageBytes()
                            if (!newRaw.contentEquals(lv.raw)) lv.raw = newRaw
                        } else if (lv.utf8 != null && lv.view == LenView.UTF8) {
                            val newRaw = lv.utf8!!.toByteArray(StandardCharsets.UTF_8)
                            if (!newRaw.contentEquals(lv.raw)) lv.raw = newRaw
                        }
                        out.writeByteArray(f.fieldNumber, lv.raw)
                    }

                    5 -> out.writeFixed32(f.fieldNumber, f.value as Int)
                }
            }
            out.flush()
            bos.toByteArray()
        }.getOrElse {
            WeLogger.e("WeProtoData - toBytes", it)
            ByteArray(0)
        }
    }

    fun toPacketBytes(): ByteArray {
        val body = toMessageBytes()
        if (packetPrefix.isEmpty()) return body
        return packetPrefix + body
    }

    private fun findFieldIndex(fieldNumber: Int, occurrenceIndex: Int): Int {
        var occ = 0
        for (i in fields.indices) {
            if (fields[i].fieldNumber == fieldNumber) {
                if (occ == occurrenceIndex) return i
                occ++
            }
        }
        return -1
    }

    private fun indicesOf(fieldNumber: Int) =
        fields.indices.filter { fields[it].fieldNumber == fieldNumber }

    private fun removeAllOccurrences(fieldNumber: Int): Int {
        val before = fields.size
        fields.removeAll { it.fieldNumber == fieldNumber }
        return before - fields.size
    }

    fun setVarInt(fieldNumber: Int, occurrenceIndex: Int, value: Long): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        fields[idx].value = value
        return true
    }

    fun setFixed64(fieldNumber: Int, occurrenceIndex: Int, value: Long): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        fields[idx].value = value
        return true
    }

    fun setFixed32(fieldNumber: Int, occurrenceIndex: Int, value: Int): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        fields[idx].value = value
        return true
    }

    fun setLenHex(fieldNumber: Int, occurrenceIndex: Int, hex: String): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        val f = fields[idx].takeIf { it.wireType == 2 } ?: return false
        val lv = f.value as LenValue
        val h = stripNonHex(hex)
        lv.raw = if (h.isEmpty()) ByteArray(0) else hexToBytes(h)!!
        lv.utf8 = null
        lv.subMessage = null
        lv.view = LenView.HEX
        return true
    }

    fun setLenUtf8(fieldNumber: Int, occurrenceIndex: Int, text: String?): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        val f = fields[idx].takeIf { it.wireType == 2 } ?: return false
        val lv = f.value as LenValue
        val s = text ?: ""
        lv.utf8 = s
        lv.raw = s.toByteArray(StandardCharsets.UTF_8)
        lv.subMessage = null
        lv.view = LenView.UTF8
        return true
    }

    fun setLenSubBytes(fieldNumber: Int, occurrenceIndex: Int, subBytes: ByteArray?): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        val f = fields[idx].takeIf { it.wireType == 2 } ?: return false
        val lv = f.value as LenValue
        val sub = tryParseSubMessageStrong(subBytes)
        lv.raw = subBytes ?: ByteArray(0)
        lv.subMessage = sub
        lv.utf8 = null
        lv.view = if (sub != null) LenView.SUB else LenView.HEX
        return true
    }

    fun removeField(fieldNumber: Int, occurrenceIndex: Int): Boolean {
        val idx = findFieldIndex(fieldNumber, occurrenceIndex).takeIf { it >= 0 } ?: return false
        fields.removeAt(idx)
        return true
    }

    fun replaceUtf8Contains(needle: String?, replacement: String?): Int {
        if (needle.isNullOrEmpty()) return 0
        return replaceUtf8ContainsInternal(needle, replacement ?: "")
    }

    private fun replaceUtf8ContainsInternal(needle: String, replacement: String): Int {
        var changed = 0
        for (f in fields) {
            if (f.wireType != 2) continue
            val lv = f.value as LenValue
            ensureSubParsedStrong(lv)?.let { sub ->
                val subChanged = sub.replaceUtf8ContainsInternal(needle, replacement)
                if (subChanged > 0) {
                    lv.subMessage = sub
                    lv.raw = sub.toMessageBytes()
                    lv.utf8 = null
                    lv.view = LenView.SUB
                    changed += subChanged
                }
            }
            ensureUtf8Decoded(lv)?.takeIf { it.contains(needle) }?.let { s ->
                val ns = s.replace(needle, replacement)
                if (ns != s) {
                    lv.utf8 = ns
                    lv.raw = ns.toByteArray(StandardCharsets.UTF_8)
                    lv.subMessage = null
                    lv.view = LenView.UTF8
                    changed++
                }
            }
        }
        return changed
    }

    fun replaceUtf8Regex(pattern: Pattern?, replacement: String?): Int {
        if (pattern == null) return 0
        return replaceUtf8RegexInternal(pattern, replacement ?: "")
    }

    private fun replaceUtf8RegexInternal(pattern: Pattern, replacement: String): Int {
        var total = 0
        for (f in fields) {
            if (f.wireType != 2) continue
            val lv = f.value as LenValue
            ensureSubParsedStrong(lv)?.let { sub ->
                val subMatches = sub.replaceUtf8RegexInternal(pattern, replacement)
                if (subMatches > 0) {
                    lv.subMessage = sub
                    lv.raw = sub.toMessageBytes()
                    lv.utf8 = null
                    lv.view = LenView.SUB
                    total += subMatches
                }
            }
            ensureUtf8Decoded(lv)?.let { s ->
                val cnt = pattern.matcher(s).let { m -> var c = 0; while (m.find()) c++; c }
                if (cnt > 0) {
                    val ns = pattern.matcher(s).replaceAll(replacement)
                    lv.utf8 = ns
                    lv.raw = ns.toByteArray(StandardCharsets.UTF_8)
                    lv.subMessage = null
                    lv.view = LenView.UTF8
                    total += cnt
                }
            }
        }
        return total
    }

    fun fromJSON(json: JSONObject) {
        runCatching {
            clear()
            for (key in json.keys()) {
                val fieldNumber = key.toInt()
                when (val value = json.get(key)) {
                    is JSONObject -> fields.add(subMessageField(fieldNumber, value))
                    is JSONArray -> repeat(value.length()) {
                        addJsonValueAsField(
                            fieldNumber,
                            value.get(it)
                        )
                    }

                    else -> addJsonValueAsField(fieldNumber, value)
                }
            }
        }
    }

    private fun subMessageField(fieldNumber: Int, obj: JSONObject): Field {
        val sub = WeProtoData().also { it.fromJSON(obj) }
        val lv = LenValue(sub.toMessageBytes()).also { it.subMessage = sub; it.view = LenView.SUB }
        return Field(fieldNumber, 2, lv)
    }

    private fun addJsonValueAsField(fieldNumber: Int, value: Any?) {
        runCatching {
            when (value) {
                is JSONObject -> fields.add(subMessageField(fieldNumber, value))
                is Number -> fields.add(Field(fieldNumber, 0, value.toLong()))
                is String -> {
                    val lv = if (value.startsWith("hex->")) {
                        LenValue(hexToBytes(stripNonHex(value.substring(5)))).also {
                            it.view = LenView.HEX
                        }
                    } else {
                        LenValue(value.toByteArray(StandardCharsets.UTF_8)).also {
                            it.utf8 = value; it.view = LenView.UTF8
                        }
                    }
                    fields.add(Field(fieldNumber, 2, lv))
                }

                null -> Unit
                else -> WeLogger.w("WeProtoData.fromJSON Unknown type: ${value.javaClass.name}")
            }
        }
    }

    fun applyViewJson(view: JSONObject?, deleteMissing: Boolean): Int {
        if (view == null) return 0
        var changes = 0

        if (deleteMissing) {
            val existingNums = fields.map { it.fieldNumber }.distinct()
            for (fn in existingNums) {
                if (!view.has(fn.toString())) changes += removeAllOccurrences(fn)
            }
        }

        for (key in view.keys()) {
            val fn = key.toIntOrNull() ?: continue
            val value = view.opt(key)
            if (value == null || value == JSONObject.NULL) {
                if (deleteMissing) changes += removeAllOccurrences(fn)
                continue
            }
            if (value is JSONArray) {
                val idxs = indicesOf(fn)
                val min = minOf(value.length(), idxs.size)
                for (i in 0 until min) {
                    val v = value.opt(i)
                    if (v == JSONObject.NULL) continue
                    changes += applyOne(fields[idxs[i]], v, deleteMissing)
                }
                if (deleteMissing && idxs.size > value.length()) {
                    for (i in idxs.size - 1 downTo value.length()) {
                        fields.removeAt(idxs[i])
                        changes++
                    }
                }
            } else {
                val idx = findFieldIndex(fn, 0)
                if (idx >= 0) changes += applyOne(fields[idx], value, deleteMissing)
            }
        }
        return changes
    }

    private fun applyOne(field: Field, value: Any, deleteMissing: Boolean): Int {
        return runCatching {
            when (field.wireType) {
                0, 1 -> {
                    field.value = when (value) {
                        is Number -> value.toLong()
                        is String -> value.toLong()
                        else -> return@runCatching 0
                    }
                    1
                }

                5 -> {
                    field.value = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toInt()
                        else -> return@runCatching 0
                    }
                    1
                }

                2 -> {
                    val lv = field.value as LenValue
                    when (value) {
                        is JSONObject -> {
                            val sub = ensureSubParsedStrong(lv) ?: WeProtoData()
                            val c = sub.applyViewJson(value, deleteMissing)
                            lv.subMessage = sub
                            lv.raw = sub.toMessageBytes()
                            lv.utf8 = null
                            lv.view = LenView.SUB
                            maxOf(1, c)
                        }

                        is String -> {
                            if (value.startsWith("hex->")) {
                                lv.raw = hexToBytes(stripNonHex(value.substring(5))) ?: ByteArray(0)
                                lv.utf8 = null
                                lv.subMessage = null
                                lv.view = LenView.HEX
                            } else {
                                lv.utf8 = value
                                lv.raw = value.toByteArray(StandardCharsets.UTF_8)
                                lv.subMessage = null
                                lv.view = LenView.UTF8
                            }
                            1
                        }

                        else -> 0
                    }
                }

                else -> 0
            }
        }.getOrDefault(0)
    }
}