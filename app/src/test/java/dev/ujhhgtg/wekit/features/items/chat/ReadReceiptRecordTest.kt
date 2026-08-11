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
    fun `canonicalizes third party endpoint before persistent round trip`() {
        val submitted = ReadReceiptRecord(
            "0123456789abcdef",
            "wxid_a",
            ReadReceiptBackend.THIRD_PARTY,
            "HTTPS://Example.COM/receipts/",
            1_700_000_000_000,
        )

        assertEquals(
            submitted.copy(endpoint = "https://example.com/receipts"),
            ReadReceiptRecordCodec.decode(ReadReceiptRecordCodec.encode(submitted)),
        )
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
    fun `rejects malformed id`() {
        assertNull(
            ReadReceiptRecordCodec.decode(
                "{\"version\":1,\"id\":\"not-hex\",\"wxId\":\"wxid\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"https://x\",\"createdAtMillis\":1}"
            )
        )
    }

    @Test
    fun `rejects malformed wxId`() {
        assertNull(
            ReadReceiptRecordCodec.decode(
                "{\"version\":1,\"id\":\"0123456789abcdef\",\"wxId\":\"\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"https://x\",\"createdAtMillis\":1}"
            )
        )
        assertNull(
            ReadReceiptRecordCodec.decode(
                "{\"version\":1,\"id\":\"0123456789abcdef\",\"wxId\":\"${"界".repeat(43)}\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"https://x\",\"createdAtMillis\":1}"
            )
        )
    }

    @Test
    fun `rejects malformed backend`() {
        assertNull(
            ReadReceiptRecordCodec.decode(
                "{\"version\":1,\"id\":\"0123456789abcdef\",\"wxId\":\"wxid\",\"backend\":\"UNKNOWN\",\"endpoint\":\"https://x\",\"createdAtMillis\":1}"
            )
        )
    }

    @Test
    fun `rejects malformed third party endpoints`() {
        for (
            endpoint in listOf(
                "http://",
                "http://receipts.example",
                "https:///path",
                "https://?query",
                "https://user:password@receipts.example",
                "https://@receipts.example",
                "https://receipts.example:@",
                "https://receipts.example/path?token=secret",
                "https://receipts.example/path#fragment",
            )
        ) {
            assertNull(
                ReadReceiptRecordCodec.decode(
                    "{\"version\":1,\"id\":\"0123456789abcdef\",\"wxId\":\"wxid\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"$endpoint\",\"createdAtMillis\":1}"
                ),
                endpoint,
            )
        }
    }

    @Test
    fun `rejects malformed timestamp`() {
        assertNull(
            ReadReceiptRecordCodec.decode(
                "{\"version\":1,\"id\":\"0123456789abcdef\",\"wxId\":\"wxid\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"https://x\",\"createdAtMillis\":0}"
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
    fun `normalizes trailing slash before pruning and deduplication`() {
        val canonical = ReadReceiptRecord(
            "0123456789abcdef",
            "wxid",
            ReadReceiptBackend.THIRD_PARTY,
            "https://receipts.example",
            1_700_000_000_000,
        )
        val trailingSlash = canonical.copy(endpoint = "https://receipts.example/")
        assertEquals(
            setOf(canonical),
            ReadReceiptRecordCodec.prune(
                listOf(trailingSlash, canonical),
                1_700_000_000_001,
                Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `deduplicates identity with differing timestamps and keeps newest`() {
        val older = ReadReceiptRecord(
            "0123456789abcdef",
            "wxid",
            ReadReceiptBackend.BUILT_IN,
            "builtin://local",
            1_700_000_000_000,
        )
        val newer = older.copy(createdAtMillis = 1_700_000_000_001)
        assertEquals(
            setOf(newer),
            ReadReceiptRecordCodec.prune(
                listOf(newer, older),
                1_700_000_000_002,
                Long.MAX_VALUE,
            ),
        )
    }
}
