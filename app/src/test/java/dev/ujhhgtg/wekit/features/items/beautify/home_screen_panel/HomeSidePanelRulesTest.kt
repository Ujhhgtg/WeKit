package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSidePanelRulesTest {

    @Test
    fun onlySupportedWechatCountryCodesAreEligible() {
        assertTrue(isEligibleWeatherCountry("CN"))
        assertTrue(isEligibleWeatherCountry("hk"))
        assertTrue(isEligibleWeatherCountry("MO"))
        assertTrue(isEligibleWeatherCountry("TW"))
        assertFalse(isEligibleWeatherCountry("US"))
        assertFalse(isEligibleWeatherCountry(""))
    }

    @Test
    fun invalidHitokotoLengthsAndCategoriesAreRejected() {
        assertEquals(
            "长度不能为负数",
            validateHitokotoSettings(minLength = -1, maxLength = null),
        )
        assertEquals(
            "最大长度不能小于最小长度",
            validateHitokotoSettings(minLength = 20, maxLength = 10),
        )
        assertEquals(
            "至少选择一个分类",
            validateHitokotoSettings(
                minLength = null,
                maxLength = null,
                categories = emptySet(),
            ),
        )
        assertNull(
            validateHitokotoSettings(
                minLength = 10,
                maxLength = 20,
                categories = setOf("a"),
            ),
        )
    }

    @Test
    fun defaultWeatherCityIsBeijing() {
        assertEquals("CN", DEFAULT_WEATHER_CITY.countryCode)
        assertEquals("北京", DEFAULT_WEATHER_CITY.city)
        assertEquals("101010100", DEFAULT_WEATHER_CITY.cityNum)
    }
}
