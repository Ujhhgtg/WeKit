package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import java.time.LocalDateTime

sealed interface DateTimeCardContent {
    data object Runtime : DateTimeCardContent
    data class Preview(val now: LocalDateTime) : DateTimeCardContent
}

sealed interface WeatherCardContent {
    data class Runtime(val state: WeatherUiState) : WeatherCardContent
    data class Preview(val snapshot: WeatherSnapshot) : WeatherCardContent
}

sealed interface WalletCardContent {
    data class Runtime(val state: HomeSidePanelWalletUiState) : WalletCardContent
    data class Preview(val displayBalance: String) : WalletCardContent
}

sealed interface HitokotoCardContent {
    data class Runtime(val state: HitokotoUiState) : HitokotoCardContent
    data class Preview(val snapshot: HitokotoSnapshot) : HitokotoCardContent
}

sealed interface HomeSidePanelActionCardContent {
    data object Runtime : HomeSidePanelActionCardContent
    data object Preview : HomeSidePanelActionCardContent
}
