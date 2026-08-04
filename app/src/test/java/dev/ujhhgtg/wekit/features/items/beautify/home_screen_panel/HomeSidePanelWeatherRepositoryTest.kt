package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class HomeSidePanelWeatherRepositoryTest {

    @Test
    fun weatherRequestUsesXiaomiLocationKeyAndRequiredParameters() {
        val url = buildWeatherUrl(BEIJING_CITY)
        assertEquals("weathercn:101010100", url.queryParameter("locationKey"))
        assertEquals("zUFJoAR2ZVrDy1vF3D07", url.queryParameter("sign"))
        assertEquals("false", url.queryParameter("isGlobal"))
        assertEquals("zh_cn", url.queryParameter("locale"))
        assertEquals("weather20151024", url.queryParameter("appKey"))
        assertEquals("5", url.queryParameter("days"))
    }

    @Test
    fun weatherJsonMapsCurrentAndDailyValues() {
        val snapshot = parseWeatherPayload(BEIJING_CITY, fixtureWeatherJson, fetchedAt = 1234L)
        assertEquals("21", snapshot.temperature)
        assertEquals("22", snapshot.feelsLike)
        assertEquals("1", snapshot.weatherCode)
        assertEquals("25", snapshot.high)
        assertEquals("16", snapshot.low)
        assertEquals(1234L, snapshot.fetchedAt)
    }

    @Test
    fun weatherCodesMapToTheirSemanticIconKinds() {
        assertEquals(WeatherIconKind.SUNNY, weatherIconKind("0"))
        assertEquals(WeatherIconKind.CLOUDY, weatherIconKind("2"))
        assertEquals(WeatherIconKind.THUNDER, weatherIconKind("4"))
        assertEquals(WeatherIconKind.SNOW, weatherIconKind("13"))
        assertEquals(WeatherIconKind.FOG, weatherIconKind("18"))
        assertEquals(WeatherIconKind.UNKNOWN, weatherIconKind("99"))
    }

    @Test
    fun repeatedWeatherRefreshesShareOneInFlightCall() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val repository = DefaultHomeSidePanelWeatherRepository(
            preferences = InMemoryHomeSidePanelWeatherPreferences(),
            cityIndex = UnusedHomeSidePanelCityIndex,
            client = OkHttpClient(),
            nowMs = { 10_000L },
            fetchPayload = {
                requestCount.incrementAndGet()
                gate.await()
                fixtureWeatherJson
            },
        )
        val refreshes = List(20) { async { repository.refresh(BEIJING_CITY) } }
        delay(50)
        assertEquals(1, requestCount.get())
        gate.complete(Unit)
        assertEquals(1, refreshes.map { it.await() }.distinct().size)
    }

    @Test
    fun refreshWithinOneSecondUsesTheCachedSnapshot() = runBlocking {
        val requestCount = AtomicInteger()
        val repository = DefaultHomeSidePanelWeatherRepository(
            preferences = InMemoryHomeSidePanelWeatherPreferences(),
            cityIndex = UnusedHomeSidePanelCityIndex,
            client = OkHttpClient(),
            nowMs = { 10_000L },
            fetchPayload = {
                requestCount.incrementAndGet()
                fixtureWeatherJson
            },
        )
        val first = repository.refresh(BEIJING_CITY)
        val second = repository.refresh(BEIJING_CITY)
        assertEquals(1, requestCount.get())
        assertEquals(first, second)
    }

    private companion object {
        val BEIJING_CITY = WeatherCity(
            countryCode = "CN",
            province = "北京市",
            city = "北京市",
            district = null,
            cityNum = "101010100",
        )

        const val fixtureWeatherJson = """
            {
              "current": {
                "temperature": {"value": "21"},
                "feelsLike": {"value": "22"},
                "humidity": {"value": "48"},
                "weather": "1",
                "wind": {"speed": {"value": "3"}},
                "pubTime": "2026-08-04T11:00:00+08:00"
              },
              "forecastDaily": {
                "temperature": {
                  "value": [{"from": "25", "to": "16"}]
                },
                "weather": {
                  "value": [{"from": "1", "to": "1"}]
                }
              }
            }
        """
    }
}

private object UnusedHomeSidePanelCityIndex : HomeSidePanelCityIndex {
    override suspend fun search(query: String): List<WeatherCity> = error("unused")

    override suspend fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult = error("unused")

    override suspend fun matchLocation(province: String, city: String): WeatherCityMatchResult =
        error("unused")
}

private class InMemoryHomeSidePanelWeatherPreferences : HomeSidePanelWeatherPreferences {
    override var selectedWeatherCity: WeatherCity = DEFAULT_WEATHER_CITY
    override var weatherLastSuccess: WeatherSnapshot? = null
}
