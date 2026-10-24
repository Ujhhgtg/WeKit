package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

object HomeSidePanelLayoutStore {

    fun load(): HomeSidePanelLayoutLoad {
        val raw = HomeSidePanelPreferences.layoutRaw
        if (raw != null) {
            return HomeSidePanelLayoutCodec.load(raw, UuidHomeSidePanelIdGenerator).also { loaded ->
                if (loaded is HomeSidePanelLayoutLoad.Fallback) {
                    WeLogger.w(TAG, "invalid saved side panel layout; using an in-memory fallback")
                }
            }
        }
        val layout = defaultHomeSidePanelLayout()
        save(layout).getOrThrow()
        return HomeSidePanelLayoutLoad.Stored(layout)
    }

    fun save(layout: HomeSidePanelLayout): Result<Unit> = runCatching {
        HomeSidePanelPreferences.layoutRaw = HomeSidePanelLayoutCodec.encode(layout)
    }

    fun loadWeatherCache(cardId: String): WeatherCardCacheRecord? =
        decodeCache(HomeSidePanelPreferenceKeys.CARD_WEATHER_CACHE_PREFIX + cardId)

    fun saveWeatherCache(cardId: String, record: WeatherCardCacheRecord): Result<Unit> =
        encodeCache(HomeSidePanelPreferenceKeys.CARD_WEATHER_CACHE_PREFIX + cardId, record)

    fun loadHitokotoCache(cardId: String): HitokotoCardCacheRecord? =
        decodeCache(HomeSidePanelPreferenceKeys.CARD_HITOKOTO_CACHE_PREFIX + cardId)

    fun saveHitokotoCache(cardId: String, record: HitokotoCardCacheRecord): Result<Unit> =
        encodeCache(HomeSidePanelPreferenceKeys.CARD_HITOKOTO_CACHE_PREFIX + cardId, record)

    fun removeCardCaches(cardId: String) {
        KvStore.remove(HomeSidePanelPreferenceKeys.CARD_WEATHER_CACHE_PREFIX + cardId)
        KvStore.remove(HomeSidePanelPreferenceKeys.CARD_HITOKOTO_CACHE_PREFIX + cardId)
    }

    private inline fun <reified T> decodeCache(key: String): T? =
        KvStore.getString(key)?.let { raw ->
            runCatching { DefaultJson.decodeFromString<T>(raw) }.getOrNull()
        }

    private inline fun <reified T> encodeCache(key: String, value: T): Result<Unit> = runCatching {
        KvStore.putString(key, DefaultJson.encodeToString(value))
    }

    private const val TAG = "HomeSidePanelLayoutStore"
}
