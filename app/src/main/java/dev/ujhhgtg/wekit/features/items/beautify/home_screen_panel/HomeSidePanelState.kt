package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
import dev.ujhhgtg.wekit.features.items.beautify.BeautifyText
import dev.ujhhgtg.wekit.features.items.beautify.beautifyText
import dev.ujhhgtg.wekit.features.items.beautify.localizedBeautifyString
import dev.ujhhgtg.wekit.features.items.beautify.resolveBeautifyText
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class HomeSidePanelUiState(
    val profile: HomeSidePanelProfile,
    val formalLayout: HomeSidePanelLayout,
    val renderedLayout: HomeSidePanelLayout,
    val runtimeStates: Map<HomeSidePanelRuntimeKey, HomeSidePanelCardRuntimeState>,
    val route: HomeSidePanelRoute,
    val editing: Boolean,
    val initialized: Boolean,
    val showToolbarProfile: Boolean,
    val hideWeChatTitle: Boolean,
    val imageImportingCardIds: Set<String> = emptySet(),
)

internal class HomeSidePanelState(
    private val activity: Activity,
    private val profile: HomeSidePanelProfileLoader,
    private val weather: HomeSidePanelWeather,
    private val hitokoto: HomeSidePanelHitokoto,
    private val runtimeStore: HomeSidePanelRuntimeStore,
    private val location: HomeSidePanelLocation,
    private val scope: CoroutineScope,
    private val closePanel: ((() -> Unit)?) -> Unit,
    private val layoutStore: HomeSidePanelLayoutStore = HomeSidePanelLayoutStore,
    private val idGenerator: HomeSidePanelIdGenerator = UuidHomeSidePanelIdGenerator,
    private val imageAssets: HomeSidePanelImageAssetStore = HomeSidePanelImageAssetStore(),
) {

    private data class ActiveEditing(
        val sessionId: String,
        val editor: HomeSidePanelEditSession,
    )

    private data class ActiveImageImport(
        val token: String,
        val sessionId: String,
        val cardId: String,
        var job: Job? = null,
    )

    private val started = AtomicBoolean()
    private var editing: ActiveEditing? = null
    private var cancelDrag: (() -> Unit)? = null
    private var pendingLocationCardId: String? = null
    private val weatherActionJobs = mutableMapOf<String, Job>()
    private val imageImports = linkedMapOf<String, ActiveImageImport>()
    private var statusSyncJob: Job? = null
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _addCandidates = MutableSharedFlow<HomeSidePanelAddCandidate>(extraBufferCapacity = 8)
    private val _weatherSettings = MutableStateFlow<Map<String, WeatherSettingsUiState>>(emptyMap())
    private val actionExecutor = HomeSidePanelActionExecutor(
        activity = activity,
        scope = scope,
        closePanel = closePanel,
        publishMessage = ::publishMessage,
    )
    private val emptyLayout = HomeSidePanelLayout(cards = emptyList())
    private val _uiState = MutableStateFlow(
        HomeSidePanelUiState(
            profile = HomeSidePanelProfile(
                wxId = "",
                nickname = "",
                avatarUrl = "",
                status = HomeSidePanelStatusUiState.Loading,
            ),
            formalLayout = emptyLayout,
            renderedLayout = emptyLayout,
            runtimeStates = emptyMap(),
            route = HomeSidePanelRoute.Home,
            editing = false,
            initialized = false,
            showToolbarProfile = HomeSidePanelPreferences.showToolbarProfile,
            hideWeChatTitle = HomeSidePanelPreferences.hideWeChatTitle,
            imageImportingCardIds = emptySet(),
        ),
    )

    val uiState: StateFlow<HomeSidePanelUiState> = _uiState.asStateFlow()
    val weatherSettings: StateFlow<Map<String, WeatherSettingsUiState>> = _weatherSettings.asStateFlow()
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    val addCandidates: SharedFlow<HomeSidePanelAddCandidate> = _addCandidates.asSharedFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        imageAssets.cleanupStaleDrafts()
        scope.launch {
            runtimeStore.states.collect { runtimeStates ->
                _uiState.update { it.copy(runtimeStates = runtimeStates) }
            }
        }
        val loaded = layoutStore.load()
        val layout = loaded.layout
        runtimeStore.initializeLive(loaded)
        _uiState.update {
            it.copy(
                formalLayout = layout,
                renderedLayout = layout,
                runtimeStates = runtimeStore.states.value,
                initialized = true,
            )
        }
        scheduleIdentitySync(
            waitForChange = false,
            maxAttempts = INITIAL_STATUS_SYNC_ATTEMPTS,
        )
    }

    fun onPanelOpened() {
        runtimeStore.resetWallets(renderedNamespace())
        scheduleIdentitySync(
            waitForChange = false,
            maxAttempts = PANEL_OPEN_STATUS_SYNC_ATTEMPTS,
        )
    }

    fun onPanelClosed() {
        runtimeStore.resetWallets(renderedNamespace())
    }

    fun refreshStatus() {
        scheduleStatusSync(
            baseline = null,
            waitForChange = false,
            maxAttempts = MANUAL_STATUS_SYNC_ATTEMPTS,
        )
    }

    fun onLauncherResumed() {
        resumePendingLocationDetection()
        scheduleIdentitySync(
            waitForChange = true,
            maxAttempts = RESUME_STATUS_SYNC_ATTEMPTS,
        )
    }

    fun openPanelSettings() {
        check(editing == null) { "Panel settings cannot open during layout editing" }
        setRoute(HomeSidePanelRoute.PanelSettings)
    }

    fun enterEditMode() {
        if (editing != null) {
            setRoute(HomeSidePanelRoute.EditHome)
            return
        }
        val formal = _uiState.value.formalLayout
        val active = ActiveEditing(
            sessionId = idGenerator.nextId(),
            editor = HomeSidePanelEditSession(formal, idGenerator),
        )
        editing = active
        _uiState.update { it.copy(initialized = false) }
        runtimeStore.reconcileDraft(active.sessionId, old = null, new = active.editor.draft)
        _uiState.update {
            it.copy(
                renderedLayout = active.editor.draft,
                runtimeStates = runtimeStore.states.value,
                route = HomeSidePanelRoute.EditHome,
                editing = true,
                initialized = true,
            )
        }
    }

    fun discardEditing() {
        cancelDrag?.invoke()
        val active = requireEditing()
        _uiState.update { it.copy(initialized = false) }
        runtimeStore.discardDraft(active.sessionId)
        cancelImageImports(active.sessionId)
        imageAssets.discardSession(active.sessionId)
        editing = null
        pendingLocationCardId = null
        cancelWeatherActionJobs()
        _weatherSettings.value = emptyMap()
        _uiState.update {
            it.copy(
                renderedLayout = it.formalLayout,
                runtimeStates = runtimeStore.states.value,
                route = HomeSidePanelRoute.Home,
                editing = false,
                initialized = true,
            )
        }
    }

    fun saveEditing() {
        cancelDrag?.invoke()
        val active = requireEditing()
        check(_uiState.value.imageImportingCardIds.isEmpty()) {
            "Side panel image imports must finish before saving"
        }
        val prepared = imageAssets.prepareCommit(
            sessionId = active.sessionId,
            oldAssetIds = _uiState.value.formalLayout.imageAssetIds(),
            newAssetIds = active.editor.draft.imageAssetIds(),
        ).getOrElse { failure ->
            WeLogger.w(TAG, "failed to prepare side panel image assets", failure)
            publishMessage(beautifyText(R.string.home_side_panel_save_failed))
            return
        }
        val result = commitHomeSidePanelEdit(
            editor = active.editor,
            persist = layoutStore::save,
            promote = {
                _uiState.update { state -> state.copy(initialized = false) }
                runtimeStore.promoteDraft(active.sessionId, it)
            },
        )
        if (result is HomeSidePanelEditCommit.Retained) {
            imageAssets.rollbackCommit(prepared)
            WeLogger.w(TAG, "failed to save side panel layout", result.failure)
            publishMessage(beautifyText(R.string.home_side_panel_save_failed))
            return
        }
        result as HomeSidePanelEditCommit.Committed
        result.promotionFailure?.let { failure ->
            WeLogger.w(TAG, "layout saved; recovering failed runtime promotion", failure)
            runtimeStore.recoverCommittedLayout(active.sessionId, result.layout)
        }
        runCatching { imageAssets.finalizeCommit(prepared) }.onFailure { failure ->
            WeLogger.w(TAG, "layout saved; failed to clean side panel image assets", failure)
        }

        editing = null
        pendingLocationCardId = null
        cancelWeatherActionJobs()
        _weatherSettings.value = emptyMap()
        _uiState.update {
            it.copy(
                formalLayout = result.layout,
                renderedLayout = result.layout,
                runtimeStates = runtimeStore.states.value,
                route = HomeSidePanelRoute.Home,
                editing = false,
                initialized = true,
            )
        }
    }

    fun openAddCard() {
        cancelDrag?.invoke()
        requireEditing()
        setRoute(HomeSidePanelRoute.AddCard)
    }

    fun addCard(type: HomeSidePanelCardType) {
        mutateDraft { addCard(type) }
        setRoute(HomeSidePanelRoute.EditHome)
    }

    fun emitAddCardCandidate(
        type: HomeSidePanelCardType,
        pointer: HomeSidePanelCandidatePointer,
    ) {
        requireEditing()
        _addCandidates.tryEmit(HomeSidePanelAddCandidate.Card(type, pointer))
    }

    fun openEditHomeForDrag() {
        requireEditing()
        setRoute(HomeSidePanelRoute.EditHome)
    }

    fun commitDrag(commit: HomeSidePanelDragCommit) {
        mutateDraft { applyHomeSidePanelDragCommit(commit) }
    }

    fun setDragCancellation(cancel: (() -> Unit)?) {
        cancelDrag = cancel
    }

    fun removeCard(cardId: String) {
        editing?.let { cancelImageImports(it.sessionId, cardId) }
        weatherActionJobs.remove(cardId)?.cancel()
        if (pendingLocationCardId == cardId) pendingLocationCardId = null
        _weatherSettings.update { it - cardId }
        mutateDraft { removeCard(cardId) }
    }

    fun selectImage(cardId: String) {
        val active = requireEditing()
        requireDraftImageCard(cardId)
        if (cardId in _uiState.value.imageImportingCardIds) return
        val sessionId = active.sessionId
        val token = UUID.randomUUID().toString()
        imageImports[token] = ActiveImageImport(token, sessionId, cardId)
        refreshImageImportingCards()
        runCatching {
            TransparentActivity.launch(activity) {
                val launcher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    finish()
                    val request = imageImports[token] ?: return@registerForActivityResult
                    if (uri == null) {
                        completeImageImport(token)
                        return@registerForActivityResult
                    }
                    val job = scope.launch {
                        try {
                            val result = imageAssets.importDraft(request.sessionId) {
                                activity.contentResolver.openInputStream(uri)
                                    ?: throw IllegalStateException("Failed to open selected image")
                            }
                            val current = editing
                            val currentCardExists = current?.sessionId == request.sessionId &&
                                current.editor.draft.cards.any {
                                    it.id == request.cardId && it is ImageCardConfig
                                }
                            if (currentCardExists) {
                                when (result) {
                                    is HomeSidePanelImageImportResult.Success -> mutateImageDraft {
                                        updateImage(request.cardId) {
                                            it.copy(
                                                imageAssetId = result.assetId,
                                                imageWidthPx = result.widthPx,
                                                imageHeightPx = result.heightPx,
                                            )
                                        }
                                    }

                                    HomeSidePanelImageImportResult.TooLarge -> publishMessage(
                                        beautifyText(R.string.home_side_panel_image_too_large),
                                    )

                                    HomeSidePanelImageImportResult.TooManyPixels -> publishMessage(
                                        beautifyText(R.string.home_side_panel_image_dimensions_too_large),
                                    )

                                    HomeSidePanelImageImportResult.UnsupportedAspectRatio -> publishMessage(
                                        beautifyText(R.string.home_side_panel_image_aspect_ratio_unsupported),
                                    )

                                    HomeSidePanelImageImportResult.InvalidImage -> publishMessage(
                                        beautifyText(R.string.home_side_panel_image_invalid),
                                    )

                                    is HomeSidePanelImageImportResult.Failure -> {
                                        WeLogger.w(TAG, "failed to import side panel image", result.error)
                                        publishMessage(beautifyText(R.string.home_side_panel_image_import_failed))
                                    }
                                }
                            } else if (result is HomeSidePanelImageImportResult.Success) {
                                imageAssets.deleteDraftAsset(request.sessionId, result.assetId)
                            }
                        } finally {
                            completeImageImport(token)
                        }
                    }
                    request.job = job
                }
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }.onFailure { failure ->
            completeImageImport(token)
            WeLogger.w(TAG, "failed to open side panel image picker", failure)
            publishMessage(beautifyText(R.string.home_side_panel_image_import_failed))
        }
    }

    fun updateImageScaleMode(cardId: String, mode: HomeSidePanelImageScaleMode) {
        mutateImageDraft { updateImage(cardId) { it.copy(scaleMode = mode) } }
    }

    fun updateImageHeight(cardId: String, heightDp: Int) {
        mutateImageDraft { updateImage(cardId) { it.copy(heightDp = heightDp) } }
    }

    fun updateImageDimensions(cardId: String, widthPx: Int, heightPx: Int) {
        mutateImageDraft {
            updateImage(cardId) { it.copy(imageWidthPx = widthPx, imageHeightPx = heightPx) }
        }
    }

    fun imageFile(cardId: String): Path? {
        val card = renderedCard(cardId)
        require(card is ImageCardConfig) { "Card '$cardId' is ${card.type}; expected Image card" }
        val assetId = card.imageAssetId ?: return null
        return imageAssets.resolve(editing?.sessionId, assetId)
    }

    fun openAddAction(cardId: String) {
        requireDraftActionCard(cardId)
        setRoute(HomeSidePanelRoute.AddAction(cardId))
    }

    fun addAction(cardId: String, kind: HomeSidePanelActionKind) {
        mutateDraft { addAction(cardId, kind) }
        setRoute(HomeSidePanelRoute.EditHome)
    }

    fun emitAddActionCandidate(
        cardId: String,
        kind: HomeSidePanelActionKind,
        pointer: HomeSidePanelCandidatePointer,
    ) {
        requireDraftActionCard(cardId)
        _addCandidates.tryEmit(HomeSidePanelAddCandidate.Action(cardId, kind, pointer))
    }

    fun removeAction(cardId: String, actionId: String) {
        mutateDraft { removeAction(cardId, actionId) }
    }

    fun openDateTimeSettings(cardId: String) {
        requireDraftDateTimeCard(cardId)
        setRoute(HomeSidePanelRoute.DateTimeSettings(cardId))
    }

    fun updateDateTimeLunarCalendar(cardId: String, show: Boolean) {
        mutateDraft { updateDateTime(cardId) { it.copy(showLunarCalendar = show) } }
    }

    fun openWeatherSettings(cardId: String) {
        val card = requireDraftWeatherCard(cardId)
        _weatherSettings.update { current ->
            current + (cardId to (current[cardId] ?: WeatherSettingsUiState(selectedCity = card.city)))
        }
        setRoute(HomeSidePanelRoute.WeatherSettings(cardId))
    }

    fun updateWeatherCity(cardId: String, city: WeatherCity) {
        mutateDraft { updateWeather(cardId) { it.copy(city = city) } }
        updateWeatherSettings(
            cardId = cardId,
            selectedCity = city,
            searchResults = emptyList(),
        )
    }

    fun searchWeatherCities(cardId: String, query: String) {
        requireDraftWeatherCard(cardId)
        _weatherSettings.update { current ->
            current + (cardId to weatherSettingsFor(cardId).copy(searchQuery = query))
        }
        scope.launch {
            val results = weather.searchCities(query)
            if (_weatherSettings.value[cardId]?.searchQuery == query) {
                updateWeatherSettings(cardId, searchResults = results)
            }
        }
    }

    fun detectWeatherLocation(cardId: String) {
        requireDraftWeatherCard(cardId)
        if (weatherSettingsFor(cardId).actionInProgress) return
        pendingLocationCardId = cardId
        setWeatherSettingsProgress(cardId, true)
        replaceWeatherActionJob(cardId) {
            when (val resolution = location.resolve(activity)) {
                LocationResolution.NeedPermission -> activity.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    HOME_SIDE_PANEL_LOCATION_REQUEST_CODE,
                )

                else -> applyLocationResolution(cardId, resolution)
            }
        }
    }

    fun readWeatherFromProfile(cardId: String) {
        requireDraftWeatherCard(cardId)
        if (weatherSettingsFor(cardId).actionInProgress) return
        replaceWeatherActionJob(cardId) {
            setWeatherSettingsProgress(cardId, true)
            when (val result = profile.readWeatherCityFromProfile()) {
                is WeatherCityMatchResult.Success -> {
                    updateWeatherCity(cardId, result.city)
                    setWeatherSettingsProgress(cardId, false)
                }

                is WeatherCityMatchResult.Error -> {
                    setWeatherSettingsProgress(cardId, false)
                    publishMessage(beautifyText(result.reason.messageRes))
                }
            }
        }
    }

    fun openWalletSettings(cardId: String) {
        requireDraftWalletCard(cardId)
        setRoute(HomeSidePanelRoute.WalletSettings(cardId))
    }

    fun updateWalletMask(cardId: String, hide: Boolean) {
        mutateDraft { updateWallet(cardId) { it.copy(hideBalanceByDefault = hide) } }
    }

    fun openHitokotoSettings(cardId: String) {
        requireDraftHitokotoCard(cardId)
        setRoute(HomeSidePanelRoute.HitokotoSettings(cardId))
    }

    fun updateHitokotoSettings(cardId: String, settings: HitokotoSettings) {
        val validationError = hitokoto.validate(settings)
        if (validationError != null) {
            publishMessage(validationError)
            return
        }
        mutateDraft { updateHitokoto(cardId) { it.copy(settings = settings) } }
        setRoute(HomeSidePanelRoute.EditHome)
    }

    fun closeCardSettings() {
        when (_uiState.value.route) {
            HomeSidePanelRoute.PanelSettings -> setRoute(HomeSidePanelRoute.Home)
            is HomeSidePanelRoute.DateTimeSettings,
            is HomeSidePanelRoute.WeatherSettings,
            is HomeSidePanelRoute.WalletSettings,
            is HomeSidePanelRoute.HitokotoSettings,
            HomeSidePanelRoute.AddCard,
            is HomeSidePanelRoute.AddAction,
            -> {
                requireEditing()
                setRoute(HomeSidePanelRoute.EditHome)
            }

            HomeSidePanelRoute.Home,
            HomeSidePanelRoute.EditHome,
            -> error("Route ${_uiState.value.route} is not a secondary settings route")
        }
    }

    fun consumeSettingsBack(): Boolean = when (_uiState.value.route) {
        HomeSidePanelRoute.Home -> false
        HomeSidePanelRoute.PanelSettings -> {
            setRoute(HomeSidePanelRoute.Home)
            true
        }

        HomeSidePanelRoute.EditHome -> {
            discardEditing()
            true
        }

        is HomeSidePanelRoute.DateTimeSettings,
        is HomeSidePanelRoute.WeatherSettings,
        is HomeSidePanelRoute.WalletSettings,
        is HomeSidePanelRoute.HitokotoSettings,
        HomeSidePanelRoute.AddCard,
        is HomeSidePanelRoute.AddAction,
        -> {
            requireEditing()
            setRoute(HomeSidePanelRoute.EditHome)
            true
        }
    }

    fun refreshWeather(cardId: String) {
        val card = renderedCard(cardId)
        require(card is WeatherCardConfig) { "Card '$cardId' is ${card.type}; expected Weather card" }
        runtimeStore.refreshWeather(renderedNamespace(), card)
    }

    fun toggleWallet(cardId: String) {
        val card = renderedCard(cardId)
        require(card is WalletCardConfig) { "Card '$cardId' is ${card.type}; expected Wallet card" }
        runtimeStore.toggleWallet(renderedNamespace(), card)
    }

    fun refreshHitokoto(cardId: String) {
        val card = renderedCard(cardId)
        require(card is HitokotoCardConfig) { "Card '$cardId' is ${card.type}; expected Hitokoto card" }
        runtimeStore.refreshHitokoto(renderedNamespace(), card)
    }

    fun runtimeState(cardId: String): HomeSidePanelCardRuntimeState? =
        _uiState.value.runtimeStates[HomeSidePanelRuntimeKey(renderedNamespace(), cardId)]

    fun runAction(kind: HomeSidePanelActionKind) {
        actionExecutor.execute(kind)
    }

    fun openPaymentCode() {
        actionExecutor.openPaymentCode()
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

    fun close() {
        cancelDrag?.invoke()
        cancelDrag = null
        val active = editing
        active?.let {
            cancelImageImports(it.sessionId)
            runtimeStore.discardDraft(it.sessionId)
            imageAssets.discardSession(it.sessionId)
        }
        editing = null
        pendingLocationCardId = null
        cancelWeatherActionJobs()
        runtimeStore.close()
        scope.coroutineContext.cancel()
    }

    private fun resumePendingLocationDetection() {
        val cardId = pendingLocationCardId ?: return
        if (editing == null) {
            pendingLocationCardId = null
            return
        }
        if (location.hasCoarsePermission(activity)) {
            pendingLocationCardId = null
            setWeatherSettingsProgress(cardId, false)
            detectWeatherLocation(cardId)
        } else {
            pendingLocationCardId = null
            setWeatherSettingsProgress(cardId, false)
            publishMessage(beautifyText(R.string.home_side_panel_location_permission_denied))
        }
    }

    private suspend fun applyLocationResolution(cardId: String, resolution: LocationResolution) {
        pendingLocationCardId = null
        when (resolution) {
            is LocationResolution.Success -> {
                updateWeatherCity(cardId, resolution.city)
                setWeatherSettingsProgress(cardId, false)
            }

            LocationResolution.NeedPermission -> Unit
            else -> {
                setWeatherSettingsProgress(cardId, false)
                publishMessage(locationResolutionMessage(resolution))
            }
        }
    }

    private fun weatherSettingsFor(cardId: String): WeatherSettingsUiState {
        val card = requireDraftWeatherCard(cardId)
        return _weatherSettings.value[cardId] ?: WeatherSettingsUiState(selectedCity = card.city)
    }

    private fun setWeatherSettingsProgress(cardId: String, progress: Boolean) {
        updateWeatherSettings(cardId, actionInProgress = progress)
    }

    private fun replaceWeatherActionJob(cardId: String, block: suspend () -> Unit) {
        weatherActionJobs.remove(cardId)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        weatherActionJobs[cardId] = job
        job.invokeOnCompletion { weatherActionJobs.remove(cardId, job) }
        job.start()
    }

    private fun cancelWeatherActionJobs() {
        weatherActionJobs.values.forEach(Job::cancel)
        weatherActionJobs.clear()
    }

    private fun cancelImageImports(sessionId: String, cardId: String? = null) {
        val matching = imageImports.values.filter {
            it.sessionId == sessionId && (cardId == null || it.cardId == cardId)
        }
        matching.forEach { request ->
            imageImports.remove(request.token)
            request.job?.cancel()
        }
        refreshImageImportingCards()
    }

    private fun completeImageImport(token: String) {
        if (imageImports.remove(token) != null) refreshImageImportingCards()
    }

    private fun refreshImageImportingCards() {
        val sessionId = editing?.sessionId
        val cardIds = if (sessionId == null) emptySet() else imageImports.values
            .filter { it.sessionId == sessionId }
            .mapTo(linkedSetOf(), ActiveImageImport::cardId)
        _uiState.update { it.copy(imageImportingCardIds = cardIds) }
    }

    private fun updateWeatherSettings(
        cardId: String,
        selectedCity: WeatherCity? = null,
        searchResults: List<WeatherCity>? = null,
        actionInProgress: Boolean? = null,
    ) {
        _weatherSettings.update { current ->
            val state = weatherSettingsFor(cardId)
            current + (cardId to state.copy(
                selectedCity = selectedCity ?: state.selectedCity,
                searchResults = searchResults ?: state.searchResults,
                actionInProgress = actionInProgress ?: state.actionInProgress,
            ))
        }
    }

    private inline fun <T> mutateDraft(block: HomeSidePanelEditSession.() -> T): T {
        val active = requireEditing()
        val old = active.editor.draft
        val result = active.editor.block()
        val new = active.editor.draft
        _uiState.update { it.copy(initialized = false) }
        runtimeStore.reconcileDraft(active.sessionId, old, new)
        _uiState.update {
            it.copy(
                renderedLayout = new,
                runtimeStates = runtimeStore.states.value,
                initialized = true,
            )
        }
        return result
    }

    private inline fun <T> mutateImageDraft(block: HomeSidePanelEditSession.() -> T): T {
        val active = requireEditing()
        val result = active.editor.block()
        _uiState.update { it.copy(renderedLayout = active.editor.draft) }
        return result
    }

    private fun requireEditing(): ActiveEditing = checkNotNull(editing) {
        "No HomeSidePanel edit session is active"
    }

    private fun requireDraftWeatherCard(cardId: String): WeatherCardConfig {
        val card = requireDraftCard(cardId)
        require(card is WeatherCardConfig) { "Card '$cardId' is ${card.type}; expected Weather card" }
        return card
    }

    private fun requireDraftDateTimeCard(cardId: String): DateTimeCardConfig {
        val card = requireDraftCard(cardId)
        require(card is DateTimeCardConfig) {
            "Card '$cardId' is ${card.type}; expected Date & time card"
        }
        return card
    }

    private fun requireDraftWalletCard(cardId: String): WalletCardConfig {
        val card = requireDraftCard(cardId)
        require(card is WalletCardConfig) { "Card '$cardId' is ${card.type}; expected Wallet card" }
        return card
    }

    private fun requireDraftHitokotoCard(cardId: String): HitokotoCardConfig {
        val card = requireDraftCard(cardId)
        require(card is HitokotoCardConfig) { "Card '$cardId' is ${card.type}; expected Hitokoto card" }
        return card
    }

    private fun requireDraftImageCard(cardId: String): ImageCardConfig {
        val card = requireDraftCard(cardId)
        require(card is ImageCardConfig) { "Card '$cardId' is ${card.type}; expected Image card" }
        return card
    }

    private fun requireDraftActionCard(cardId: String): HomeSidePanelCardConfig {
        val card = requireDraftCard(cardId)
        require(card is HorizontalActionsCardConfig || card is VerticalActionsCardConfig) {
            "Card '$cardId' is ${card.type}; expected an action card"
        }
        return card
    }

    private fun requireDraftCard(cardId: String): HomeSidePanelCardConfig =
        requireEditing().editor.draft.cards.firstOrNull { it.id == cardId }
            ?: throw IllegalArgumentException("Card '$cardId' does not exist in the draft layout")

    private fun renderedCard(cardId: String): HomeSidePanelCardConfig =
        _uiState.value.renderedLayout.cards.firstOrNull { it.id == cardId }
            ?: throw IllegalArgumentException("Card '$cardId' does not exist in the rendered layout")

    private fun renderedNamespace(): HomeSidePanelRuntimeNamespace = editing?.let {
        HomeSidePanelRuntimeNamespace.Draft(it.sessionId)
    } ?: HomeSidePanelRuntimeNamespace.Live

    private fun setRoute(route: HomeSidePanelRoute) {
        _uiState.update { it.copy(route = route) }
    }

    private suspend fun loadIdentity() {
        val loadedProfile = try {
            profile.loadIdentity()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
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
        HomeSidePanelStatusUiState.Error,
        -> true
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
        const val TAG = "HomeSidePanelState"
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
