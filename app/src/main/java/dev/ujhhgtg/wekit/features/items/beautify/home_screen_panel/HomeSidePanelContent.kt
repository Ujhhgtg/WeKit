package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeSidePanelContent(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        if (!state.initialized) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else when (val route = state.route) {
            HomeSidePanelRoute.Home,
            HomeSidePanelRoute.EditHome,
            -> HomeSidePanelHome(state, panelState)

            HomeSidePanelRoute.PanelSettings -> HomeSidePanelPanelSettings(state, panelState)
            HomeSidePanelRoute.AddCard -> HomeSidePanelAddCardPage(
                onBack = panelState::closeCardSettings,
                onAddCard = panelState::addCard,
                onLongPressCard = panelState::emitAddCardCandidate,
            )

            is HomeSidePanelRoute.AddAction -> HomeSidePanelAddActionPage(
                card = state.renderedLayout.cards.single { it.id == route.cardId },
                onBack = panelState::closeCardSettings,
                onAddAction = panelState::addAction,
                onLongPressAction = panelState::emitAddActionCandidate,
            )

            is HomeSidePanelRoute.WeatherSettings -> HomeSidePanelWeatherSettings(
                card = state.renderedLayout.cards.single { it.id == route.cardId } as WeatherCardConfig,
                panelState = panelState,
            )

            is HomeSidePanelRoute.WalletSettings -> HomeSidePanelWalletSettings(
                card = state.renderedLayout.cards.single { it.id == route.cardId } as WalletCardConfig,
                panelState = panelState,
            )

            is HomeSidePanelRoute.HitokotoSettings -> {
                val card = state.renderedLayout.cards.single { it.id == route.cardId } as HitokotoCardConfig
                val runtime = checkNotNull(panelState.runtimeState(card.id)) {
                    "Hitokoto card '${card.id}' has no runtime state"
                }
                require(runtime is HomeSidePanelCardRuntimeState.Hitokoto) {
                    "Hitokoto card '${card.id}' has mismatched runtime state $runtime"
                }
                HomeSidePanelHitokotoSettings(
                    card = card,
                    runtime = runtime.state,
                    panelState = panelState,
                )
            }
        }
    }
}
