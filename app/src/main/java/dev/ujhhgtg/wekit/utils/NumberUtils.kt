package dev.ujhhgtg.wekit.utils

@Suppress("NOTHING_TO_INLINE")
inline fun Long.coerceToInt(): Int =
    this.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
