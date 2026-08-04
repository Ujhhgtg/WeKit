package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun savingHitokotoSettingsImmediatelyRequestsAnotherQuote() {
        val hitokotoRepository = RecordingHitokotoRepository()
        val controller = fixtureController(hitokotoRepository = hitokotoRepository)
        controller.openHitokotoSettings()
        controller.saveHitokotoSettings(HitokotoSettings(categories = setOf("a")))
        assertEquals(1, hitokotoRepository.fetchCount)
        assertEquals(setOf("a"), hitokotoRepository.savedSettings.categories)
        assertEquals(HomeSidePanelCardMode.CONTENT, controller.uiState.value.cardMode)
        controller.close()
    }

    @Test
    fun accountBootstrapStateDoesNotBelongToAnotherAccount() {
        assertEquals(true, homeSidePanelWeatherProfileStateBelongsToAccount("wxid-a", "wxid-a"))
        assertEquals(false, homeSidePanelWeatherProfileStateBelongsToAccount("wxid-a", "wxid-b"))
        assertEquals(false, homeSidePanelWeatherProfileStateBelongsToAccount(null, "wxid-b"))
    }

    @Test
    fun switchingAccountsClearsThePreviousWeatherStateAndBootstrapsAgain() {
        val previousCity = DEFAULT_WEATHER_CITY.copy(
            province = "上海",
            city = "上海",
            cityNum = "101020100",
        )
        val weatherRepository = FixtureWeatherRepository(previousCity)
        var storedAccount: String? = "wxid-old"
        val controller = HomeSidePanelController(
            profileRepository = FixtureProfileRepository(
                profile = HomeSidePanelProfile("wxid-new", "新账号", "", HomeSidePanelStatusUiState.NoStatus),
                profileCityResult = WeatherCityMatchResult.Success(DEFAULT_WEATHER_CITY),
            ),
            weatherRepository = weatherRepository,
            hitokotoRepository = FixtureHitokotoRepository(),
            locationResolver = FixtureLocationResolver(),
            navigator = RecordingNavigator(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            weatherProfileAccount = { storedAccount },
            setWeatherProfileAccount = { storedAccount = it },
        )

        controller.startPreload()

        assertEquals(1, weatherRepository.resetCount)
        assertEquals("wxid-new", storedAccount)
        assertEquals(DEFAULT_WEATHER_CITY.cityNum, weatherRepository.selectedCity().cityNum)
        controller.close()
    }

    @Test
    fun failedIdentityReadDoesNotClearTheStoredWeatherState() {
        val previousCity = DEFAULT_WEATHER_CITY.copy(
            province = "上海",
            city = "上海",
            cityNum = "101020100",
        )
        val weatherRepository = FixtureWeatherRepository(previousCity)
        var storedAccount: String? = "wxid-existing"
        val controller = HomeSidePanelController(
            profileRepository = FixtureProfileRepository(
                profile = HomeSidePanelProfile("", "微信用户", "", HomeSidePanelStatusUiState.Error("获取失败")),
                profileCityResult = WeatherCityMatchResult.Success(DEFAULT_WEATHER_CITY),
            ),
            weatherRepository = weatherRepository,
            hitokotoRepository = FixtureHitokotoRepository(),
            locationResolver = FixtureLocationResolver(),
            navigator = RecordingNavigator(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            weatherProfileAccount = { storedAccount },
            setWeatherProfileAccount = { storedAccount = it },
        )

        controller.startPreload()

        assertEquals(0, weatherRepository.resetCount)
        assertEquals("wxid-existing", storedAccount)
        assertEquals(previousCity.cityNum, weatherRepository.selectedCity().cityNum)
        controller.close()
    }

    @Test
    fun slowFullProfileReadDoesNotBlockHitokotoPreload() {
        val identityGate = CompletableDeferred<Unit>()
        val hitokotoRepository = RecordingHitokotoRepository()
        val controller = HomeSidePanelController(
            profileRepository = SlowIdentityProfileRepository(identityGate),
            weatherRepository = FixtureWeatherRepository(),
            hitokotoRepository = hitokotoRepository,
            locationResolver = FixtureLocationResolver(),
            navigator = RecordingNavigator(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            weatherProfileAccount = { "wxid" },
            setWeatherProfileAccount = {},
        )

        controller.startPreload()

        assertEquals(1, hitokotoRepository.fetchCount)
        identityGate.complete(Unit)
        controller.close()
    }

    @Test
    fun locationAndSearchCallbacksHaveFreshnessGuards() {
        assertEquals(false, homeSidePanelShouldStartLocationDetection(actionInProgress = true))
        assertEquals(true, homeSidePanelShouldStartLocationDetection(actionInProgress = false))
        assertEquals(true, homeSidePanelShouldPublishWeatherSearch("上海", "上海"))
        assertEquals(false, homeSidePanelShouldPublishWeatherSearch("上海", "北京"))
    }

    private fun fixtureController(
        profileCityResult: WeatherCityMatchResult = WeatherCityMatchResult.Success(DEFAULT_WEATHER_CITY),
        status: HomeSidePanelStatusUiState = HomeSidePanelStatusUiState.NoStatus,
        navigator: RecordingNavigator = RecordingNavigator(),
        hitokotoRepository: HomeSidePanelHitokotoRepository = FixtureHitokotoRepository(),
    ): HomeSidePanelController {
        val profileRepository = FixtureProfileRepository(
            profile = HomeSidePanelProfile("wxid", "昵称", "", status),
            profileCityResult = profileCityResult,
        )
        val weatherRepository = FixtureWeatherRepository()
        val locationResolver = FixtureLocationResolver()
        return HomeSidePanelController(
            profileRepository = profileRepository,
            weatherRepository = weatherRepository,
            hitokotoRepository = hitokotoRepository,
            locationResolver = locationResolver,
            navigator = navigator,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            weatherProfileAccount = { null },
            setWeatherProfileAccount = {},
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
    override suspend fun loadAccountId(): String = profile.wxId

    override suspend fun loadIdentity(): HomeSidePanelProfile = profile

    override suspend fun refreshStatus(): HomeSidePanelStatusUiState = profile.status

    override suspend fun readWeatherCityFromProfile(): WeatherCityMatchResult = profileCityResult
}

private class SlowIdentityProfileRepository(
    private val identityGate: CompletableDeferred<Unit>,
) : HomeSidePanelProfileRepository(
    statusReader = object : HomeSidePanelTextStatusReader {
        override fun read(wxId: String): HomeSidePanelStatusUiState = HomeSidePanelStatusUiState.NoStatus
    },
    cityIndex = UnusedControllerCityIndex,
) {
    override suspend fun loadAccountId(): String = "wxid"

    override suspend fun loadIdentity(): HomeSidePanelProfile {
        identityGate.await()
        return HomeSidePanelProfile("wxid", "昵称", "", HomeSidePanelStatusUiState.NoStatus)
    }

    override suspend fun readWeatherCityFromProfile(): WeatherCityMatchResult =
        WeatherCityMatchResult.Success(DEFAULT_WEATHER_CITY)
}

private class FixtureWeatherRepository(
    initialCity: WeatherCity = DEFAULT_WEATHER_CITY,
) : HomeSidePanelWeatherRepository {
    private var city = initialCity
    var resetCount = 0

    override suspend fun loadCached(): WeatherSnapshot? = null

    override suspend fun refresh(city: WeatherCity): WeatherResult = WeatherResult.Success(
        WeatherSnapshot(city, "0", "20", "20", "25", "15", "50", "2", "", 0L),
    )

    override suspend fun searchCities(query: String): List<WeatherCity> = emptyList()

    override fun selectedCity(): WeatherCity = city

    override fun selectCity(city: WeatherCity) {
        this.city = city
    }

    override fun resetForAccount() {
        resetCount += 1
        city = DEFAULT_WEATHER_CITY
    }
}

private class FixtureHitokotoRepository : HomeSidePanelHitokotoRepository {
    override fun loadSettings(): HitokotoSettings = HitokotoSettings()

    override fun saveSettings(settings: HitokotoSettings) = Unit

    override suspend fun loadCached(): HitokotoSnapshot? = null

    override suspend fun preload(): HitokotoResult = HitokotoResult.Error("fixture", null)

    override suspend fun fetchRandom(): HitokotoResult = HitokotoResult.Error("fixture", null)
}

private class RecordingHitokotoRepository : HomeSidePanelHitokotoRepository {
    var fetchCount = 0
    var savedSettings = HitokotoSettings()

    override fun loadSettings(): HitokotoSettings = savedSettings

    override fun saveSettings(settings: HitokotoSettings) {
        savedSettings = settings
    }

    override suspend fun loadCached(): HitokotoSnapshot? = null

    override suspend fun preload(): HitokotoResult = fetchRandom()

    override suspend fun fetchRandom(): HitokotoResult {
        fetchCount += 1
        return HitokotoResult.Error("fixture", null)
    }
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
