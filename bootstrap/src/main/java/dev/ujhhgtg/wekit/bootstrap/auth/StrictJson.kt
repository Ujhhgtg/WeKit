package dev.ujhhgtg.wekit.bootstrap.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reject decoded duplicate keys before the kotlinx.serialization JSON tree is constructed. */
object StrictJson {
    fun objectFrom(bytes: ByteArray, limit: Int = 65536): JsonObject {
        require(bytes.size <= limit) { "JSON exceeds input limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        Parser(text).validate()
        return WireJson.format.parseToJsonElement(text).jsonObject
    }

    fun integer(json: JsonObject, name: String): Long = json.integer(name)

    private class Parser(private val text: String) {
        private var cursor = 0
        private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        fun validate() {
            whitespace()
            require(peek() == '{') { "JSON root must be object" }
            value(0)
            whitespace()
            require(cursor == text.length) { "trailing JSON data" }
        }
        private fun peek() = text.getOrNull(cursor)
        private fun whitespace() { while (peek() in listOf(' ', '\t', '\r', '\n')) cursor++ }
        private fun expect(char: Char) { require(peek() == char) { "invalid JSON syntax" }; cursor++ }
        private fun string(): String {
            val begin = cursor
            expect('"')
            while (true) {
                val char = peek() ?: error("unterminated JSON string")
                cursor++
                when {
                    char == '"' -> return WireJson.format.decodeFromString<String>(text.substring(begin, cursor))
                    char == '\\' -> {
                        val escape = peek() ?: error("unterminated JSON escape")
                        cursor++
                        if (escape == 'u') {
                            require(cursor + 4 <= text.length && text.substring(cursor, cursor + 4).all { it in "0123456789abcdefABCDEF" })
                            cursor += 4
                        } else require(escape in "\"\\/bfnrt")
                    }
                    char.code < 32 -> error("control character in JSON")
                }
            }
        }
        private fun value(depth: Int) {
            require(depth < 32) { "JSON nesting limit" }
            whitespace()
            when (peek()) {
                '{' -> {
                    cursor++; whitespace()
                    if (peek() == '}') { cursor++; return }
                    val keys = HashSet<String>()
                    while (true) {
                        require(peek() == '"')
                        require(keys.add(string())) { "duplicate JSON key" }
                        whitespace(); expect(':'); value(depth + 1); whitespace()
                        if (peek() == '}') { cursor++; break }
                        expect(','); whitespace()
                    }
                }
                '[' -> {
                    cursor++; whitespace()
                    if (peek() == ']') { cursor++; return }
                    while (true) {
                        value(depth + 1); whitespace()
                        if (peek() == ']') { cursor++; break }
                        expect(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val match = number.find(text, cursor)
                    require(match != null && match.range.first == cursor) { "invalid JSON value" }
                    cursor = match.range.last + 1
                }
            }
        }
        private fun literal(value: String) { require(text.startsWith(value, cursor)); cursor += value.length }
    }
}
