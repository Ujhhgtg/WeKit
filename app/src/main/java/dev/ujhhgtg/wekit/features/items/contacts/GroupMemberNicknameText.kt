package dev.ujhhgtg.wekit.features.items.contacts

import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Marker for the role badge span injected into group-member nicknames. */
interface GroupMemberRoleSpan

data class GroupMemberNicknameRange(
    val start: Int,
    val endExclusive: Int,
)

/** Locates the nickname while excluding text injected by the role and real-name features. */
fun CharSequence.groupMemberNicknameRange(): GroupMemberNicknameRange {
    var start = 0
    var endExclusive = length

    if (this is Spanned) {
        val roleSpan = getSpans(0, length, GroupMemberRoleSpan::class.java)
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
