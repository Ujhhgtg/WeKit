package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

enum class HomeSidePanelShortcut {
    SCAN,
    PAYMENTS,
    FAVORITES,
    MOMENTS,
    VIDEO_CHANNELS,
    MARK_ALL_READ,
    WEKIT_SETTINGS,
}

interface HomeSidePanelNavigator {
    fun closePanel()

    fun openShortcut(shortcut: HomeSidePanelShortcut)
}
