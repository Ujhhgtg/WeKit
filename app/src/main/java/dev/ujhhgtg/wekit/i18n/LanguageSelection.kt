package dev.ujhhgtg.wekit.i18n

import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R

enum class LanguageSelection(
    val storedValue: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM("system", R.string.language_follow_system),
    ENGLISH("en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_simplified_chinese),
    MEOW_CHINESE("zh-Hans-x-meow", R.string.language_meow_chinese),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_traditional_chinese),
    ;

    companion object {
        fun fromStored(value: String?): LanguageSelection =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}
