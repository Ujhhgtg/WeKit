package dev.ujhhgtg.wekit.i18n

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocaleResolverTest {
    @Test
    fun storedValuesRoundTripAndInvalidValuesUseSystem() {
        LanguageSelection.entries.forEach { selection ->
            assertEquals(selection, LanguageSelection.fromStored(selection.storedValue))
        }
        assertEquals(LanguageSelection.SYSTEM, LanguageSelection.fromStored(null))
        assertEquals(LanguageSelection.SYSTEM, LanguageSelection.fromStored("invalid"))
    }

    @Test
    fun manualSelectionIgnoresSystemLocales() {
        val system = listOf(Locale.forLanguageTag("zh-CN"))
        assertEquals(SupportedLocale.ENGLISH, LocaleResolver.resolve(LanguageSelection.ENGLISH, system))
        assertEquals(
            SupportedLocale.SIMPLIFIED_CHINESE,
            LocaleResolver.resolve(LanguageSelection.SIMPLIFIED_CHINESE, emptyList()),
        )
        assertEquals(
            SupportedLocale.TRADITIONAL_CHINESE,
            LocaleResolver.resolve(LanguageSelection.TRADITIONAL_CHINESE, system),
        )
        assertEquals(
            SupportedLocale.MEOW_CHINESE,
            LocaleResolver.resolve(LanguageSelection.MEOW_CHINESE, emptyList()),
        )
    }

    @Test
    fun followSystemUsesTheFirstSupportedLocale() {
        assertEquals(
            SupportedLocale.TRADITIONAL_CHINESE,
            LocaleResolver.resolve(
                LanguageSelection.SYSTEM,
                listOf(Locale.JAPAN, Locale.forLanguageTag("zh-HK"), Locale.ENGLISH),
            ),
        )
        assertEquals(
            SupportedLocale.ENGLISH,
            LocaleResolver.resolve(
                LanguageSelection.SYSTEM,
                listOf(Locale.JAPAN, Locale.US),
            ),
        )
    }

    @Test
    fun chineseScriptAndRegionMappingIsExplicit() {
        val simplified = listOf("zh-Hans", "zh-CN", "zh-SG", "zh-MY", "zh")
        val traditional = listOf("zh-Hant", "zh-TW", "zh-HK", "zh-MO")

        simplified.forEach { tag ->
            assertEquals(
                SupportedLocale.SIMPLIFIED_CHINESE,
                LocaleResolver.resolve(LanguageSelection.SYSTEM, listOf(Locale.forLanguageTag(tag))),
                tag,
            )
        }
        traditional.forEach { tag ->
            assertEquals(
                SupportedLocale.TRADITIONAL_CHINESE,
                LocaleResolver.resolve(LanguageSelection.SYSTEM, listOf(Locale.forLanguageTag(tag))),
                tag,
            )
        }
    }

    @Test
    fun unsupportedOrEmptySystemListFallsBackToEnglish() {
        assertEquals(
            SupportedLocale.ENGLISH,
            LocaleResolver.resolve(LanguageSelection.SYSTEM, listOf(Locale.JAPAN)),
        )
        assertEquals(
            SupportedLocale.ENGLISH,
            LocaleResolver.resolve(LanguageSelection.SYSTEM, emptyList()),
        )
    }
}
