package dev.ujhhgtg.wekit.i18n

import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R

enum class SupportedLocale(
    val logicalTag: String,
    val androidTag: String,
    @StringRes val labelRes: Int,
) {
    ENGLISH("en", "en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", "zh-CN", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", "zh-TW", R.string.language_traditional_chinese),
    MEOW_CHINESE("zh-CA", "zh-CA", R.string.language_meow_chinese),
}
