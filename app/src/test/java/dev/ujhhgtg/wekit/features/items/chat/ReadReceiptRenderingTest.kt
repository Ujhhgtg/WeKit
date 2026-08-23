package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadReceiptRenderingTest {

    @Test
    fun `replaces placeholder in active template with localized read text`() {
        assertEquals(
            "12:00 · 已讀 2 人",
            renderReadReceiptText(
                $$"12:00 · $readReceipts",
                "已讀 2 人",
                enhancementActive = true,
            ),
        )
    }

    @Test
    fun `appends localized read text to active template without placeholder`() {
        assertEquals("time | Read by 3", renderReadReceiptText("time", "Read by 3", true))
    }

    @Test
    fun `appends localized read text to native text when enhancement is inactive`() {
        assertEquals(
            "12:00 | Read by 0",
            renderReadReceiptText("12:00", "Read by 0", enhancementActive = false),
        )
    }

    @Test
    fun `clears placeholder when localized read text is unknown`() {
        assertEquals(
            "time ·  · type",
            renderReadReceiptText("time · " + READ_RECEIPTS_PLACEHOLDER + " · type", null, true),
        )
    }

    @Test
    fun `renders localized zero`() {
        assertEquals("time | Read by 0", renderReadReceiptText("time", "Read by 0", true))
    }

    @Test
    fun `leaves native text unchanged when localized read text is unknown`() {
        assertEquals(
            "12:00",
            renderReadReceiptText("12:00", null, enhancementActive = false),
        )
    }

    @Test
    fun `placeholder suppresses automatic suffix`() {
        assertEquals(
            "time Read by 3",
            renderReadReceiptText("time " + READ_RECEIPTS_PLACEHOLDER, "Read by 3", true),
        )
    }

    @Test
    fun `uses retained native text when rerendering after locale changes`() {
        val renderedInTraditionalChinese =
            renderReadReceiptText("12:00", "已讀 2 人", enhancementActive = false)
        val nativeText = readReceiptNativeText(renderedInTraditionalChinese, "12:00")

        assertEquals(
            "12:00 | Read by 2",
            renderReadReceiptText(nativeText, "Read by 2", enhancementActive = false),
        )
        assertEquals(
            "12:00",
            renderReadReceiptText(nativeText, null, enhancementActive = false),
        )
    }
}
