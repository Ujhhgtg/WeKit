package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

internal object HomeSidePanelLayoutStore {

    fun load(): HomeSidePanelLayoutLoad {
        val legacy = LegacyHomeSidePanelSnapshot(
            weatherCity = HomeSidePanelPreferences.selectedWeatherCity,
            hideWalletBalance = HomeSidePanelPreferences.hideWalletBalance,
            hitokotoSettings = HomeSidePanelPreferences.hitokotoSettings,
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
}
