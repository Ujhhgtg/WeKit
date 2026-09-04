package dev.ujhhgtg.wekit.features.items.contacts

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan

data class GroupMemberNicknameRange(
    val start: Int,
    val endExclusive: Int,
)

/** Locates the nickname while excluding text injected by the role and real-name features. */
fun CharSequence.groupMemberNicknameRange(): GroupMemberNicknameRange {
    var start = 0
    var endExclusive = length

    if (this is Spanned) {
        val roleSpan = getSpans(0, length, ReplacementSpan::class.java)
            .firstOrNull { getSpanStart(it) == 0 }
        if (roleSpan != null) {
            start = getSpanEnd(roleSpan)
            if (start < length && this[start] == ' ') start++
        }

        val realNameSpan = getSpans(0, length, ForegroundColorSpan::class.java)
            .filter { getSpanEnd(it) == length && getSpanStart(it) > start }
            .maxByOrNull { getSpanStart(it) }
        if (realNameSpan != null) {
            endExclusive = getSpanStart(realNameSpan)
        }
    }

    return GroupMemberNicknameRange(start, endExclusive.coerceAtLeast(start))
}
