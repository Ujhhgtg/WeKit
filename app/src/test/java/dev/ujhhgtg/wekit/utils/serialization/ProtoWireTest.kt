package dev.ujhhgtg.wekit.utils.serialization

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtoWireTest {

    // field 1: string "a"; field 5: string "hi"; field 7: varint 300; field 11: fixed64; field 12:
    // fixed32
    private val message =
        byteArrayOf(
            0x0A,
            0x01,
            'a'.code.toByte(),
            0x2A,
            0x02,
            'h'.code.toByte(),
            'i'.code.toByte(),
            0x38,
            0xAC.toByte(),
            0x02,
            0x59,
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            0x65,
            1,
            2,
            3,
            4,
        )

    @Test
    fun readsFieldsAcrossAllWireTypes() {
        assertEquals("a", ProtoWire.string(message, 1))
        assertEquals("hi", ProtoWire.string(message, 5))
        assertEquals(300L, ProtoWire.varint(message, 7))
        assertNull(ProtoWire.string(message, 9))
        assertNull(ProtoWire.varint(message, 5))
    }

    @Test
    fun replacesStringInPlaceKeepingOtherBytes() {
        val edited = ProtoWire.withString(message, 5, "你好")

        assertEquals("你好", ProtoWire.string(edited, 5))
        assertArrayEquals(message.copyOfRange(0, 3), edited.copyOfRange(0, 3))
        assertArrayEquals(
            message.copyOfRange(7, message.size),
            edited.copyOfRange(edited.size - (message.size - 7), edited.size),
        )
    }

    @Test
    fun appendsAbsentFieldAndCollapsesDuplicates() {
        val appended = ProtoWire.withString(message, 9, "x")
        assertArrayEquals(message, appended.copyOfRange(0, message.size))
        assertEquals("x", ProtoWire.string(appended, 9))

        val duplicated = message + byteArrayOf(0x2A, 0x01, 'z'.code.toByte())
        val collapsed = ProtoWire.withString(duplicated, 5, "y")
        assertArrayEquals(ProtoWire.withString(message, 5, "y"), collapsed)
    }
}
