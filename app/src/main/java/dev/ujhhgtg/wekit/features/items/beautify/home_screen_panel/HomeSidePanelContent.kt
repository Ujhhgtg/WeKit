package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class HomeSidePanelTransitionKind {
    ENTER_EDITOR,
    EXIT_EDITOR,
    PUSH,
    POP,
    NONE,
}

internal fun homeSidePanelTransitionKind(
    from: HomeSidePanelRoute,
    to: HomeSidePanelRoute,
): HomeSidePanelTransitionKind = when {
    from == to -> HomeSidePanelTransitionKind.NONE
    to == HomeSidePanelRoute.EditHome && from !is HomeSidePanelRoute.EditorDetail ->
        HomeSidePanelTransitionKind.ENTER_EDITOR

    from == HomeSidePanelRoute.EditHome && to == HomeSidePanelRoute.Home ->
        HomeSidePanelTransitionKind.EXIT_EDITOR

    from == HomeSidePanelRoute.Home && to == HomeSidePanelRoute.PanelSettings ->
        HomeSidePanelTransitionKind.PUSH

    from == HomeSidePanelRoute.PanelSettings && to == HomeSidePanelRoute.Home ->
        HomeSidePanelTransitionKind.POP

    from == HomeSidePanelRoute.EditHome && to is HomeSidePanelRoute.EditorDetail ->
        HomeSidePanelTransitionKind.PUSH

    from is HomeSidePanelRoute.EditorDetail && to == HomeSidePanelRoute.EditHome ->
        HomeSidePanelTransitionKind.POP

    else -> HomeSidePanelTransitionKind.NONE
}

@Composable
internal fun HomeSidePanelContent(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    val dragState = remember { HomeSidePanelDragState() }
    val listState = rememberLazyListState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        HomeSidePanelDragHost(
            dragState = dragState,
            listState = listState,
            state = state,
            panelState = panelState,
        ) {
            if (!state.initialized) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else AnimatedContent(
                targetState = state.route,
                contentKey = ::homeSidePanelRouteContentKey,
                transitionSpec = {
                    homeSidePanelRouteContentTransform(
                        homeSidePanelTransitionKind(initialState, targetState),
                    )
                },
                label = "HomeSidePanelRoute",
            ) { route ->
                when (route) {
                    HomeSidePanelRoute.Home,
                    HomeSidePanelRoute.EditHome,
                    -> HomeSidePanelHome(
                        state = state,
                        panelState = panelState,
                        dragState = dragState,
                        listState = listState,
                    )

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
                        val card = state.renderedLayout.cards.single {
                            it.id == route.cardId
                        } as HitokotoCardConfig
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
    }
}

private fun homeSidePanelRouteContentKey(route: HomeSidePanelRoute): Any = when (route) {
    // Home and EditHome share the lazy list; their classifier-driven pulse runs in HomeSidePanelHome.
    HomeSidePanelRoute.Home,
    HomeSidePanelRoute.EditHome,
    -> HomeSidePanelHomeContentKey

    else -> route
}

private data object HomeSidePanelHomeContentKey

private fun homeSidePanelRouteContentTransform(
    kind: HomeSidePanelTransitionKind,
): ContentTransform {
    val enter: EnterTransition
    val exit: ExitTransition
    when (kind) {
        HomeSidePanelTransitionKind.ENTER_EDITOR -> {
            enter = fadeIn(tween(180)) +
                scaleIn(tween(220), initialScale = 0.985f) +
                slideInVertically(tween(220)) { it / 24 }
            exit = fadeOut(tween(120)) +
                scaleOut(tween(180), targetScale = 0.99f) +
                slideOutVertically(tween(180)) { -it / 32 }
        }

        HomeSidePanelTransitionKind.EXIT_EDITOR -> {
            enter = fadeIn(tween(180)) +
                scaleIn(tween(220), initialScale = 0.99f) +
                slideInVertically(tween(220)) { -it / 32 }
            exit = fadeOut(tween(140)) +
                scaleOut(tween(200), targetScale = 0.985f) +
                slideOutVertically(tween(200)) { it / 24 }
        }

        HomeSidePanelTransitionKind.PUSH -> {
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(240)) { it / 4 }
            exit = fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -it / 8 }
        }

        HomeSidePanelTransitionKind.POP -> {
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(240)) { -it / 8 }
            exit = fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { it / 4 }
        }

        HomeSidePanelTransitionKind.NONE -> {
            enter = EnterTransition.None
            exit = ExitTransition.None
        }
    }
    return enter.togetherWith(exit)
}
