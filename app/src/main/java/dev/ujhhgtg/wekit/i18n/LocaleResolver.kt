package dev.ujhhgtg.wekit.i18n

import java.util.Locale

object LocaleResolver {
    fun resolve(
        selection: LanguageSelection,
        systemLocales: List<Locale>,
    ): SupportedLocale = when (selection) {
        LanguageSelection.ENGLISH -> SupportedLocale.ENGLISH
        LanguageSelection.SIMPLIFIED_CHINESE -> SupportedLocale.SIMPLIFIED_CHINESE
        LanguageSelection.MEOW_CHINESE -> SupportedLocale.MEOW_CHINESE
        LanguageSelection.TRADITIONAL_CHINESE -> SupportedLocale.TRADITIONAL_CHINESE
        LanguageSelection.SYSTEM -> systemLocales.firstNotNullOfOrNull(::mapSystemLocale)
            ?: SupportedLocale.ENGLISH
    }

    private fun mapSystemLocale(locale: Locale): SupportedLocale? = when (locale.language) {
        "en" -> SupportedLocale.ENGLISH
        "zh" -> when {
            locale.script.equals("Hant", ignoreCase = true) -> SupportedLocale.TRADITIONAL_CHINESE
            locale.script.equals("Hans", ignoreCase = true) -> SupportedLocale.SIMPLIFIED_CHINESE
            locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") ->
                SupportedLocale.TRADITIONAL_CHINESE
            else -> SupportedLocale.SIMPLIFIED_CHINESE
        }
        else -> null
    }
}
