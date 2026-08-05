package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.activity.settings.SettingsActivity
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
import dev.ujhhgtg.wekit.utils.android.showToast
import java.util.concurrent.atomic.AtomicBoolean

internal data class HomeSidePanelUiState(
    val profile: HomeSidePanelProfile,
    val weather: WeatherUiState,
    val weatherSettings: WeatherSettingsUiState,
    val hitokoto: HitokotoUiState,
    val hitokotoSettings: HitokotoSettings,
    val hideWeChatTitle: Boolean,
)

internal class HomeSidePanelState(
    private val activity: Activity,
    private val profile: HomeSidePanelProfileLoader,
    private val weather: HomeSidePanelWeather,
    private val hitokoto: HomeSidePanelHitokoto,
    private val location: HomeSidePanelLocation,
    private val scope: CoroutineScope,
    private val closePanel: ((() -> Unit)?) -> Unit,
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
            weatherSettings = WeatherSettingsUiState(selectedCity = weather.selectedCity()),
            hitokoto = HitokotoUiState.Loading,
            hitokotoSettings = hitokoto.loadSettings(),
            hideWeChatTitle = HomeSidePanelPreferences.hideWeChatTitle,
        ),
    )

    var route by mutableStateOf(HomeSidePanelRoute.HOME)
        private set

    val uiState: StateFlow<HomeSidePanelUiState> = _uiState.asStateFlow()
    val weatherMessages: SharedFlow<String> = _weatherMessages.asSharedFlow()

    fun startPreload() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            loadIdentity()
        }
        scope.launch {
            loadCachedHitokoto()
            fetchHitokotoInternal()
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
            val status = profile.refreshStatus()
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
            val result = profile.readWeatherCityFromProfile()
            when (result) {
                is WeatherCityMatchResult.Success -> {
                    weather.selectCity(result.city)
                    updateWeatherSettings(
                        selectedCity = result.city,
                        actionInProgress = false,
                    )
                    HomeSidePanelPreferences.weatherProfileAccount = _uiState.value.profile.wxId
                    refreshWeatherInternal()
                }

                is WeatherCityMatchResult.Error -> {
                    updateWeatherSettings(
                        actionInProgress = false,
                    )
                    publishWeatherMessage(result.reason.message)
                    HomeSidePanelPreferences.weatherProfileAccount = _uiState.value.profile.wxId
                }
            }
        }
    }

    fun searchWeatherCities(query: String) {
        _uiState.update { state ->
            state.copy(weatherSettings = state.weatherSettings.copy(searchQuery = query))
        }
        scope.launch {
            val results = weather.searchCities(query)
            if (_uiState.value.weatherSettings.searchQuery == query) {
                _uiState.update { state ->
                    state.copy(weatherSettings = state.weatherSettings.copy(searchResults = results))
                }
            }
        }
    }

    fun selectWeatherCity(city: WeatherCity) {
        weather.selectCity(city)
        updateWeatherSettings(selectedCity = city, searchResults = emptyList())
        scope.launch { refreshWeatherInternal() }
    }

    fun detectWeatherLocation() {
        if (_uiState.value.weatherSettings.actionInProgress) return
        setWeatherSettingsProgress(true)
        locationJob?.cancel()
        locationJob = scope.launch {
            when (val resolution = location.resolve(activity)) {
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

    fun resumePendingLocationDetection() {
        if (!pendingLocationPermission) return
        if (location.hasCoarsePermission(activity)) {
            pendingLocationPermission = false
            setWeatherSettingsProgress(false)
            detectWeatherLocation()
        } else {
            pendingLocationPermission = false
            val message = "定位权限已拒绝，仍可搜索或手动选择城市"
            updateWeatherSettings(
                actionInProgress = false,
            )
            publishWeatherMessage(message)
        }
    }

    fun openWeatherSettings() {
        route = HomeSidePanelRoute.WEATHER_SETTINGS
    }

    fun openHitokotoSettings() {
        route = HomeSidePanelRoute.HITOKOTO_SETTINGS
    }

    fun openPanelSettings() {
        route = HomeSidePanelRoute.PANEL_SETTINGS
    }

    fun closeCardSettings() {
        route = HomeSidePanelRoute.HOME
    }

    fun consumeSettingsBack(): Boolean {
        if (route == HomeSidePanelRoute.HOME) return false
        closeCardSettings()
        return true
    }

    fun fetchAnotherHitokoto() {
        scope.launch { fetchHitokotoInternal() }
    }

    fun setHideWeChatTitle(hide: Boolean) {
        HomeSidePanelPreferences.hideWeChatTitle = hide
        _uiState.update { it.copy(hideWeChatTitle = hide) }
    }

    fun openPersonalProfile() {
        closePanel { openPersonalProfileActivity() }
    }

    fun openStatusEditor() {
        closePanel { openStatusDestination() }
    }

    fun openStatusEditorFromToolbar() {
        openStatusDestination()
    }

    fun saveHitokotoSettings(settings: HitokotoSettings) {
        try {
            hitokoto.saveSettings(settings)
            _uiState.update {
                it.copy(
                    hitokotoSettings = settings,
                )
            }
            route = HomeSidePanelRoute.HOME
            scope.launch { fetchHitokotoInternal() }
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
        if (shortcut == HomeSidePanelShortcut.MARK_ALL_READ) {
            closePanel(null)
            openShortcut(shortcut)
        } else {
            closePanel { openShortcut(shortcut) }
        }
    }

    fun close() {
        scope.coroutineContext.cancel()
    }

    private suspend fun loadCachedWeather() {
        weather.loadCached()?.let { snapshot ->
            _uiState.update { state ->
                state.copy(
                    weather = WeatherUiState.Ready(snapshot),
                    weatherSettings = state.weatherSettings.copy(selectedCity = snapshot.city),
                )
            }
        }
    }

    private suspend fun loadCachedHitokoto() {
        hitokoto.loadCached()?.let { snapshot ->
            _uiState.update { state ->
                state.copy(hitokoto = HitokotoUiState.Ready(snapshot))
            }
        }
    }

    private suspend fun loadIdentity() {
        val loadedProfile = try {
            profile.loadIdentity()
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
        _uiState.update { it.copy(profile = loadedProfile) }
    }

    private suspend fun loadAccountId(): String = try {
        profile.loadAccountId()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        ""
    }

    private suspend fun initializeWeatherCityFromProfile(accountId: String) {
        if (accountId.isBlank()) return
        if (HomeSidePanelPreferences.weatherProfileAccount == accountId) return
        when (val result = profile.readWeatherCityFromProfile()) {
            is WeatherCityMatchResult.Success -> {
                weather.selectCity(result.city)
                updateWeatherSettings(selectedCity = result.city)
            }

            is WeatherCityMatchResult.Error -> {
                publishWeatherMessage(result.reason.message)
            }
        }
        HomeSidePanelPreferences.weatherProfileAccount = accountId
    }

    private suspend fun prepareWeatherAccount(accountId: String) {
        if (accountId.isBlank()) return
        if (HomeSidePanelPreferences.weatherProfileAccount == accountId) return
        weather.resetForAccount()
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
        val result = weather.refresh(weather.selectedCity())
        _uiState.update { state ->
            state.copy(
                weather = when (result) {
                    is WeatherResult.Success -> WeatherUiState.Ready(result.snapshot)
                    is WeatherResult.Error -> WeatherUiState.Error(result.message, result.cached)
                },
                weatherSettings = state.weatherSettings.copy(
                    selectedCity = weather.selectedCity(),
                    actionInProgress = false,
                ),
            )
        }
        if (result is WeatherResult.Error) publishWeatherMessage(result.message)
    }

    private suspend fun fetchHitokotoInternal() {
        val current = _uiState.value.hitokoto
        _uiState.update {
            it.copy(
                hitokoto = when (current) {
                    is HitokotoUiState.Ready -> current.copy(refreshing = true)
                    else -> HitokotoUiState.Loading
                },
            )
        }
        val result = hitokoto.fetchRandom()
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
                weather.selectCity(resolution.city)
                updateWeatherSettings(
                    selectedCity = resolution.city,
                    actionInProgress = false,
                )
                refreshWeatherInternal()
            }

            LocationResolution.NeedPermission -> Unit
            else -> {
                val message = locationResolutionMessage(resolution)
                updateWeatherSettings(
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
        actionInProgress: Boolean? = null,
    ) {
        _uiState.update { state ->
            state.copy(
                weatherSettings = state.weatherSettings.copy(
                    selectedCity = selectedCity ?: state.weatherSettings.selectedCity,
                    searchResults = searchResults ?: state.weatherSettings.searchResults,
                    actionInProgress = actionInProgress ?: state.weatherSettings.actionInProgress,
                ),
            )
        }
    }

    private fun publishWeatherMessage(message: String) {
        _weatherMessages.tryEmit(message)
    }

    private fun openShortcut(shortcut: HomeSidePanelShortcut) {
        when (shortcut) {
            HomeSidePanelShortcut.SCAN -> startExplicit("${activity.packageName}.plugin.scanner.ui.BaseScanUI")
            HomeSidePanelShortcut.PAYMENTS -> {
                if (!startExplicit("${activity.packageName}.plugin.offline.ui.WalletOfflineCoinPurseUI")) {
                    startExplicit("${activity.packageName}.plugin.mall.ui.MallIndexUIv2")
                }
            }

            HomeSidePanelShortcut.FAVORITES -> startExplicit("${activity.packageName}.plugin.fav.ui.FavoriteIndexUI")
            HomeSidePanelShortcut.MOMENTS -> WeApi.openMoments(activity, WeApi.selfWxId)
            HomeSidePanelShortcut.VIDEO_CHANNELS -> startExplicit("${activity.packageName}.plugin.finder.ui.FinderHomeAffinityUI")
            HomeSidePanelShortcut.MARK_ALL_READ -> scope.launch(Dispatchers.IO) { WeConversationApi.markAllAsRead() }
            HomeSidePanelShortcut.WEKIT_SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
    }

    private fun openPersonalProfileActivity() {
        val opened = startExplicit(PERSONAL_PROFILE_NEW_CLASS) {
            putExtra("key_config_item", "SettingGroup_Main_PersonalInfo")
        } || startExplicit(PERSONAL_PROFILE_LEGACY_CLASS)
        if (!opened) showToast(activity, "无法打开个人资料页")
    }

    private fun openStatusDestination() {
        if (WeTextStatusApi.openCurrentStatusActions(activity, WeApi.selfWxId)) return
        openStatusEditorActivity()
    }

    private fun openStatusEditorActivity() {
        val opened = STATUS_EDITOR_CLASSES.any { className ->
            startExplicit(className) { putExtra("KEY_IS_ENTER", true) }
        }
        if (!opened) showToast(activity, "无法打开状态编辑页")
    }

    private fun startExplicit(className: String, configure: Intent.() -> Unit = {}): Boolean {
        val intent = Intent().setClassName(activity.packageName, className).apply(configure)
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private companion object {
        const val PERSONAL_PROFILE_NEW_CLASS =
            "com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI"
        const val PERSONAL_PROFILE_LEGACY_CLASS =
            "com.tencent.mm.plugin.setting.ui.setting.SettingsPersonalInfoUI"
        val STATUS_EDITOR_CLASSES = listOf(
            "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2",
            "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivity",
        )
    }

}

internal const val HOME_SIDE_PANEL_LOCATION_REQUEST_CODE = 0x574B

internal enum class HomeSidePanelRoute {
    HOME,
    WEATHER_SETTINGS,
    HITOKOTO_SETTINGS,
    PANEL_SETTINGS,
}

internal enum class HomeSidePanelShortcut {
    SCAN,
    PAYMENTS,
    FAVORITES,
    MOMENTS,
    VIDEO_CHANNELS,
    MARK_ALL_READ,
    WEKIT_SETTINGS,
}
