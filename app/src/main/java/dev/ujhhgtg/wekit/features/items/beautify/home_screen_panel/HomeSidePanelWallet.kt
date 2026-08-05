package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

internal data class HomeSidePanelWalletDisplayState(
    val defaultMaskEnabled: Boolean,
    val isMasked: Boolean = defaultMaskEnabled,
) {
    fun toggleFromCard(): HomeSidePanelWalletDisplayState = if (defaultMaskEnabled) {
        copy(isMasked = !isMasked)
    } else {
        this
    }

    fun reset() = copy(isMasked = defaultMaskEnabled)
}

internal data class HomeSidePanelWalletUiState(
    val balance: String = "¥ 2,480.60",
    val displayState: HomeSidePanelWalletDisplayState = HomeSidePanelWalletDisplayState(true),
) {
    val displayBalance: String
        get() = if (displayState.isMasked) "******" else balance
}
