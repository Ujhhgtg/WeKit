package dev.ujhhgtg.wekit.features.items.chat

const val READ_RECEIPTS_PLACEHOLDER = $$"$readReceipts"
const val READ_RECEIPTS_SUFFIX = " | 已读 "

/**
 * Renders the read-receipt portion of a message-time string without depending on Android state.
 *
 * An active enhancement template owns the placeholder location. Without a placeholder, or when
 * the enhancement is inactive, a known count is appended to the supplied base text instead.
 */
fun renderReadReceiptText(
    templateOrNativeText: String,
    count: Int?,
    enhancementActive: Boolean,
): String {
    val hasPlaceholder = templateOrNativeText.contains(READ_RECEIPTS_PLACEHOLDER)
    if (enhancementActive && hasPlaceholder) {
        val rendered = count?.let { "已读 $it 人" } ?: ""
        return templateOrNativeText.replace(READ_RECEIPTS_PLACEHOLDER, rendered)
    }

    return count?.let { "$templateOrNativeText$READ_RECEIPTS_SUFFIX$it 人" }
        ?: templateOrNativeText
}
