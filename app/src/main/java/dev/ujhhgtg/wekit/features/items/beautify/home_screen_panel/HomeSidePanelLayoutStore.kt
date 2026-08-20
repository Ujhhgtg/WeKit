package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

internal object HomeSidePanelLayoutStore {

    fun load(): HomeSidePanelLayoutLoad {
        val legacy = LegacyHomeSidePanelSnapshot(
            weatherCity = HomeSidePanelPreferences.legacySelectedWeatherCity,
            hideWalletBalance = HomeSidePanelPreferences.legacyHideWalletBalance,
            hitokotoSettings = HomeSidePanelPreferences.legacyHitokotoSettings,
        )
        val raw = HomeSidePanelPreferences.layoutRaw
        if (raw != null) {
            return HomeSidePanelLayoutCodec.load(raw, legacy, UuidHomeSidePanelIdGenerator)
        }
        val layout = defaultHomeSidePanelLayout(legacy)
        save(layout).getOrThrow()
        return HomeSidePanelLayoutLoad.Migrated(layout)
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
        WePrefs.remove(HomeSidePanelPreferenceKeys.CARD_WEATHER_CACHE_PREFIX + cardId)
        WePrefs.remove(HomeSidePanelPreferenceKeys.CARD_HITOKOTO_CACHE_PREFIX + cardId)
    }

    fun migrateLegacyCaches(layout: HomeSidePanelLayout) {
        HomeSidePanelPreferences.legacyWeatherLastSuccess?.let { snapshot ->
            val card = layout.cards.filterIsInstance<WeatherCardConfig>().firstOrNull {
                weatherCacheFingerprint(it.city) == weatherCacheFingerprint(snapshot.city)
            }
            if (card != null) {
                if (loadWeatherCache(card.id) == null) {
                    saveWeatherCache(
                        card.id,
                        WeatherCardCacheRecord(weatherCacheFingerprint(card.city), snapshot),
                    ).getOrThrow()
                }
            }
        }

        HomeSidePanelPreferences.legacyHitokotoLastSuccess?.let { snapshot ->
            val legacyFingerprint = hitokotoCacheFingerprint(HomeSidePanelPreferences.legacyHitokotoSettings)
            val card = layout.cards.filterIsInstance<HitokotoCardConfig>().firstOrNull {
                hitokotoCacheFingerprint(it.settings) == legacyFingerprint
            }
            if (card != null) {
                if (loadHitokotoCache(card.id) == null) {
                    saveHitokotoCache(
                        card.id,
                        HitokotoCardCacheRecord(legacyFingerprint, snapshot),
                    ).getOrThrow()
                }
            }
        }
    }

    private inline fun <reified T> decodeCache(key: String): T? =
        WePrefs.getString(key)?.let { raw ->
            runCatching { DefaultJson.decodeFromString<T>(raw) }.getOrNull()
        }

    private inline fun <reified T> encodeCache(key: String, value: T): Result<Unit> =
        runCatching { WePrefs.putString(key, DefaultJson.encodeToString(value)) }
}
