package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeSidePanelCityIndexTest {

    private val cities = listOf(
        WeatherCity("CN", "北京", "北京", null, "101010100"),
        WeatherCity("CN", "北京", "北京", "海淀", "101010200"),
        WeatherCity("CN", "广东", "广州", null, "101280101"),
        WeatherCity("HK", "香港", "香港", null, "101320101"),
        WeatherCity("TW", "台湾", "台北", null, "101340101"),
    )

    private val matcher = HomeSidePanelCityMatcher(cities)

    @Test
    fun profileMatchingNormalizesProvinceCityAndDistrictSuffixes() {
        val result = matcher.matchProfile("CN", "北京市", "北京市海淀区")

        assertEquals(
            "101010200",
            (result as WeatherCityMatchResult.Success).city.cityNum,
        )
    }

    @Test
    fun unsupportedProfileCountryReturnsExplicitFailure() {
        val result = matcher.matchProfile("US", "New York", "New York")

        assertEquals(
            WeatherCityMatchFailure.UNSUPPORTED_COUNTRY,
            (result as WeatherCityMatchResult.Error).reason,
        )
    }

    @Test
    fun profileMatchPriorityPrefersCountryProvinceAndDistrict() {
        val result = matcher.matchProfile("CN", "北京市", "海淀区")

        assertEquals(
            "101010200",
            (result as WeatherCityMatchResult.Success).city.cityNum,
        )
    }

    @Test
    fun profileMatchingReportsMissingRegionAndCitySeparately() {
        assertEquals(
            WeatherCityMatchFailure.MISSING_REGION,
            (matcher.matchProfile("CN", "", "北京") as WeatherCityMatchResult.Error).reason,
        )
        assertEquals(
            WeatherCityMatchFailure.MISSING_CITY,
            (matcher.matchProfile("CN", "北京", "") as WeatherCityMatchResult.Error).reason,
        )
    }

    @Test
    fun searchMatchesDistrictSuffixes() {
        assertEquals(
            listOf("101010200"),
            matcher.search("海淀区").map(WeatherCity::cityNum),
        )
    }

    @Test
    fun locationMatchingUsesTheSameNormalizedCityRules() {
        val result = matcher.matchLocation("广东省", "广州市")

        assertEquals(
            "101280101",
            (result as WeatherCityMatchResult.Success).city.cityNum,
        )
    }
}
