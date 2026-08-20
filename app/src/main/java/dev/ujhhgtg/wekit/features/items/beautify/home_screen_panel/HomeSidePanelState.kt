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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
import dev.ujhhgtg.wekit.features.items.beautify.BeautifyText
import dev.ujhhgtg.wekit.features.items.beautify.beautifyText
import dev.ujhhgtg.wekit.features.items.beautify.localizedBeautifyString
import dev.ujhhgtg.wekit.features.items.beautify.resolveBeautifyText
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
    private val actionExecutor = HomeSidePanelActionExecutor(
        activity = activity,
        scope = scope,
        closePanel = closePanel,
        publishMessage = { message -> publishMessage(message) },
    )
    private val _uiState = MutableStateFlow(
        HomeSidePanelUiState(
            profile = HomeSidePanelProfile(
                wxId = "",
                nickname = "",
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

    var route by mutableStateOf<HomeSidePanelRoute>(HomeSidePanelRoute.Home)
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
                    publishMessage(beautifyText(result.reason.messageRes))
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
            updateWeatherSettings(
                actionInProgress = false,
            )
            publishMessage(beautifyText(R.string.home_side_panel_location_permission_denied))
        }
    }

    fun openWeatherSettings() {
        route = HomeSidePanelRoute.LegacyWeatherSettings
    }

    fun openWalletSettings() {
        route = HomeSidePanelRoute.LegacyWalletSettings
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
        route = HomeSidePanelRoute.LegacyHitokotoSettings
    }

    fun openPanelSettings() {
        route = HomeSidePanelRoute.PanelSettings
    }

    fun closeCardSettings() {
        route = HomeSidePanelRoute.Home
    }

    fun consumeSettingsBack(): Boolean {
        if (route == HomeSidePanelRoute.Home) return false
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
            route = HomeSidePanelRoute.Home
            scope.launch { fetchHitokotoInternal() }
        } catch (error: InvalidHitokotoSettingsException) {
            _uiState.update { state ->
                state.copy(
                    hitokoto = HitokotoUiState.Error(
                        message = error.text,
                        cached = (state.hitokoto as? HitokotoUiState.Ready)?.snapshot,
                    ),
                )
            }
        }
    }

    fun runAction(kind: HomeSidePanelActionKind) {
        actionExecutor.execute(kind)
    }

    // Temporary pre-Task-6 compatibility bridge. Task 7/8 cleanup must remove this old shortcut API.
    fun runShortcut(shortcut: HomeSidePanelShortcut) {
        runAction(shortcut.toActionKind())
    }

    fun close() {
        resetWalletDisplay()
        scope.coroutineContext.cancel()
        // Temporary pre-Task-6 ownership bridge: this fixed state exclusively owns both services.
        // Task 6 moves their lifecycle under HomeSidePanelRuntimeStore.close().
        weather.close()
        hitokoto.close()
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
                nickname = "",
                avatarUrl = "",
                status = HomeSidePanelStatusUiState.Error,
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
        HomeSidePanelStatusUiState.Error -> true
    }

    private fun statusFingerprint(status: HomeSidePanelStatusUiState): StatusFingerprint = when (status) {
        HomeSidePanelStatusUiState.Loading -> StatusFingerprint.Loading
        HomeSidePanelStatusUiState.NoStatus -> StatusFingerprint.NoStatus
        HomeSidePanelStatusUiState.Error -> StatusFingerprint.Error
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
                publishMessage(beautifyText(result.reason.messageRes))
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
                updateWeatherSettings(
                    actionInProgress = false,
                )
                publishMessage(locationResolutionMessage(resolution))
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

    private fun publishMessage(message: BeautifyText) {
        publishMessage(activity.resolveBeautifyText(message))
    }

    private fun openPersonalProfileActivity() {
        val opened = startExplicit(PERSONAL_PROFILE_NEW_CLASS) {
            putExtra("key_config_item", "SettingGroup_Main_PersonalInfo")
        } || startExplicit(PERSONAL_PROFILE_LEGACY_CLASS)
        if (!opened) showToast(activity, localizedBeautifyString(R.string.home_side_panel_open_profile_failed))
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
        if (!opened) showToast(activity, localizedBeautifyString(R.string.home_side_panel_open_status_failed))
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

internal enum class HomeSidePanelShortcut {
    SCAN,
    PAYMENTS,
    FAVORITES,
    MOMENTS,
    VIDEO_CHANNELS,
    MARK_ALL_READ,
    WEKIT_SETTINGS,
}

private fun HomeSidePanelShortcut.toActionKind(): HomeSidePanelActionKind = when (this) {
    HomeSidePanelShortcut.SCAN -> HomeSidePanelActionKind.SCAN
    HomeSidePanelShortcut.PAYMENTS -> HomeSidePanelActionKind.WALLET
    HomeSidePanelShortcut.FAVORITES -> HomeSidePanelActionKind.FAVORITES
    HomeSidePanelShortcut.MOMENTS -> HomeSidePanelActionKind.MOMENTS
    HomeSidePanelShortcut.VIDEO_CHANNELS -> HomeSidePanelActionKind.CHANNELS
    HomeSidePanelShortcut.MARK_ALL_READ -> HomeSidePanelActionKind.MARK_ALL_READ
    HomeSidePanelShortcut.WEKIT_SETTINGS -> HomeSidePanelActionKind.WEKIT_SETTINGS
}
