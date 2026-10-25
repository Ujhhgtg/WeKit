package dev.ujhhgtg.wekit.utils.strings

import java.security.MessageDigest

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex MD5; WeChat uses this form for its cache directory and file names. */
fun md5Hex(value: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val code = byte.toInt()
            append(HEX_DIGITS[(code shr 4) and 0xF])
            append(HEX_DIGITS[code and 0xF])
        }
    }
}
