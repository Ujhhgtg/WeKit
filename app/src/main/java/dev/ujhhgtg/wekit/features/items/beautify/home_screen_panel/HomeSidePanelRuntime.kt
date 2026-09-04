package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

sealed interface HomeSidePanelRuntimeNamespace {
    data object Live : HomeSidePanelRuntimeNamespace
    data class Draft(val sessionId: String) : HomeSidePanelRuntimeNamespace
}

@Serializable
data class WeatherCardCacheRecord(
    val fingerprint: String,
    val snapshot: WeatherSnapshot,
)

@Serializable
data class HitokotoCardCacheRecord(
    val fingerprint: String,
    val snapshot: HitokotoSnapshot,
)

data class HomeSidePanelRuntimeKey(
    val namespace: HomeSidePanelRuntimeNamespace,
    val cardId: String,
)

sealed interface HomeSidePanelCardRuntimeState {
    data class Weather(val state: WeatherUiState) : HomeSidePanelCardRuntimeState
    data class Wallet(val state: HomeSidePanelWalletUiState) : HomeSidePanelCardRuntimeState
    data class Hitokoto(val state: HitokotoUiState) : HomeSidePanelCardRuntimeState
}

class HomeSidePanelRuntimeStore(
    private val weather: HomeSidePanelWeather,
    private val hitokoto: HomeSidePanelHitokoto,
    private val walletBalance: HomeSidePanelWalletBalanceSource,
    parentScope: CoroutineScope,
    private val cacheStore: HomeSidePanelLayoutStore = HomeSidePanelLayoutStore,
) {

    private val storeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + storeJob)
    private val _states = MutableStateFlow<Map<HomeSidePanelRuntimeKey, HomeSidePanelCardRuntimeState>>(emptyMap())
    private val activeLayouts = mutableMapOf<HomeSidePanelRuntimeNamespace, HomeSidePanelLayout>()
    private val jobs = mutableMapOf<HomeSidePanelRuntimeKey, Job>()
    private val weatherCaches = mutableMapOf<HomeSidePanelRuntimeKey, WeatherCardCacheRecord>()
    private val hitokotoCaches = mutableMapOf<HomeSidePanelRuntimeKey, HitokotoCardCacheRecord>()
    private var liveCachePolicy = HomeSidePanelLiveCachePolicy.FALLBACK

    val states: StateFlow<Map<HomeSidePanelRuntimeKey, HomeSidePanelCardRuntimeState>> =
        _states.asStateFlow()

    init {
        scope.launch {
            walletBalance.updates.collect { balanceFen ->
                _states.update { current ->
                    current.mapValues { (_, runtime) ->
                        if (runtime is HomeSidePanelCardRuntimeState.Wallet) {
                            runtime.copy(state = runtime.state.withBalance(balanceFen))
                        } else {
                            runtime
                        }
                    }
                }
            }
        }
    }

    fun initializeLive(load: HomeSidePanelLayoutLoad) {
        check(HomeSidePanelRuntimeNamespace.Live !in activeLayouts) {
            "The live side-panel runtime is already initialized"
        }
        liveCachePolicy = homeSidePanelLiveCachePolicy(load)
        if (liveCachePolicy.importLegacyCaches) {
            cacheStore.migrateLegacyCaches(load.layout)
        }
        reconcile(
            namespace = HomeSidePanelRuntimeNamespace.Live,
            old = null,
            new = load.layout,
        )
    }

    fun reconcileDraft(
        sessionId: String,
        old: HomeSidePanelLayout?,
        new: HomeSidePanelLayout,
    ) = reconcile(
        namespace = HomeSidePanelRuntimeNamespace.Draft(sessionId),
        old = old,
        new = new,
    )

    private fun reconcile(
        namespace: HomeSidePanelRuntimeNamespace,
        old: HomeSidePanelLayout?,
        new: HomeSidePanelLayout,
    ) {
        validateHomeSidePanelLayout(new)
        activeLayouts[namespace] = new
        val delta = homeSidePanelRuntimeDelta(old, new)
        (delta.deactivate + delta.reconfigure).forEach { cardId ->
            deactivate(HomeSidePanelRuntimeKey(namespace, cardId))
        }

        val activateIds = delta.activate + delta.reconfigure
        new.cards.filter(HomeSidePanelCardConfig::hasRuntimeState).forEach { card ->
            val key = HomeSidePanelRuntimeKey(namespace, card.id)
            if (card.id in activateIds || key !in _states.value) activate(namespace, card)
        }
    }

    fun refreshWeather(namespace: HomeSidePanelRuntimeNamespace, card: WeatherCardConfig) {
        if (activeCard(namespace, card.id) != card) return
        val key = HomeSidePanelRuntimeKey(namespace, card.id)
        val fingerprint = weatherCacheFingerprint(card.city)
        val cached = weatherCache(namespace, card)?.snapshot
        _states.update { current ->
            val runtime = checkNotNull(current[key]) { "Weather runtime is missing for $key" }
            require(runtime is HomeSidePanelCardRuntimeState.Weather) {
                "Weather card $key has mismatched runtime state $runtime"
            }
            val previous = runtime.state
            val loading = when (previous) {
                is WeatherUiState.Ready -> previous.copy(refreshing = true)
                is WeatherUiState.Error -> previous.cached?.let { WeatherUiState.Ready(it, refreshing = true) }
                    ?: WeatherUiState.Loading
                else -> cached?.let { WeatherUiState.Ready(it, refreshing = true) }
                    ?: WeatherUiState.Loading
            }
            current + (key to HomeSidePanelCardRuntimeState.Weather(loading))
        }
        replaceJob(key) {
            when (val result = weather.refresh(card.city, cached)) {
                is WeatherResult.Success -> {
                    val record = WeatherCardCacheRecord(fingerprint, result.snapshot)
                    weatherCaches[key] = record
                    persistWeatherCache(namespace, card.id, record)
                    updateState(key, HomeSidePanelCardRuntimeState.Weather(WeatherUiState.Ready(result.snapshot)))
                }

                is WeatherResult.Error -> updateState(
                    key,
                    HomeSidePanelCardRuntimeState.Weather(
                        WeatherUiState.Error(result.message, cached),
                    ),
                )
            }
        }
    }

    fun refreshHitokoto(namespace: HomeSidePanelRuntimeNamespace, card: HitokotoCardConfig) {
        if (activeCard(namespace, card.id) != card) return
        val key = HomeSidePanelRuntimeKey(namespace, card.id)
        val fingerprint = hitokotoCacheFingerprint(card.settings)
        val cached = hitokotoCache(namespace, card)?.snapshot
        _states.update { current ->
            val runtime = checkNotNull(current[key]) { "Hitokoto runtime is missing for $key" }
            require(runtime is HomeSidePanelCardRuntimeState.Hitokoto) {
                "Hitokoto card $key has mismatched runtime state $runtime"
            }
            val previous = runtime.state
            val loading = when (previous) {
                is HitokotoUiState.Ready -> previous.copy(refreshing = true)
                is HitokotoUiState.Error -> previous.cached?.let { HitokotoUiState.Ready(it, refreshing = true) }
                    ?: HitokotoUiState.Loading
                else -> cached?.let { HitokotoUiState.Ready(it, refreshing = true) }
                    ?: HitokotoUiState.Loading
            }
            current + (key to HomeSidePanelCardRuntimeState.Hitokoto(loading))
        }
        replaceJob(key) {
            when (val result = hitokoto.fetchRandom(card.settings, cached)) {
                is HitokotoResult.Success -> {
                    val record = HitokotoCardCacheRecord(fingerprint, result.snapshot)
                    hitokotoCaches[key] = record
                    persistHitokotoCache(namespace, card.id, record)
                    updateState(key, HomeSidePanelCardRuntimeState.Hitokoto(HitokotoUiState.Ready(result.snapshot)))
                }

                is HitokotoResult.Error -> updateState(
                    key,
                    HomeSidePanelCardRuntimeState.Hitokoto(
                        HitokotoUiState.Error(result.message, cached),
                    ),
                )
            }
        }
    }

    fun toggleWallet(namespace: HomeSidePanelRuntimeNamespace, card: WalletCardConfig) {
        if (activeCard(namespace, card.id) != card) return
        val key = HomeSidePanelRuntimeKey(namespace, card.id)
        _states.update { current ->
            val wallet = checkNotNull(current[key]) { "Wallet runtime is missing for $key" }
            require(wallet is HomeSidePanelCardRuntimeState.Wallet) {
                "Wallet card $key has mismatched runtime state $wallet"
            }
            current + (key to wallet.copy(state = wallet.state.copy(
                displayState = wallet.state.displayState.toggleFromCard(),
            )))
        }
    }

    fun resetWallets(namespace: HomeSidePanelRuntimeNamespace) {
        _states.update { current ->
            current.mapValues { (key, runtime) ->
                if (key.namespace == namespace && runtime is HomeSidePanelCardRuntimeState.Wallet) {
                    runtime.copy(state = runtime.state.copy(displayState = runtime.state.displayState.reset()))
                } else {
                    runtime
                }
            }
        }
    }

    fun promoteDraft(sessionId: String, committed: HomeSidePanelLayout) {
        validateHomeSidePanelLayout(committed)
        liveCachePolicy = HomeSidePanelLiveCachePolicy.STORED
        val draft = HomeSidePanelRuntimeNamespace.Draft(sessionId)
        val oldLive = activeLayouts[HomeSidePanelRuntimeNamespace.Live]
        val draftWeather = entriesForNamespace(weatherCaches, draft)
        val liveWeather = entriesForNamespace(weatherCaches, HomeSidePanelRuntimeNamespace.Live)
        val draftHitokoto = entriesForNamespace(hitokotoCaches, draft)
        val liveHitokoto = entriesForNamespace(hitokotoCaches, HomeSidePanelRuntimeNamespace.Live)
        val promotedWeather = retainCommittedCardEntries(liveWeather + draftWeather, committed).filter { (id, record) ->
            val card = committed.cards.firstOrNull { it.id == id } as? WeatherCardConfig
            card != null && record.fingerprint == weatherCacheFingerprint(card.city)
        }
        val promotedHitokoto = retainCommittedCardEntries(liveHitokoto + draftHitokoto, committed).filter { (id, record) ->
            val card = committed.cards.firstOrNull { it.id == id } as? HitokotoCardConfig
            card != null && record.fingerprint == hitokotoCacheFingerprint(card.settings)
        }
        val promotedStates = retainCommittedCardEntries(
            _states.value.filterKeys { it.namespace == draft }.mapKeys { it.key.cardId },
            committed,
        )

        jobs.keys.filter { it.namespace == draft || it.namespace == HomeSidePanelRuntimeNamespace.Live }
            .toList().forEach(::cancelJob)
        _states.update { current ->
            current.filterKeys { it.namespace != draft && it.namespace != HomeSidePanelRuntimeNamespace.Live } +
                promotedStates.mapKeys { (cardId, _) ->
                    HomeSidePanelRuntimeKey(HomeSidePanelRuntimeNamespace.Live, cardId)
                }
        }
        weatherCaches.keys.removeAll { it.namespace == draft || it.namespace == HomeSidePanelRuntimeNamespace.Live }
        hitokotoCaches.keys.removeAll { it.namespace == draft || it.namespace == HomeSidePanelRuntimeNamespace.Live }
        oldLive?.cards?.forEach { cacheStore.removeCardCaches(it.id) }
        promotedWeather.forEach { (cardId, record) ->
            val key = HomeSidePanelRuntimeKey(HomeSidePanelRuntimeNamespace.Live, cardId)
            weatherCaches[key] = record
            persistWeatherCache(HomeSidePanelRuntimeNamespace.Live, cardId, record)
        }
        promotedHitokoto.forEach { (cardId, record) ->
            val key = HomeSidePanelRuntimeKey(HomeSidePanelRuntimeNamespace.Live, cardId)
            hitokotoCaches[key] = record
            persistHitokotoCache(HomeSidePanelRuntimeNamespace.Live, cardId, record)
        }
        activeLayouts.remove(draft)
        activeLayouts[HomeSidePanelRuntimeNamespace.Live] = committed
        committed.cards.filter(HomeSidePanelCardConfig::hasRuntimeState).forEach { card ->
            activate(HomeSidePanelRuntimeNamespace.Live, card)
        }
    }

    fun recoverCommittedLayout(sessionId: String, committed: HomeSidePanelLayout) {
        liveCachePolicy = HomeSidePanelLiveCachePolicy.STORED
        val draft = HomeSidePanelRuntimeNamespace.Draft(sessionId)
        jobs.keys.filter { it.namespace == draft || it.namespace == HomeSidePanelRuntimeNamespace.Live }
            .toList().forEach(::cancelJob)
        weatherCaches.keys.removeAll { it.namespace == draft || it.namespace == HomeSidePanelRuntimeNamespace.Live }
        hitokotoCaches.keys.removeAll { it.namespace == draft || it.namespace == HomeSidePanelRuntimeNamespace.Live }
        activeLayouts.remove(draft)
        activeLayouts[HomeSidePanelRuntimeNamespace.Live] = committed

        val recovered = committed.cards.mapNotNull { card ->
            val runtime = when (card) {
                is WeatherCardConfig -> HomeSidePanelCardRuntimeState.Weather(WeatherUiState.Loading)
                is WalletCardConfig -> HomeSidePanelCardRuntimeState.Wallet(
                    HomeSidePanelWalletUiState(
                        balanceFen = walletBalance.updates.value,
                        displayState = HomeSidePanelWalletDisplayState(card.hideBalanceByDefault),
                    ),
                )

                is HitokotoCardConfig -> HomeSidePanelCardRuntimeState.Hitokoto(HitokotoUiState.Loading)
                else -> null
            }
            runtime?.let {
                HomeSidePanelRuntimeKey(HomeSidePanelRuntimeNamespace.Live, card.id) to it
            }
        }.toMap()
        _states.update { current ->
            current.filterKeys { it.namespace != draft && it.namespace != HomeSidePanelRuntimeNamespace.Live } + recovered
        }

        committed.cards.forEach { card ->
            runCatching {
                when (card) {
                    is WeatherCardConfig -> refreshWeather(HomeSidePanelRuntimeNamespace.Live, card)
                    is WalletCardConfig -> walletBalance.refresh()
                    is HitokotoCardConfig -> refreshHitokoto(HomeSidePanelRuntimeNamespace.Live, card)
                    else -> Unit
                }
            }.onFailure {
                WeLogger.w(TAG, "failed to restart runtime for committed card ${card.id}", it)
            }
        }
    }

    fun discardDraft(sessionId: String) {
        val namespace = HomeSidePanelRuntimeNamespace.Draft(sessionId)
        jobs.keys.filter { it.namespace == namespace }.toList().forEach(::cancelJob)
        _states.update { current -> current.filterKeys { it.namespace != namespace } }
        weatherCaches.keys.removeAll { it.namespace == namespace }
        hitokotoCaches.keys.removeAll { it.namespace == namespace }
        activeLayouts.remove(namespace)
    }

    fun close() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        activeLayouts.clear()
        weatherCaches.clear()
        hitokotoCaches.clear()
        _states.value = emptyMap()
        scope.cancel()
        weather.close()
        hitokoto.close()
    }

    private fun activate(namespace: HomeSidePanelRuntimeNamespace, card: HomeSidePanelCardConfig) {
        when (card) {
            is WeatherCardConfig -> {
                val key = HomeSidePanelRuntimeKey(namespace, card.id)
                val cached = weatherCache(namespace, card)?.snapshot
                updateState(
                    key,
                    HomeSidePanelCardRuntimeState.Weather(
                        cached?.let(WeatherUiState::Ready) ?: WeatherUiState.Loading,
                    ),
                )
                refreshWeather(namespace, card)
            }

            is WalletCardConfig -> {
                val key = HomeSidePanelRuntimeKey(namespace, card.id)
                val current = _states.value[key]
                require(current == null || current is HomeSidePanelCardRuntimeState.Wallet) {
                    "Wallet card $key has mismatched runtime state $current"
                }
                val existing = current?.state
                val displayState = existing?.displayState?.takeIf {
                    it.defaultMaskEnabled == card.hideBalanceByDefault
                } ?: HomeSidePanelWalletDisplayState(card.hideBalanceByDefault)
                updateState(
                    key,
                    HomeSidePanelCardRuntimeState.Wallet(
                        HomeSidePanelWalletUiState(walletBalance.updates.value, displayState),
                    ),
                )
                runCatching(walletBalance::refresh).onFailure {
                    WeLogger.w(TAG, "failed to refresh wallet balance for card ${card.id}", it)
                }
            }

            is HitokotoCardConfig -> {
                val key = HomeSidePanelRuntimeKey(namespace, card.id)
                val cached = hitokotoCache(namespace, card)?.snapshot
                updateState(
                    key,
                    HomeSidePanelCardRuntimeState.Hitokoto(
                        cached?.let(HitokotoUiState::Ready) ?: HitokotoUiState.Loading,
                    ),
                )
                refreshHitokoto(namespace, card)
            }

            else -> Unit
        }
    }

    private fun deactivate(key: HomeSidePanelRuntimeKey) {
        cancelJob(key)
        _states.update { it - key }
        weatherCaches.remove(key)
        hitokotoCaches.remove(key)
        if (
            key.namespace == HomeSidePanelRuntimeNamespace.Live &&
            liveCachePolicy.persistLiveCaches
        ) {
            cacheStore.removeCardCaches(key.cardId)
        }
    }

    private fun weatherCache(
        namespace: HomeSidePanelRuntimeNamespace,
        card: WeatherCardConfig,
    ): WeatherCardCacheRecord? {
        val key = HomeSidePanelRuntimeKey(namespace, card.id)
        val fingerprint = weatherCacheFingerprint(card.city)
        val record = weatherCaches[key]
            ?: when (namespace) {
                HomeSidePanelRuntimeNamespace.Live -> cacheStore.loadWeatherCache(card.id)
                is HomeSidePanelRuntimeNamespace.Draft -> weatherCaches[
                    HomeSidePanelRuntimeKey(HomeSidePanelRuntimeNamespace.Live, card.id)
                ] ?: cacheStore.loadWeatherCache(card.id)
            }
        return record?.takeIf { it.fingerprint == fingerprint }?.also { weatherCaches[key] = it }
    }

    private fun hitokotoCache(
        namespace: HomeSidePanelRuntimeNamespace,
        card: HitokotoCardConfig,
    ): HitokotoCardCacheRecord? {
        val key = HomeSidePanelRuntimeKey(namespace, card.id)
        val fingerprint = hitokotoCacheFingerprint(card.settings)
        val record = hitokotoCaches[key]
            ?: when (namespace) {
                HomeSidePanelRuntimeNamespace.Live -> cacheStore.loadHitokotoCache(card.id)
                is HomeSidePanelRuntimeNamespace.Draft -> hitokotoCaches[
                    HomeSidePanelRuntimeKey(HomeSidePanelRuntimeNamespace.Live, card.id)
                ] ?: cacheStore.loadHitokotoCache(card.id)
            }
        return record?.takeIf { it.fingerprint == fingerprint }?.also { hitokotoCaches[key] = it }
    }

    private fun activeCard(namespace: HomeSidePanelRuntimeNamespace, cardId: String): HomeSidePanelCardConfig? =
        activeLayouts[namespace]?.cards?.firstOrNull { it.id == cardId }

    private fun updateState(key: HomeSidePanelRuntimeKey, state: HomeSidePanelCardRuntimeState) {
        if (activeCard(key.namespace, key.cardId) == null) return
        _states.update { it + (key to state) }
    }

    private fun replaceJob(key: HomeSidePanelRuntimeKey, block: suspend () -> Unit) {
        cancelJob(key)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
        job.start()
    }

    private fun cancelJob(key: HomeSidePanelRuntimeKey) {
        jobs.remove(key)?.cancel()
    }

    private fun persistWeatherCache(
        namespace: HomeSidePanelRuntimeNamespace,
        cardId: String,
        record: WeatherCardCacheRecord,
    ) {
        if (
            namespace != HomeSidePanelRuntimeNamespace.Live ||
            !liveCachePolicy.persistLiveCaches
        ) {
            return
        }
        cacheStore.saveWeatherCache(cardId, record).onFailure {
            WeLogger.w(TAG, "failed to persist weather cache for card $cardId", it)
        }
    }

    private fun persistHitokotoCache(
        namespace: HomeSidePanelRuntimeNamespace,
        cardId: String,
        record: HitokotoCardCacheRecord,
    ) {
        if (
            namespace != HomeSidePanelRuntimeNamespace.Live ||
            !liveCachePolicy.persistLiveCaches
        ) {
            return
        }
        cacheStore.saveHitokotoCache(cardId, record).onFailure {
            WeLogger.w(TAG, "failed to persist hitokoto cache for card $cardId", it)
        }
    }

    private fun <T> entriesForNamespace(
        entries: Map<HomeSidePanelRuntimeKey, T>,
        namespace: HomeSidePanelRuntimeNamespace,
    ): Map<String, T> = entries.filterKeys { it.namespace == namespace }.mapKeys { it.key.cardId }

    private companion object {
        const val TAG = "HomeSidePanelRuntimeStore"
    }
}
