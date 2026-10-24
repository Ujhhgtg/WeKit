package dev.ujhhgtg.wekit.utils

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
inline fun enumValueOfClass(enumClass: Class<*>, name: String): Enum<*> =
    java.lang.Enum.valueOf(enumClass as Class<out Enum<*>?>, name)
