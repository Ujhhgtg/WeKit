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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val wallet: HomeSidePanelWalletUiState,
    val showToolbarProfile: Boolean,
    val hideWeChatTitle: Boolean,
)

internal class HomeSidePanelState(
    private val activity: Activity,
    private val profile: HomeSidePanelProfileLoader,
    private val weather: HomeSidePanelWeather,
    private val hitokoto: HomeSidePanelHitokoto,
    private val walletBalance: HomeSidePanelWalletBalanceSource,
    private val location: HomeSidePanelLocation,
    private val scope: CoroutineScope,
    private val closePanel: ((() -> Unit)?) -> Unit,
) {

    private val started = AtomicBoolean()
    private var pendingLocationPermission = false
    private var locationJob: Job? = null
    private var statusSyncJob: Job? = null
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
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
            wallet = HomeSidePanelWalletUiState(
                displayState = HomeSidePanelWalletDisplayState(
                    defaultMaskEnabled = HomeSidePanelPreferences.hideWalletBalance,
                ),
            ),
            showToolbarProfile = HomeSidePanelPreferences.showToolbarProfile,
            hideWeChatTitle = HomeSidePanelPreferences.hideWeChatTitle,
        ),
    )

    var route by mutableStateOf(HomeSidePanelRoute.HOME)
        private set

    val uiState: StateFlow<HomeSidePanelUiState> = _uiState.asStateFlow()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun startPreload() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            walletBalance.updates.collect { balanceFen ->
                _uiState.update { state ->
                    state.copy(wallet = state.wallet.copy(balanceFen = balanceFen))
                }
            }
        }
        refreshWalletBalance()
        scheduleIdentitySync(
            waitForChange = false,
            maxAttempts = INITIAL_STATUS_SYNC_ATTEMPTS,
        )
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
        resetWalletDisplay()
        refreshWalletBalance()
        scheduleIdentitySync(
            waitForChange = false,
            maxAttempts = PANEL_OPEN_STATUS_SYNC_ATTEMPTS,
        )
    }

    fun refreshStatus() {
        scheduleStatusSync(
            baseline = null,
            waitForChange = false,
            maxAttempts = MANUAL_STATUS_SYNC_ATTEMPTS,
        )
    }

    fun onLauncherResumed() {
        scheduleIdentitySync(
            waitForChange = true,
            maxAttempts = RESUME_STATUS_SYNC_ATTEMPTS,
        )
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
                    publishMessage(result.reason.message)
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
            publishMessage(message)
        }
    }

    fun openWeatherSettings() {
        route = HomeSidePanelRoute.WEATHER_SETTINGS
    }

    fun openWalletSettings() {
        route = HomeSidePanelRoute.WALLET_SETTINGS
    }

    fun toggleWalletBalance() {
        _uiState.update { state ->
            state.copy(
                wallet = state.wallet.copy(
                    displayState = state.wallet.displayState.toggleFromCard(),
                ),
            )
        }
    }

    fun setHideWalletBalance(hide: Boolean) {
        HomeSidePanelPreferences.hideWalletBalance = hide
        _uiState.update { state ->
            state.copy(
                wallet = state.wallet.copy(
                    displayState = HomeSidePanelWalletDisplayState(hide),
                ),
            )
        }
    }

    fun onPanelClosed() {
        resetWalletDisplay()
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

    fun setShowToolbarProfile(show: Boolean) {
        HomeSidePanelPreferences.showToolbarProfile = show
        _uiState.update { it.copy(showToolbarProfile = show) }
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
        resetWalletDisplay()
        scope.coroutineContext.cancel()
    }

    private fun refreshWalletBalance() {
        walletBalance.refresh()
    }

    private fun resetWalletDisplay() {
        _uiState.update { state ->
            state.copy(
                wallet = state.wallet.copy(
                    displayState = state.wallet.displayState.reset(),
                ),
            )
        }
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

    private fun scheduleStatusSync(
        baseline: StatusFingerprint?,
        waitForChange: Boolean,
        maxAttempts: Int,
    ) {
        statusSyncJob?.cancel()
        statusSyncJob = scope.launch {
            synchronizeStatus(baseline, waitForChange, maxAttempts)
        }
    }

    private fun scheduleIdentitySync(
        waitForChange: Boolean,
        maxAttempts: Int,
    ) {
        val baseline = statusFingerprint(_uiState.value.profile.status)
        statusSyncJob?.cancel()
        statusSyncJob = scope.launch {
            loadIdentity()
            val loadedStatus = _uiState.value.profile.status
            if (statusSyncSatisfied(loadedStatus, baseline, waitForChange)) return@launch
            synchronizeStatus(
                baseline = baseline,
                waitForChange = waitForChange,
                maxAttempts = maxAttempts - 1,
                delayFirst = true,
            )
        }
    }

    private suspend fun synchronizeStatus(
        baseline: StatusFingerprint?,
        waitForChange: Boolean,
        maxAttempts: Int,
        delayFirst: Boolean = false,
    ) {
        repeat(maxAttempts) { attempt ->
            if (delayFirst || attempt > 0) delay(STATUS_SYNC_INTERVAL_MS)
            val status = profile.refreshStatus()
            _uiState.update { state ->
                state.copy(profile = state.profile.copy(status = status))
            }
            if (statusSyncSatisfied(status, baseline, waitForChange)) return
        }
    }

    private fun statusSyncSatisfied(
        status: HomeSidePanelStatusUiState,
        baseline: StatusFingerprint?,
        waitForChange: Boolean,
    ): Boolean = isSettledStatus(status) &&
        (!waitForChange || statusFingerprint(status) != baseline)

    private fun isSettledStatus(status: HomeSidePanelStatusUiState): Boolean = when (status) {
        HomeSidePanelStatusUiState.Loading -> false
        is HomeSidePanelStatusUiState.Ready -> status.status.description.isNotBlank()
        HomeSidePanelStatusUiState.NoStatus,
        is HomeSidePanelStatusUiState.Error -> true
    }

    private fun statusFingerprint(status: HomeSidePanelStatusUiState): StatusFingerprint = when (status) {
        HomeSidePanelStatusUiState.Loading -> StatusFingerprint.Loading
        HomeSidePanelStatusUiState.NoStatus -> StatusFingerprint.NoStatus
        is HomeSidePanelStatusUiState.Error -> StatusFingerprint.Error
        is HomeSidePanelStatusUiState.Ready -> StatusFingerprint.Ready(
            statusId = status.status.statusId,
            description = status.status.description,
            iconId = status.status.iconId,
            userText = status.status.userText,
        )
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
                publishMessage(result.reason.message)
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
                    is WeatherUiState.Error -> current.cached?.let { WeatherUiState.Ready(it, refreshing = true) }
                        ?: WeatherUiState.Loading
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
        if (result is WeatherResult.Error) publishMessage(result.message)
    }

    private suspend fun fetchHitokotoInternal() {
        val current = _uiState.value.hitokoto
        _uiState.update {
            it.copy(
                hitokoto = when (current) {
                    is HitokotoUiState.Ready -> current.copy(refreshing = true)
                    is HitokotoUiState.Error -> current.cached?.let { HitokotoUiState.Ready(it, refreshing = true) }
                        ?: HitokotoUiState.Loading
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
        if (result is HitokotoResult.Error) publishMessage(result.message)
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
                publishMessage(message)
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

    private fun publishMessage(message: String) {
        _messages.tryEmit(message)
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
        val baseline = statusFingerprint(_uiState.value.profile.status)
        if (WeTextStatusApi.openCurrentStatusActions(activity, WeApi.selfWxId)) {
            scheduleStatusSync(
                baseline = baseline,
                waitForChange = true,
                maxAttempts = STATUS_ACTION_SYNC_ATTEMPTS,
            )
            return
        }
        if (openStatusEditorActivity()) {
            scheduleStatusSync(
                baseline = baseline,
                waitForChange = true,
                maxAttempts = STATUS_ACTION_SYNC_ATTEMPTS,
            )
        }
    }

    private fun openStatusEditorActivity(): Boolean {
        val opened = STATUS_EDITOR_CLASSES.any { className ->
            startExplicit(className) { putExtra("KEY_IS_ENTER", true) }
        }
        if (!opened) showToast(activity, "无法打开状态编辑页")
        return opened
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
        const val STATUS_SYNC_INTERVAL_MS = 350L
        const val INITIAL_STATUS_SYNC_ATTEMPTS = 24
        const val PANEL_OPEN_STATUS_SYNC_ATTEMPTS = 8
        const val MANUAL_STATUS_SYNC_ATTEMPTS = 12
        const val RESUME_STATUS_SYNC_ATTEMPTS = 8
        const val STATUS_ACTION_SYNC_ATTEMPTS = 48
    }

    private sealed interface StatusFingerprint {
        data object Loading : StatusFingerprint
        data object NoStatus : StatusFingerprint
        data object Error : StatusFingerprint
        data class Ready(
            val statusId: String,
            val description: String,
            val iconId: String,
            val userText: String,
        ) : StatusFingerprint
    }

}

internal const val HOME_SIDE_PANEL_LOCATION_REQUEST_CODE = 0x574B

internal enum class HomeSidePanelRoute {
    HOME,
    WEATHER_SETTINGS,
    WALLET_SETTINGS,
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
