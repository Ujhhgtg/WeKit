package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadReceiptRecordTest {

    @Test
    fun `round trips third party endpoint`() {
        val record = ReadReceiptRecord(
            "0123456789abcdef",
            "wxid_a",
            ReadReceiptBackend.THIRD_PARTY,
            "https://receipts.example",
            1_700_000_000_000,
        )
        assertEquals(record, ReadReceiptRecordCodec.decode(ReadReceiptRecordCodec.encode(record)))
    }

    @Test
    fun `round trips built in logical endpoint`() {
        val record = ReadReceiptRecord(
            "abcdef0123456789",
            "wxid_b",
            ReadReceiptBackend.BUILT_IN,
            "builtin://local",
            1_700_000_000_000,
        )
        assertEquals(record, ReadReceiptRecordCodec.decode(ReadReceiptRecordCodec.encode(record)))
    }

    @Test
    fun `rejects unsupported schema version`() {
        assertNull(ReadReceiptRecordCodec.decode("{\"version\":99}"))
    }

    @Test
    fun `rejects malformed id wxId backend endpoint and timestamp`() {
        assertNull(
            ReadReceiptRecordCodec.decode(
                "{\"version\":1,\"id\":\"not-hex\",\"wxId\":\"wxid\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"https://x\",\"createdAtMillis\":1}"
            )
        )
    }

    @Test
    fun `prunes records older than 180 days and retains boundary`() {
        val now = 1_800_000_000_000
        val retention = 180L * 24 * 60 * 60 * 1000
        val boundary = ReadReceiptRecord(
            "0123456789abcdef",
            "wxid",
            ReadReceiptBackend.BUILT_IN,
            "builtin://local",
            now - retention,
        )
        val expired = boundary.copy(id = "abcdef0123456789", createdAtMillis = now - retention - 1)
        assertEquals(
            setOf(boundary),
            ReadReceiptRecordCodec.prune(listOf(boundary, expired), now, retention),
        )
    }

    @Test
    fun `deduplicates records by backend wxId id endpoint`() {
        val record = ReadReceiptRecord(
            "0123456789abcdef",
            "wxid",
            ReadReceiptBackend.BUILT_IN,
            "builtin://local",
            1_700_000_000_000,
        )
        assertEquals(
            setOf(record),
            ReadReceiptRecordCodec.prune(listOf(record, record), 1_700_000_000_001, Long.MAX_VALUE),
        )
    }
}
