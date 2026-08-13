package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadReceiptRenderingTest {

    @Test
    fun `replaces placeholder in active template`() {
        assertEquals(
            "time · 已读 3 人 · type",
            renderReadReceiptText("time · " + READ_RECEIPTS_PLACEHOLDER + " · type", 3, true),
        )
    }

    @Test
    fun `appends suffix to active template without placeholder`() {
        assertEquals("time | 已读 3 人", renderReadReceiptText("time", 3, true))
    }

    @Test
    fun `appends suffix to native text when enhancement is inactive`() {
        assertEquals("native | 已读 3 人", renderReadReceiptText("native", 3, false))
    }

    @Test
    fun `clears placeholder when count is unknown`() {
        assertEquals(
            "time ·  · type",
            renderReadReceiptText("time · " + READ_RECEIPTS_PLACEHOLDER + " · type", null, true),
        )
    }

    @Test
    fun `renders known zero`() {
        assertEquals("time | 已读 0 人", renderReadReceiptText("time", 0, true))
    }

    @Test
    fun `leaves native text unchanged when count is unknown`() {
        assertEquals("native", renderReadReceiptText("native", null, false))
    }

    @Test
    fun `placeholder suppresses automatic suffix`() {
        assertEquals(
            "time 已读 3 人",
            renderReadReceiptText("time " + READ_RECEIPTS_PLACEHOLDER, 3, true),
        )
    }
}
