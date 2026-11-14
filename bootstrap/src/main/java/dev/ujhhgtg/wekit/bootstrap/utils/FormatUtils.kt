package dev.ujhhgtg.wekit.bootstrap.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatEpoch(epochMs: Long, includeDate: Boolean = false): String = DateTimeFormatter
    .ofPattern(if (includeDate) "yyyy/MM/dd HH:mm:ss" else "HH:mm:ss")
    .withZone(ZoneId.of("Asia/Shanghai")).withLocale(Locale.CHINA).format(Instant.ofEpochMilli(epochMs))
