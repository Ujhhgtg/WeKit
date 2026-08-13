package dev.ujhhgtg.wekit.features.items.chat

const val READ_RECEIPTS_PLACEHOLDER = $$"$readReceipts"

/**
 * Renders the read-receipt portion of a message-time string without depending on Android state.
 *
 * An active enhancement template owns the placeholder location. Without a placeholder, or when
 * the enhancement is inactive, a known count is appended to the supplied base text instead.
 */
fun renderReadReceiptText(
    templateOrNativeText: String,
    localizedReadText: String?,
    enhancementActive: Boolean,
): String {
    val hasPlaceholder = templateOrNativeText.contains(READ_RECEIPTS_PLACEHOLDER)
    if (enhancementActive && hasPlaceholder) {
        return templateOrNativeText.replace(
            READ_RECEIPTS_PLACEHOLDER,
            localizedReadText.orEmpty(),
        )
    }

    return localizedReadText?.let { "$templateOrNativeText | $it" } ?: templateOrNativeText
}
