package dev.ujhhgtg.wekit.i18n

import android.text.SpannableStringBuilder
import android.text.Spanned

object MeowTextTransformer {
    private const val SUFFIX = "喵"

    fun transform(text: String): String = insertionIndex(text)?.let { index ->
        text.substring(0, index) + suffixAt(text, index) + text.substring(index)
    } ?: text

    fun transform(text: CharSequence): CharSequence {
        val index = insertionIndex(text) ?: return text
        val suffix = suffixAt(text, index)
        return if (text is Spanned) {
            SpannableStringBuilder(text).insert(index, suffix)
        } else {
            text.subSequence(0, index).toString() + suffix + text.subSequence(index, text.length)
        }
    }

    private fun suffixAt(text: CharSequence, index: Int): String {
        if (index == 0) return SUFFIX
        val preceding = Character.codePointBefore(text, index)
        return if (preceding.isWesternBoundary()) " $SUFFIX" else SUFFIX
    }

    private fun insertionIndex(text: CharSequence): Int? {
        var nonWhitespaceEnd = text.length
        while (nonWhitespaceEnd > 0) {
            val codePoint = Character.codePointBefore(text, nonWhitespaceEnd)
            if (!codePoint.isWhitespace()) break
            nonWhitespaceEnd -= Character.charCount(codePoint)
        }
        if (nonWhitespaceEnd == 0) return null

        var insertionIndex = text.length
        while (insertionIndex > 0) {
            val codePoint = Character.codePointBefore(text, insertionIndex)
            if (!codePoint.isPunctuation() && !codePoint.isWhitespace()) break
            insertionIndex -= Character.charCount(codePoint)
        }
        if (insertionIndex > 0 && text[insertionIndex - 1] == SUFFIX.single()) return null
        return insertionIndex
    }

    private fun Int.isPunctuation(): Boolean = when (Character.getType(this)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true

        else -> false
    }

    private fun Int.isWhitespace(): Boolean =
        Character.isWhitespace(this) || Character.isSpaceChar(this)

    private fun Int.isWesternBoundary(): Boolean =
        this == '°'.code ||
            this in '0'.code..'9'.code ||
            this in 'A'.code..'Z'.code ||
            this in 'a'.code..'z'.code
}
