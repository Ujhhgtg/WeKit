package dev.ujhhgtg.wekit.utils.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader

private const val LT_CHAR = '<'
private const val GT_CHAR = '>'
private const val SLASH_CHAR = '/'
private const val EQ_CHAR = '='
private const val BANG_CHAR = '!'
private const val QUEST_CHAR = '?'

data class XmlParserConfiguration(
    val cDataTagName: String = "#text",
    val convertNilAttributeToNull: Boolean = false,
    val keepBooleanAsString: Boolean = false,
    val keepNumberAsString: Boolean = false,
    val keepStrings: Boolean = false,
    val trimWhiteSpace: Boolean = true,
    val maxNestingDepth: Int = Int.MAX_VALUE,
    val forceList: Set<String> = emptySet(),
    val xsiTypeMap: Map<String, (String) -> JsonElement> = emptyMap()
)

// ported from org.json.XML
object XmlJsonParser {

    @Suppress("NOTHING_TO_INLINE")
    inline fun toJsonObject(string: String, configuration: XmlParserConfiguration = XmlParserConfiguration()): JsonObject =
        toJsonObject(string.reader(), configuration)

    fun toJsonObject(reader: Reader, config: XmlParserConfiguration = XmlParserConfiguration()): JsonObject {
        val context = JsonAccumulator()
        val tokener = XmlTokener(reader, config)
        while (tokener.more()) {
            tokener.skipPast("<")
            if (tokener.more()) {
                parse(tokener, context, null, config, 0)
            }
        }
        return context.toJsonObject(config.forceList)
    }

    private fun parse(
        x: XmlTokener,
        context: JsonAccumulator,
        name: String?,
        config: XmlParserConfiguration,
        currentNestingDepth: Int
    ): Boolean {
        var token: Any? = x.nextToken()

        when (token) {
            BANG_CHAR -> {
                val c = x.next()
                if (c == '-') {
                    if (x.next() == '-') {
                        x.skipPast("-->")
                        return false
                    }
                    x.back()
                } else if (c == '[') {
                    token = x.nextToken()
                    if ("CDATA" == token) {
                        if (x.next() == '[') {
                            val text = x.nextCDATA()
                            if (text.isNotEmpty()) {
                                context.accumulate(
                                    config.cDataTagName,
                                    stringToJsonElement(
                                        text,
                                        config.keepBooleanAsString,
                                        config.keepNumberAsString,
                                        config.keepStrings
                                    )
                                )
                            }
                            return false
                        }
                    }
                    throw x.syntaxError("Expected 'CDATA['")
                }

                var depth = 1
                do {
                    token = x.nextMeta()
                    when (token) {
//                        null -> {
//                            throw x.syntaxError("Missing '>' after '<!'.")
//                        }
                        LT_CHAR -> {
                            depth += 1
                        }

                        GT_CHAR -> {
                            depth -= 1
                        }
                    }
                } while (depth > 0)

                return false
            }

            QUEST_CHAR -> {
                x.skipPast("?>")
                return false
            }

            SLASH_CHAR -> {
                val closeName = x.nextToken()
                if (name == null) {
                    throw x.syntaxError("Mismatched close tag $closeName")
                }
                if (closeName != name) {
                    throw x.syntaxError("Mismatched $name and $closeName")
                }
                if (x.nextToken() != GT_CHAR) {
                    throw x.syntaxError("Misshaped close tag")
                }
                return true
            }

            is Char -> throw x.syntaxError("Misshaped tag")

            else -> {
                val tagName = token as String
                token = null

                val jsonObject = JsonAccumulator()
                var nilAttributeFound = false
                var xmlXsiTypeConverter: ((String) -> JsonElement)? = null

                while (true) {
                    if (token == null) {
                        token = x.nextToken()
                    }

                    if (token is String) {
                        val attrName = token
                        token = x.nextToken()

                        if (token == EQ_CHAR) {
                            token = x.nextToken()
                            if (token !is String) {
                                throw x.syntaxError("Missing value")
                            }

                            val attrValue = token
                            when {
                                config.convertNilAttributeToNull && attrName == NULL_ATTR && attrValue.toBooleanStrictOrNull() == true -> {
                                    nilAttributeFound = true
                                }
                                config.xsiTypeMap.isNotEmpty() && attrName == TYPE_ATTR -> {
                                    xmlXsiTypeConverter = config.xsiTypeMap[attrValue]
                                }
                                !nilAttributeFound -> {
                                    jsonObject.accumulate(
                                        attrName,
                                        stringToJsonElement(
                                            attrValue,
                                            config.keepBooleanAsString,
                                            config.keepNumberAsString,
                                            config.keepStrings
                                        )
                                    )
                                }
                            }
                            token = null
                        } else {
                            jsonObject.accumulate(attrName, JsonPrimitive(""))
                        }
                    } else if (token == SLASH_CHAR) {
                        if (x.nextToken() != GT_CHAR) {
                            throw x.syntaxError("Misshaped tag")
                        }

                        val emptyValue: JsonElement =
                            if (nilAttributeFound) JsonNull
                            else if (jsonObject.isEmpty()) JsonPrimitive("")
                            else jsonObject.toJsonElement(config.forceList)

                        if (tagName in config.forceList) {
                            if (nilAttributeFound) {
                                context.append(tagName, JsonNull)
                            } else if (jsonObject.isEmpty()) {
                                context.put(tagName, JsonArray(emptyList()))
                            } else {
                                context.append(tagName, emptyValue)
                            }
                        } else {
                            context.accumulate(tagName, emptyValue)
                        }
                        return false
                    } else if (token == GT_CHAR) {
                        while (true) {
                            token = x.nextContent()
                            when (token) {
                                null -> {
                                    throw x.syntaxError("Unclosed tag $tagName")
                                }

                                is String -> {
                                    val text = token
                                    if (text.isNotEmpty()) {
                                        val contentValue =
                                            xmlXsiTypeConverter?.invoke(text)
                                                ?: stringToJsonElement(
                                                    text,
                                                    config.keepBooleanAsString,
                                                    config.keepNumberAsString,
                                                    config.keepStrings
                                                )
                                        jsonObject.accumulate(config.cDataTagName, contentValue)
                                    }
                                }

                                LT_CHAR -> {
                                    if (currentNestingDepth == config.maxNestingDepth) {
                                        throw x.syntaxError(
                                            "Maximum nesting depth of ${config.maxNestingDepth} reached"
                                        )
                                    }

                                    if (parse(x, jsonObject, tagName, config, currentNestingDepth + 1)) {
                                        val value: JsonElement? =
                                            if (jsonObject.isEmpty()) {
                                                null
                                            } else if (jsonObject.size == 1) {
                                                jsonObject.singleValue(config.cDataTagName)
                                            } else {
                                                null
                                            }

                                        if (tagName in config.forceList) {
                                            when {
                                                jsonObject.isEmpty() -> context.put(tagName, JsonArray(emptyList()))
                                                value != null -> context.append(tagName, value)
                                                else -> {
                                                    if (!config.trimWhiteSpace) {
                                                        removeEmpty(jsonObject)
                                                    }
                                                    context.append(tagName, jsonObject.toJsonElement(config.forceList))
                                                }
                                            }
                                        } else {
                                            when {
                                                jsonObject.isEmpty() -> context.accumulate(tagName, JsonPrimitive(""))
                                                value != null -> context.accumulate(tagName, value)
                                                else -> {
                                                    if (!config.trimWhiteSpace) {
                                                        removeEmpty(jsonObject)
                                                    }
                                                    context.accumulate(tagName, jsonObject.toJsonElement(config.forceList))
                                                }
                                            }
                                        }
                                        return false
                                    }
                                }

                                else -> throw x.syntaxError("Misshaped tag")
                            }
                        }
                    } else {
                        throw x.syntaxError("Misshaped tag")
                    }
                }
            }
        }
    }

    private fun stringToJsonElement(
        text: String,
        keepBooleanAsString: Boolean,
        keepNumberAsString: Boolean,
        keepStrings: Boolean
    ): JsonElement {
        when (text) {
            "true" -> return if (keepBooleanAsString) JsonPrimitive(text) else JsonPrimitive(true)
            "false" -> return if (keepBooleanAsString) JsonPrimitive(text) else JsonPrimitive(false)
            "null" -> return if (keepStrings) JsonPrimitive(text) else JsonNull
        }

        if (!keepNumberAsString) {
            text.toLongOrNull()?.let { return JsonPrimitive(it) }
            text.toDoubleOrNull()?.let { if (it.isFinite()) return JsonPrimitive(it) }
        }

        return JsonPrimitive(text)
    }

    private fun removeEmpty(obj: JsonAccumulator) {
        obj.pruneEmptyStrings()
    }

    private const val NULL_ATTR = "nil"
    private const val TYPE_ATTR = "type"
}

private class JsonAccumulator {
    private val values: LinkedHashMap<String, MutableList<JsonElement>> = linkedMapOf()

    val size: Int get() = values.size
    fun isEmpty(): Boolean = values.isEmpty()

    fun accumulate(name: String, value: JsonElement) {
        values.getOrPut(name) { mutableListOf() }.add(value)
    }

    fun put(name: String, value: JsonElement) {
        values[name] = mutableListOf(value)
    }

    fun append(name: String, value: JsonElement) {
        accumulate(name, value)
    }

    fun singleValue(name: String): JsonElement? {
        val list = values[name] ?: return null
        return when (list.size) {
            0 -> null
            1 -> list[0]
            else -> JsonArray(list.toList())
        }
    }

    fun toJsonElement(forceList: Set<String>): JsonElement {
        if (values.size == 1) {
            val (key, list) = values.entries.first()
            return if (key in forceList) {
                JsonArray(list.toList())
            } else if (list.size == 1) {
                list[0]
            } else {
                JsonArray(list.toList())
            }
        }

        val map = linkedMapOf<String, JsonElement>()
        for ((key, list) in values) {
            map[key] = if (key in forceList) {
                JsonArray(list.toList())
            } else if (list.size == 1) {
                list[0]
            } else {
                JsonArray(list.toList())
            }
        }
        return JsonObject(map)
    }

    fun toJsonObject(forceList: Set<String>): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        for ((key, list) in values) {
            map[key] = if (key in forceList) {
                JsonArray(list.toList())
            } else if (list.size == 1) {
                list[0]
            } else {
                JsonArray(list.toList())
            }
        }
        return JsonObject(map)
    }

    fun pruneEmptyStrings() {
        for ((_, list) in values) {
            list.removeAll { element ->
                element is JsonPrimitive && element.isString && element.content.isEmpty()
            }
        }
        values.entries.removeAll { it.value.isEmpty() }
    }
}

private class XmlTokener(
    reader: Reader,
    private val configuration: XmlParserConfiguration = XmlParserConfiguration()
) {
    private val reader: Reader = if (reader.markSupported()) reader else BufferedReader(reader)
    private var character: Long = 1
    private var eof: Boolean = false
    private var index: Long = 0
    private var line: Long = 1
    private var previous: Char = 0.toChar()
    private var usePrevious: Boolean = false
    private var characterPreviousLine: Long = 0

    fun nextCDATA(): String {
        val sb = StringBuilder()
        while (more()) {
            val c = next()
            sb.append(c)
            val i = sb.length - 3
            if (i >= 0 && sb[i] == ']' && sb[i + 1] == ']' && sb[i + 2] == '>') {
                sb.setLength(i)
                return sb.toString()
            }
        }
        throw syntaxError("Unclosed CDATA")
    }

    fun nextContent(): Any? {
        var c: Char
        do {
            c = next()
        } while (c.isWhitespace() && configuration.trimWhiteSpace)

        if (c == 0.toChar()) return null
        if (c == LT_CHAR) return LT_CHAR

        val sb = StringBuilder()
        while (true) {
            if (c == 0.toChar()) {
                return sb.toString().trim()
            }
            if (c == LT_CHAR) {
                back()
                return if (configuration.trimWhiteSpace) sb.toString().trim() else sb.toString()
            }
            if (c == '&') {
                when (val ent = nextEntity(c)) {
                    is Char -> sb.append(ent)
                    is String -> sb.append(ent)
                }
            } else {
                sb.append(c)
            }
            c = next()
        }
    }

    fun nextEntity(@Suppress("UNUSED_PARAMETER") ampersand: Char): Any {
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when {
                c.isLetterOrDigit() || c == '#' -> sb.append(c.lowercaseChar())
                c == ';' -> break
                else -> throw syntaxError("Missing ';' in XML entity: &$sb")
            }
        }
        return unescapeEntity(sb.toString())
    }

    companion object {
        fun unescapeEntity(e: String?): String {
            if (e.isNullOrEmpty()) return ""
            if (e[0] == '#') {
                val cp = if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) {
                    e.substring(2).toInt(16)
                } else {
                    e.substring(1).toInt()
                }
                return String(Character.toChars(cp))
            }
            return entity[e]?.toString() ?: "&$e;"
        }

        private val entity: HashMap<String, Char> = hashMapOf(
            "amp" to '&',
            "apos" to '\'',
            "gt" to '>',
            "lt" to '<',
            "quot" to '"'
        )
    }

    fun nextMeta(): Any {
        var c: Char
        do {
            c = next()
        } while (c.isWhitespace())

        return when (c) {
            0.toChar() -> throw syntaxError("Misshaped meta tag")
            LT_CHAR -> LT_CHAR
            GT_CHAR -> GT_CHAR
            SLASH_CHAR -> SLASH_CHAR
            EQ_CHAR -> EQ_CHAR
            BANG_CHAR -> BANG_CHAR
            QUEST_CHAR -> QUEST_CHAR
            '"', '\'' -> {
                val q = c
                while (true) {
                    c = next()
                    if (c == 0.toChar()) throw syntaxError("Unterminated string")
                    if (c == q) return true
                }
            }

            else -> {
                while (true) {
                    c = next()
                    if (c.isWhitespace()) return true
                    when (c) {
                        0.toChar() -> throw syntaxError("Unterminated string")
                        LT_CHAR, GT_CHAR, SLASH_CHAR, EQ_CHAR, BANG_CHAR, QUEST_CHAR, '"', '\'' -> {
                            back()
                            return true
                        }
                    }
                }
            }
        }
    }

    fun nextToken(): Any {
        var c: Char
        do {
            c = next()
        } while (c.isWhitespace())

        return when (c) {
            0.toChar() -> throw syntaxError("Misshaped element")
            LT_CHAR -> throw syntaxError("Misplaced '<'")
            GT_CHAR -> GT_CHAR
            SLASH_CHAR -> SLASH_CHAR
            EQ_CHAR -> EQ_CHAR
            BANG_CHAR -> BANG_CHAR
            QUEST_CHAR -> QUEST_CHAR
            '"', '\'' -> {
                val q = c
                val sb = StringBuilder()
                while (true) {
                    c = next()
                    if (c == 0.toChar()) throw syntaxError("Unterminated string")
                    if (c == q) return sb.toString()
                    if (c == '&') {
                        when (val ent = nextEntity(c)) {
                            is Char -> sb.append(ent)
                            is String -> sb.append(ent)
                        }
                    } else {
                        sb.append(c)
                    }
                }
            }

            else -> {
                val sb = StringBuilder()
                var ch = c
                while (true) {
                    sb.append(ch)
                    ch = next()
                    when {
                        ch.isWhitespace() -> return sb.toString()
                        ch == 0.toChar() -> return sb.toString()
                        ch == GT_CHAR || ch == SLASH_CHAR || ch == EQ_CHAR ||
                                ch == BANG_CHAR || ch == QUEST_CHAR ||
                                ch == '[' || ch == ']' -> {
                            back()
                            return sb.toString()
                        }

                        ch == LT_CHAR || ch == '"' || ch == '\'' -> {
                            throw syntaxError("Bad character in a name")
                        }
                    }
                }
            }
        }
    }

    fun skipPast(to: String) {
        var c: Char
        var j: Int
        var offset = 0
        val length = to.length
        val circle = CharArray(length)

        for (idx in 0 until length) {
            c = next()
            if (c == 0.toChar()) return
            circle[idx] = c
        }

        while (true) {
            j = offset
            var match = true
            for (idx in 0 until length) {
                if (circle[j] != to[idx]) {
                    match = false
                    break
                }
                j += 1
                if (j >= length) j -= length
            }
            if (match) return

            c = next()
            if (c == 0.toChar()) return
            circle[offset] = c
            offset += 1
            if (offset >= length) offset -= length
        }
    }

    fun next(): Char {
        val c: Int = if (usePrevious) {
            usePrevious = false
            previous.code
        } else {
            try {
                reader.read()
            } catch (e: IOException) {
                throw JSONException(e)
            }
        }

        if (c <= 0) {
            eof = true
            return 0.toChar()
        }

        incrementIndexes(c)
        previous = c.toChar()
        return previous
    }

    fun more(): Boolean {
        if (usePrevious) return true
        try {
            reader.mark(1)
        } catch (e: IOException) {
            throw JSONException("Unable to preserve stream position", e)
        }

        try {
            if (reader.read() <= 0) {
                eof = true
                return false
            }
            reader.reset()
        } catch (e: IOException) {
            throw JSONException("Unable to read the next character from the stream", e)
        }
        return true
    }

    fun back() {
        if (usePrevious || index <= 0) {
            throw JSONException("Stepping back two steps is not supported")
        }
        decrementIndexes()
        usePrevious = true
        eof = false
    }

    private fun decrementIndexes() {
        index--
        if (previous == '\r' || previous == '\n') {
            line--
            character = characterPreviousLine
        } else if (character > 0) {
            character--
        }
    }

    private fun incrementIndexes(c: Int) {
        if (c > 0) {
            index++
            when (c.toChar()) {
                '\r' -> {
                    line++
                    characterPreviousLine = character
                    character = 0
                }

                '\n' -> {
                    if (previous != '\r') {
                        line++
                        characterPreviousLine = character
                    }
                    character = 0
                }

                else -> character++
            }
        }
    }

    fun syntaxError(message: String): JSONException = JSONException(message + toString())

    override fun toString(): String {
        return " at $index [character $character line $line]"
    }
}

class JSONException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
