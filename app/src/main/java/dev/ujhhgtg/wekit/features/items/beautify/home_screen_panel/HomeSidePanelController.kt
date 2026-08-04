package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.Manifest
import android.app.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class HomeSidePanelController(
    private val profileRepository: HomeSidePanelProfileRepository,
    private val weatherRepository: HomeSidePanelWeatherRepository,
    private val hitokotoRepository: HomeSidePanelHitokotoRepository,
    private val locationResolver: HomeSidePanelLocationResolver,
    private val navigator: HomeSidePanelNavigator,
    private val scope: CoroutineScope,
    private val weatherProfileAccount: () -> String? = {
        HomeSidePanelPreferences.weatherProfileAccount
    },
    private val setWeatherProfileAccount: (String?) -> Unit = {
        HomeSidePanelPreferences.weatherProfileAccount = it
    },
) {

    private val started = AtomicBoolean()
    private var pendingLocationPermission = false
    private var locationJob: Job? = null
    private val _weatherMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _uiState = MutableStateFlow(
        HomeSidePanelUiState(
            profile = HomeSidePanelProfile(
                wxId = "",
                nickname = "微信用户",
                avatarUrl = "",
                status = HomeSidePanelStatusUiState.Loading,
            ),
            weather = WeatherUiState.Loading,
            weatherSettings = WeatherSettingsUiState(selectedCity = weatherRepository.selectedCity()),
            hitokoto = HitokotoUiState.Loading,
            hitokotoSettings = hitokotoRepository.loadSettings(),
            cardMode = HomeSidePanelCardMode.CONTENT,
        ),
    )

    val uiState: StateFlow<HomeSidePanelUiState> = _uiState.asStateFlow()
    val weatherMessages: SharedFlow<String> = _weatherMessages.asSharedFlow()

    fun startPreload() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            loadIdentity()
        }
        scope.launch {
            loadCachedHitokoto()
            fetchHitokotoInternal(preload = true)
        }
        scope.launch {
            val accountId = loadAccountId()
            prepareWeatherAccount(accountId)
            loadCachedWeather()
            initializeWeatherCityFromProfile(accountId)
            refreshWeatherInternal()
        }
    }

    fun onPanelOpened() {
        scope.launch { loadIdentity() }
    }

    fun refreshStatus() {
        scope.launch {
            val status = profileRepository.refreshStatus()
            _uiState.update { state ->
                state.copy(profile = state.profile.copy(status = status))
            }
        }
    }

    fun refreshWeather() {
        scope.launch { refreshWeatherInternal() }
    }

    fun readWeatherFromProfile() {
        scope.launch {
            setWeatherSettingsProgress(true)
            val result = profileRepository.readWeatherCityFromProfile()
            when (result) {
                is WeatherCityMatchResult.Success -> {
                    weatherRepository.selectCity(result.city)
                    updateWeatherSettings(
                        selectedCity = result.city,
                        message = null,
                        actionInProgress = false,
                    )
                    setWeatherProfileAccount(_uiState.value.profile.wxId)
                    refreshWeatherInternal()
                }

                is WeatherCityMatchResult.Error -> {
                    updateWeatherSettings(
                        message = result.reason.message,
                        actionInProgress = false,
                    )
                    publishWeatherMessage(result.reason.message)
                    setWeatherProfileAccount(_uiState.value.profile.wxId)
                }
            }
        }
    }

    fun searchWeatherCities(query: String) {
        _uiState.update { state ->
            state.copy(weatherSettings = state.weatherSettings.copy(searchQuery = query))
        }
        scope.launch {
            val results = weatherRepository.searchCities(query)
            if (homeSidePanelShouldPublishWeatherSearch(_uiState.value.weatherSettings.searchQuery, query)) {
                _uiState.update { state ->
                    state.copy(weatherSettings = state.weatherSettings.copy(searchResults = results))
                }
            }
        }
    }

    fun selectWeatherCity(city: WeatherCity) {
        weatherRepository.selectCity(city)
        updateWeatherSettings(selectedCity = city, message = null, searchResults = emptyList())
        scope.launch { refreshWeatherInternal() }
    }

    fun detectWeatherLocation(activity: Activity) {
        if (!homeSidePanelShouldStartLocationDetection(_uiState.value.weatherSettings.actionInProgress)) return
        setWeatherSettingsProgress(true)
        locationJob?.cancel()
        locationJob = scope.launch {
            when (val resolution = locationResolver.resolve(activity)) {
                LocationResolution.NeedPermission -> {
                    pendingLocationPermission = true
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                        HOME_SIDE_PANEL_LOCATION_REQUEST_CODE,
                    )
                }

                else -> applyLocationResolution(resolution)
            }
        }
    }

    fun resumePendingLocationDetection(activity: Activity) {
        if (!pendingLocationPermission) return
        if (locationResolver.hasCoarsePermission(activity)) {
            pendingLocationPermission = false
            setWeatherSettingsProgress(false)
            detectWeatherLocation(activity)
        } else {
            pendingLocationPermission = false
            val message = "定位权限已拒绝，仍可搜索或手动选择城市"
            updateWeatherSettings(
                message = message,
                actionInProgress = false,
            )
            publishWeatherMessage(message)
        }
    }

    fun openWeatherSettings() {
        _uiState.update { it.copy(cardMode = HomeSidePanelCardMode.WEATHER_SETTINGS) }
    }

    fun openHitokotoSettings() {
        _uiState.update { it.copy(cardMode = HomeSidePanelCardMode.HITOKOTO_SETTINGS) }
    }

    fun closeCardSettings() {
        _uiState.update { it.copy(cardMode = HomeSidePanelCardMode.CONTENT) }
    }

    fun consumeSettingsBack(): Boolean {
        if (_uiState.value.cardMode == HomeSidePanelCardMode.CONTENT) return false
        closeCardSettings()
        return true
    }

    fun fetchAnotherHitokoto() {
        scope.launch { fetchHitokotoInternal(preload = false) }
    }

    fun saveHitokotoSettings(settings: HitokotoSettings) {
        try {
            hitokotoRepository.saveSettings(settings)
            _uiState.update {
                it.copy(
                    hitokotoSettings = settings,
                    cardMode = HomeSidePanelCardMode.CONTENT,
                )
            }
            scope.launch { fetchHitokotoInternal(preload = false) }
        } catch (error: IllegalArgumentException) {
            _uiState.update { state ->
                state.copy(
                    hitokoto = HitokotoUiState.Error(
                        message = error.message ?: "一言设置无效",
                        cached = (state.hitokoto as? HitokotoUiState.Ready)?.snapshot,
                    ),
                )
            }
        }
    }

    fun runShortcut(shortcut: HomeSidePanelShortcut) {
        navigator.closePanel()
        navigator.openShortcut(shortcut)
    }

    fun close() {
        scope.coroutineContext.cancel()
    }

    private suspend fun loadCachedWeather() {
        weatherRepository.loadCached()?.let { snapshot ->
            _uiState.update { state ->
                state.copy(
                    weather = WeatherUiState.Ready(snapshot),
                    weatherSettings = state.weatherSettings.copy(selectedCity = snapshot.city),
                )
            }
        }
    }

    private suspend fun loadCachedHitokoto() {
        hitokotoRepository.loadCached()?.let { snapshot ->
            _uiState.update { state ->
                state.copy(hitokoto = HitokotoUiState.Ready(snapshot))
            }
        }
    }

    private suspend fun loadIdentity() {
        val profile = try {
            profileRepository.loadIdentity()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            HomeSidePanelProfile(
                wxId = "",
                nickname = "微信用户",
                avatarUrl = "",
                status = HomeSidePanelStatusUiState.Error("获取失败"),
            )
        }
        _uiState.update { it.copy(profile = profile) }
    }

    private suspend fun loadAccountId(): String = try {
        profileRepository.loadAccountId()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        ""
    }

    private suspend fun initializeWeatherCityFromProfile(accountId: String) {
        if (accountId.isBlank()) return
        if (homeSidePanelWeatherProfileStateBelongsToAccount(weatherProfileAccount(), accountId)) return
        when (val result = profileRepository.readWeatherCityFromProfile()) {
            is WeatherCityMatchResult.Success -> {
                weatherRepository.selectCity(result.city)
                updateWeatherSettings(selectedCity = result.city, message = null)
            }

            is WeatherCityMatchResult.Error -> {
                updateWeatherSettings(message = result.reason.message)
                publishWeatherMessage(result.reason.message)
            }
        }
        setWeatherProfileAccount(accountId)
    }

    private suspend fun prepareWeatherAccount(accountId: String) {
        if (accountId.isBlank()) return
        if (homeSidePanelWeatherProfileStateBelongsToAccount(weatherProfileAccount(), accountId)) return
        weatherRepository.resetForAccount()
        _uiState.update { state ->
            state.copy(weatherSettings = state.weatherSettings.copy(selectedCity = DEFAULT_WEATHER_CITY))
        }
    }

    private suspend fun refreshWeatherInternal() {
        val current = _uiState.value.weather
        _uiState.update {
            it.copy(
                weather = when (current) {
                    is WeatherUiState.Ready -> current.copy(refreshing = true)
                    else -> WeatherUiState.Loading
                },
            )
        }
        val result = weatherRepository.refresh(weatherRepository.selectedCity())
        _uiState.update { state ->
            state.copy(
                weather = when (result) {
                    is WeatherResult.Success -> WeatherUiState.Ready(result.snapshot)
                    is WeatherResult.Error -> WeatherUiState.Error(result.message, result.cached)
                },
                weatherSettings = state.weatherSettings.copy(
                    selectedCity = weatherRepository.selectedCity(),
                    actionInProgress = false,
                ),
            )
        }
        if (result is WeatherResult.Error) publishWeatherMessage(result.message)
    }

    private suspend fun fetchHitokotoInternal(preload: Boolean) {
        val current = _uiState.value.hitokoto
        _uiState.update {
            it.copy(
                hitokoto = when (current) {
                    is HitokotoUiState.Ready -> current.copy(refreshing = true)
                    else -> HitokotoUiState.Loading
                },
            )
        }
        val result = if (preload) hitokotoRepository.preload() else hitokotoRepository.fetchRandom()
        _uiState.update { state ->
            state.copy(
                hitokoto = when (result) {
                    is HitokotoResult.Success -> HitokotoUiState.Ready(result.snapshot)
                    is HitokotoResult.Error -> HitokotoUiState.Error(result.message, result.cached)
                },
            )
        }
    }

    private suspend fun applyLocationResolution(resolution: LocationResolution) {
        when (resolution) {
            is LocationResolution.Success -> {
                weatherRepository.selectCity(resolution.city)
                updateWeatherSettings(
                    selectedCity = resolution.city,
                    message = null,
                    actionInProgress = false,
                )
                refreshWeatherInternal()
            }

            LocationResolution.NeedPermission -> Unit
            else -> {
                val message = locationResolutionMessage(resolution)
                updateWeatherSettings(
                    message = message,
                    actionInProgress = false,
                )
                publishWeatherMessage(message)
            }
        }
    }

    private fun setWeatherSettingsProgress(progress: Boolean) {
        _uiState.update { it.copy(weatherSettings = it.weatherSettings.copy(actionInProgress = progress)) }
    }

    private fun updateWeatherSettings(
        selectedCity: WeatherCity? = null,
        searchResults: List<WeatherCity>? = null,
        message: String? = null,
        actionInProgress: Boolean? = null,
    ) {
        _uiState.update { state ->
            state.copy(
                weatherSettings = state.weatherSettings.copy(
                    selectedCity = selectedCity ?: state.weatherSettings.selectedCity,
                    searchResults = searchResults ?: state.weatherSettings.searchResults,
                    message = message,
                    actionInProgress = actionInProgress ?: state.weatherSettings.actionInProgress,
                ),
            )
        }
    }

    private fun publishWeatherMessage(message: String) {
        _weatherMessages.tryEmit(message)
    }

}

internal const val HOME_SIDE_PANEL_LOCATION_REQUEST_CODE = 0x574B
