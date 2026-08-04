package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeSidePanelLocationResolverTest {

    @Test
    fun missingPermissionRequiresAnExplicitRequest() = runBlocking {
        assertEquals(
            LocationResolution.NeedPermission,
            FixtureLocationResolver(permissionGranted = false).resolve(),
        )
    }

    @Test
    fun locationFailuresHaveDistinctChineseMessages() {
        assertEquals("请先开启系统定位服务", locationResolutionMessage(LocationResolution.LocationDisabled))
        assertEquals("定位超时，请重试或手动选择城市", locationResolutionMessage(LocationResolution.Timeout))
        assertEquals("无法将当前位置转换为城市", locationResolutionMessage(LocationResolution.GeocoderFailed))
        assertEquals("天气城市库中找不到当前城市", locationResolutionMessage(LocationResolution.CityNotFound))
    }

    @Test
    fun geocodedCityIsMatchedAgainstTheLocalIndex() = runBlocking {
        val result = FixtureLocationResolver(
            permissionGranted = true,
            province = "北京市",
            city = "北京市",
        ).resolve()
        assertEquals("101010100", (result as LocationResolution.Success).city.cityNum)
    }

    private class FixtureLocationResolver(
        private val permissionGranted: Boolean,
        private val province: String = "",
        private val city: String = "",
    ) {
        suspend fun resolve(): LocationResolution {
            if (!permissionGranted) return LocationResolution.NeedPermission
            val result = HomeSidePanelCityMatcher(
                cities = listOf(DEFAULT_WEATHER_CITY),
                transliterator = CityQueryTransliterator { it },
            ).matchLocation(province, city)
            return when (result) {
                is WeatherCityMatchResult.Success -> LocationResolution.Success(result.city)

                is WeatherCityMatchResult.Error -> LocationResolution.CityNotFound
            }
        }
    }
}
