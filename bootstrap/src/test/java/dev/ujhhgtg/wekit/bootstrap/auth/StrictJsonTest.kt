package dev.ujhhgtg.wekit.bootstrap.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class StrictJsonTest {
    @Test
    fun `rejects duplicate keys after unicode escape decoding at every nesting level`() {
        for (json in listOf(
            "{\"a\":1,\"\\u0061\":2}",
            "{\"outer\":{\"a\":1,\"\\u0061\":2}}",
            "{\"items\":[{\"a\":1,\"\\u0061\":2}]}",
        )) {
            assertThrows(IllegalArgumentException::class.java) { StrictJson.objectFrom(json.toByteArray()) }
        }
    }

    @Test
    fun `rejects permissive Android JSON extensions and ambiguous trailing values`() {
        for (json in listOf(
            "{'a':1}", "{a:1}", "{\"a\":1,}", "{\"a\":[1,]}",
            "{\"a\":01}", "{\"a\":+1}", "{\"a\":NaN}", "{\"a\":Infinity}",
            "{\"a\":1/*comment*/}", "{\"a\":1}true", "{\"a\":\"\\x41\"}",
        )) {
            assertThrows(IllegalArgumentException::class.java) { StrictJson.objectFrom(json.toByteArray()) }
        }
    }

    @Test
    fun `rejects malformed UTF8 instead of silently replacing request bytes`() {
        val malformed = "{\"a\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { StrictJson.objectFrom(malformed) }
    }

    @Test
    fun `parses valid nested documents with escaped text without changing integer values`() {
        val json = StrictJson.objectFrom("""{"items":[{"line":"a\nb","quote":"\""}],"count":1048595,"nil":null,"flag":false}""".toByteArray())
        assertEquals(1048595L, StrictJson.integer(json, "count"))
        assertEquals("a\nb", json.getValue("items").jsonArray[0].jsonObject.string("line"))
        assertEquals("\"", json.getValue("items").jsonArray[0].jsonObject.string("quote"))
    }

    @Test
    fun `rejects deeply nested and oversized wire input before native JSON parsing`() {
        val deep = "{\"a\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"
        assertThrows(IllegalArgumentException::class.java) { StrictJson.objectFrom(deep.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { StrictJson.objectFrom("{}".toByteArray(), 1) }
    }
}
