package dev.ujhhgtg.wekit.features.items.beautify

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class ConversationListPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val avatarRadiusDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    COMFORT_CARD(14, 10, 4, 12, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
    COMPACT_ROUNDED(10, 6, 2, 10, 0xFFF9FBFA.toInt(), 0xFF272928.toInt()),
    MINIMAL_LIST(6, 0, 0, 8, 0xFFFCFCFC.toInt(), 0xFF232323.toInt()),
}

internal data class ConversationListPalette(
    val backgroundColor: Int,
    val strokeColor: Int,
    val unreadBackgroundColor: Int,
    val rippleColor: Int,
)

internal data class AvatarCandidateMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val depth: Int,
)

internal fun conversationListPalette(
    preset: ConversationListPreset,
    isDark: Boolean,
): ConversationListPalette = ConversationListPalette(
    backgroundColor = if (isDark) preset.darkBackgroundColor else preset.lightBackgroundColor,
    strokeColor = if (isDark) 0x22FFFFFF else 0x16161D1C,
    unreadBackgroundColor = if (isDark) 0xFF253E37.toInt() else 0xFFEAF8F2.toInt(),
    rippleColor = if (isDark) 0x2AFFFFFF else 0x18006A62,
)

internal fun isUnreadConversation(unreadCount: Int): Boolean = unreadCount > 0

internal fun shouldHideConversationDivider(
    hideConversationListDividersEnabled: Boolean,
    beautifyConversationListEnabled: Boolean,
    beautifyHideDividersEnabled: Boolean,
): Boolean = hideConversationListDividersEnabled ||
    (beautifyConversationListEnabled && beautifyHideDividersEnabled)

internal fun avatarCandidateScore(
    candidate: AvatarCandidateMetrics,
    density: Float,
): Float? {
    if (density <= 0f || candidate.depth !in 0..8 || candidate.widthPx <= 0 || candidate.heightPx <= 0) {
        return null
    }

    val shortSideDp = min(candidate.widthPx, candidate.heightPx) / density
    val longSideDp = max(candidate.widthPx, candidate.heightPx) / density
    if (shortSideDp < 32f || longSideDp > 84f) return null

    val shapeDeviation = abs(candidate.widthPx - candidate.heightPx).toFloat() /
        max(candidate.widthPx, candidate.heightPx)
    if (shapeDeviation > 0.22f) return null

    return candidate.widthPx.toFloat() * candidate.heightPx - shapeDeviation
}

internal fun dpToPx(dp: Int, density: Float): Int = (dp * density).toInt()
