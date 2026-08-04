package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HomeSidePanelControllerRulesTest {

    @Test
    fun profileCityFailureKeepsBeijingAndPublishesTheExactMessage() {
        val controller = fixtureController(
            profileCityResult = WeatherCityMatchResult.Error(WeatherCityMatchFailure.UNSUPPORTED_COUNTRY),
        )
        controller.startPreload()
        assertEquals("101010100", controller.uiState.value.weatherSettings.selectedCity.cityNum)
        assertEquals("不支持的资料地区", controller.uiState.value.weatherSettings.message)
        controller.close()
    }

    @Test
    fun statusFailureIsNeverConvertedToOnline() {
        val controller = fixtureController(
            status = HomeSidePanelStatusUiState.Error("boom"),
        )
        controller.startPreload()
        assertInstanceOf(HomeSidePanelStatusUiState.Error::class.java, controller.uiState.value.profile.status)
        controller.close()
    }

    @Test
    fun cardModesAndShortcutNavigationAreDeterministic() {
        val navigator = RecordingNavigator()
        val controller = fixtureController(navigator = navigator)
        controller.openWeatherSettings()
        assertEquals(HomeSidePanelCardMode.WEATHER_SETTINGS, controller.uiState.value.cardMode)
        controller.openHitokotoSettings()
        assertEquals(HomeSidePanelCardMode.HITOKOTO_SETTINGS, controller.uiState.value.cardMode)
        controller.runShortcut(HomeSidePanelShortcut.SCAN)
        assertEquals(listOf("close", "SCAN"), navigator.events)
        controller.close()
    }

    private fun fixtureController(
        profileCityResult: WeatherCityMatchResult = WeatherCityMatchResult.Success(DEFAULT_WEATHER_CITY),
        status: HomeSidePanelStatusUiState = HomeSidePanelStatusUiState.NoStatus,
        navigator: RecordingNavigator = RecordingNavigator(),
    ): HomeSidePanelController {
        val profileRepository = FixtureProfileRepository(
            profile = HomeSidePanelProfile("wxid", "昵称", "", status),
            profileCityResult = profileCityResult,
        )
        val weatherRepository = FixtureWeatherRepository()
        val hitokotoRepository = FixtureHitokotoRepository()
        val locationResolver = FixtureLocationResolver()
        return HomeSidePanelController(
            profileRepository = profileRepository,
            weatherRepository = weatherRepository,
            hitokotoRepository = hitokotoRepository,
            locationResolver = locationResolver,
            navigator = navigator,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            weatherProfileInitialized = { false },
            setWeatherProfileInitialized = {},
        )
    }
}

private class FixtureProfileRepository(
    private val profile: HomeSidePanelProfile,
    private val profileCityResult: WeatherCityMatchResult,
) : HomeSidePanelProfileRepository(
    statusReader = object : HomeSidePanelTextStatusReader {
        override fun read(wxId: String): HomeSidePanelStatusUiState = profile.status
    },
    cityIndex = UnusedControllerCityIndex,
) {
    override suspend fun loadIdentity(): HomeSidePanelProfile = profile

    override suspend fun refreshStatus(): HomeSidePanelStatusUiState = profile.status

    override suspend fun readWeatherCityFromProfile(): WeatherCityMatchResult = profileCityResult
}

private class FixtureWeatherRepository : HomeSidePanelWeatherRepository {
    override suspend fun loadCached(): WeatherSnapshot? = null

    override suspend fun refresh(city: WeatherCity): WeatherResult = WeatherResult.Success(
        WeatherSnapshot(city, "0", "20", "20", "25", "15", "50", "2", "", 0L),
    )

    override suspend fun searchCities(query: String): List<WeatherCity> = emptyList()

    override fun selectedCity(): WeatherCity = DEFAULT_WEATHER_CITY

    override fun selectCity(city: WeatherCity) = Unit
}

private class FixtureHitokotoRepository : HomeSidePanelHitokotoRepository {
    override fun loadSettings(): HitokotoSettings = HitokotoSettings()

    override fun saveSettings(settings: HitokotoSettings) = Unit

    override suspend fun loadCached(): HitokotoSnapshot? = null

    override suspend fun preload(): HitokotoResult = HitokotoResult.Error("fixture", null)

    override suspend fun fetchRandom(): HitokotoResult = HitokotoResult.Error("fixture", null)
}

private class FixtureLocationResolver : HomeSidePanelLocationResolver {
    override fun hasCoarsePermission(activity: Activity): Boolean = false

    override suspend fun resolve(activity: Activity): LocationResolution = LocationResolution.NeedPermission
}

private class RecordingNavigator : HomeSidePanelNavigator {
    val events = mutableListOf<String>()

    override fun closePanel() {
        events += "close"
    }

    override fun openShortcut(shortcut: HomeSidePanelShortcut) {
        events += shortcut.name
    }
}

private object UnusedControllerCityIndex : HomeSidePanelCityIndex {
    override suspend fun search(query: String): List<WeatherCity> = emptyList()

    override suspend fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult = WeatherCityMatchResult.Error(WeatherCityMatchFailure.NO_MATCH)

    override suspend fun matchLocation(province: String, city: String): WeatherCityMatchResult =
        WeatherCityMatchResult.Error(WeatherCityMatchFailure.NO_MATCH)
}
