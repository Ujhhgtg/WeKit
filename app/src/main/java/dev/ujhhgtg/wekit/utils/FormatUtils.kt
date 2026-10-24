@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

fun formatBytesSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()

    // Format to 2 decimal places
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return "%.2f %s".format(value, units[digitGroups])
}

inline fun formatEpoch(epochMs: Long, includeDate: Boolean = false): String =
    formatEpoch(epochMs, if (includeDate) "yyyy/MM/dd HH:mm:ss" else "HH:mm:ss")

fun formatEpoch(epochMs: Long, pattern: String): String {
    val formatter =
        DateTimeFormatter.ofPattern(pattern)
            .withZone(ZoneId.of("Asia/Shanghai"))
            .withLocale(Locale.CHINA)

    return formatter.format(Instant.ofEpochMilli(epochMs))
}
