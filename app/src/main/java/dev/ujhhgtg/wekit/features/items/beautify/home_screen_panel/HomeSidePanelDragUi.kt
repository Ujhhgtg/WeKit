package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ujhhgtg.wekit.R
import kotlin.math.roundToInt

@Composable
internal fun HomeSidePanelDragHost(
    dragState: HomeSidePanelDragState,
    listState: LazyListState,
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val snapshot = dragState.snapshot

    DisposableEffect(dragState, panelState) {
        panelState.setDragCancellation(dragState::cancel)
        onDispose {
            dragState.cancel()
            panelState.setDragCancellation(null)
        }
    }
    LaunchedEffect(panelState, dragState) {
        panelState.addCandidates.collect { candidate ->
            val pointer = candidate.pointer
            val payload = when (candidate) {
                is HomeSidePanelAddCandidate.Card -> HomeSidePanelDragPayload.NewCard(candidate.type)
                is HomeSidePanelAddCandidate.Action -> HomeSidePanelDragPayload.NewAction(
                    candidate.cardId,
                    candidate.kind,
                )
            }
            val started = dragState.begin(
                payload = payload,
                pointerId = pointer.pointerId,
                rootPosition = RootDragPosition(pointer.rootX, pointer.rootY),
                anchor = RootDragPosition(pointer.anchorX, pointer.anchorY),
                sourceBounds = RootDragBounds(
                    pointer.sourceLeft,
                    pointer.sourceTop,
                    pointer.sourceRight,
                    pointer.sourceBottom,
                ),
            )
            if (started) panelState.openEditHomeForDrag()
        }
    }
    LaunchedEffect(state.editing) {
        if (!state.editing) dragState.cancel()
    }
    LaunchedEffect(snapshot?.startToken) {
        if (snapshot != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(snapshot?.startToken, snapshot?.targetChangeToken) {
        if (snapshot != null && snapshot.targetChangeToken > 0L) {
            val startToken = snapshot.startToken
            val targetToken = snapshot.targetChangeToken
            withFrameNanos { }
            val current = dragState.snapshot
            if (current?.startToken == startToken && current.targetChangeToken == targetToken) {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        }
    }
    LaunchedEffect(snapshot?.startToken, state.route, dragState.viewportBounds) {
        if (snapshot != null && state.route == HomeSidePanelRoute.EditHome) {
            withFrameNanos { }
            dragState.refreshTarget()
        }
    }
    LaunchedEffect(
        snapshot?.startToken,
        snapshot?.rootPosition?.y,
        dragState.viewportBounds,
        state.route,
    ) {
        if (state.route != HomeSidePanelRoute.EditHome) return@LaunchedEffect
        val active = snapshot ?: return@LaunchedEffect
        val edgeZone = with(density) { HOME_SIDE_PANEL_EDGE_SCROLL_ZONE.toPx() }
        val maxStep = with(density) { HOME_SIDE_PANEL_EDGE_SCROLL_STEP.toPx() }
        while (true) {
            val current = dragState.snapshot
            if (current == null || current.pointerId != active.pointerId) break
            val viewport = dragState.viewportBounds ?: break
            val y = current.rootPosition.y
            val step = when {
                y in viewport.top..(viewport.top + edgeZone) -> {
                    -maxStep * (1f - (y - viewport.top) / edgeZone)
                }

                y in (viewport.bottom - edgeZone)..viewport.bottom -> {
                    maxStep * (1f - (viewport.bottom - y) / edgeZone)
                }

                else -> 0f
            }
            if (step == 0f) break
            listState.scrollBy(step)
            withFrameNanos { }
            dragState.refreshTarget()
        }
    }

    val latestCommit by rememberUpdatedState(panelState::commitDrag)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .homeSidePanelRootPointerObserver(dragState) { commit -> latestCommit(commit) },
    ) {
        content()
        snapshot?.let {
            HomeSidePanelDragOverlay(
                snapshot = it,
                state = state,
                panelState = panelState,
            )
        }
    }
}

internal fun Modifier.homeSidePanelDragViewport(
    dragState: HomeSidePanelDragState,
): Modifier = composed {
    DisposableEffect(dragState) {
        onDispose(dragState::unregisterViewport)
    }
    onGloballyPositioned { coordinates ->
        dragState.registerViewport(coordinates.boundsInRoot().toRootDragBounds())
    }
}

internal fun Modifier.homeSidePanelCardDragTarget(
    dragState: HomeSidePanelDragState,
    cardId: String,
    index: Int,
    actionAxis: HomeSidePanelDragAxis?,
): Modifier = composed {
    DisposableEffect(dragState, cardId) {
        onDispose {
            dragState.unregisterCardBounds(cardId)
            if (actionAxis != null) dragState.unregisterActionContainer(cardId)
        }
    }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot().toRootDragBounds()
        dragState.registerCardBounds(
            cardId = cardId,
            index = index,
            bounds = bounds,
            sourceBounds = coordinates.homeSidePanelUnclippedBoundsInRoot().toRootDragBounds(),
        )
        if (actionAxis != null) {
            dragState.registerActionContainer(cardId, actionAxis, bounds)
        }
    }
}

internal fun Modifier.homeSidePanelActionDragTarget(
    dragState: HomeSidePanelDragState,
    cardId: String,
    actionId: String,
    index: Int,
): Modifier = composed {
    DisposableEffect(dragState, cardId, actionId) {
        onDispose { dragState.unregisterActionBounds(cardId, actionId) }
    }
    onGloballyPositioned { coordinates ->
        dragState.registerActionBounds(
            cardId = cardId,
            actionId = actionId,
            index = index,
            bounds = coordinates.boundsInRoot().toRootDragBounds(),
            sourceBounds = coordinates.homeSidePanelUnclippedBoundsInRoot().toRootDragBounds(),
        )
    }
}

internal fun Modifier.homeSidePanelActionTerminalDragTarget(
    dragState: HomeSidePanelDragState,
    cardId: String,
    insertionIndex: Int,
): Modifier = composed {
    DisposableEffect(dragState, cardId) {
        onDispose { dragState.unregisterActionTerminalBounds(cardId) }
    }
    onGloballyPositioned { coordinates ->
        dragState.registerActionTerminalBounds(
            cardId = cardId,
            insertionIndex = insertionIndex,
            bounds = coordinates.boundsInRoot().toRootDragBounds(),
        )
    }
}

internal fun Modifier.homeSidePanelDragSource(
    dragState: HomeSidePanelDragState,
    payload: HomeSidePanelDragPayload,
    @StringRes descriptionRes: Int,
    @StringRes additionalDescriptionRes: Int? = null,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val description = stringResource(descriptionRes)
    val additionalDescription = if (additionalDescriptionRes != null) {
        stringResource(additionalDescriptionRes)
    } else {
        null
    }
    onGloballyPositioned { coordinates = it }
        .semantics {
            this[SemanticsProperties.ContentDescription] =
                listOfNotNull(additionalDescription, description)
        }
        .pointerInput(dragState, payload) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                dragState.claimSource(down.id.value, payload)
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val bounds = coordinates!!.homeSidePanelUnclippedBoundsInRoot()
                val root = bounds.topLeft + longPress.position
                val started = dragState.begin(
                    payload = payload,
                    pointerId = longPress.id.value,
                    rootPosition = RootDragPosition(root.x, root.y),
                    anchor = RootDragPosition(longPress.position.x, longPress.position.y),
                    sourceBounds = bounds.toRootDragBounds(),
                )
                val ownsDrag = started || dragState.snapshot?.let {
                    it.pointerId == longPress.id.value && it.payload == payload
                } == true
                if (!ownsDrag) return@awaitEachGesture
                longPress.consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach(PointerInputChange::consume)
                } while (event.changes.any(PointerInputChange::pressed))
            }
        }
}

@Composable
internal fun HomeSidePanelCardInsertionGap(
    snapshot: HomeSidePanelDragSnapshot,
) {
    val density = LocalDensity.current
    val height = with(density) { snapshot.sourceBounds.height.toDp() }.coerceAtLeast(32.dp)
    Spacer(Modifier.fillMaxWidth().height(height))
}

@Composable
internal fun HomeSidePanelActionInsertionGap(
    snapshot: HomeSidePanelDragSnapshot,
    axis: HomeSidePanelDragAxis,
) {
    val density = LocalDensity.current
    val bounds = snapshot.targetBounds ?: snapshot.sourceBounds
    when (axis) {
        HomeSidePanelDragAxis.Horizontal -> {
            val height = with(density) { bounds.height.toDp() }.coerceAtLeast(52.dp)
            Spacer(Modifier.fillMaxWidth().height(height))
        }

        HomeSidePanelDragAxis.Vertical -> {
            val height = with(density) { bounds.height.toDp() }.coerceAtLeast(48.dp)
            Spacer(Modifier.fillMaxWidth().height(height))
        }
    }
}

@Composable
private fun HomeSidePanelDragOverlay(
    snapshot: HomeSidePanelDragSnapshot,
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    val density = LocalDensity.current
    val sourceWidth = with(density) { snapshot.sourceBounds.width.toDp() }.coerceAtLeast(72.dp)
    val sourceHeight = with(density) { snapshot.sourceBounds.height.toDp() }.coerceAtLeast(52.dp)
    var lifted by remember(snapshot.startToken) { mutableStateOf(false) }
    LaunchedEffect(snapshot.startToken) {
        withFrameNanos { }
        lifted = true
    }
    val scale by animateFloatAsState(
        targetValue = if (lifted) 1.035f else 1f,
        animationSpec = tween(150),
        label = "HomeSidePanelDragScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (lifted) 12.dp else 2.dp,
        animationSpec = tween(170),
        label = "HomeSidePanelDragElevation",
    )
    val anchorFractionX = snapshot.anchor.x / snapshot.sourceBounds.width.coerceAtLeast(1f)
    val anchorFractionY = snapshot.anchor.y / snapshot.sourceBounds.height.coerceAtLeast(1f)
    val left = snapshot.rootPosition.x - with(density) { sourceWidth.toPx() } * anchorFractionX
    val top = snapshot.rootPosition.y - with(density) { sourceHeight.toPx() } * anchorFractionY
    Surface(
        modifier = Modifier
            .zIndex(1f)
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(sourceWidth, sourceHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(anchorFractionX, anchorFractionY)
            }
            .clearAndSetSemantics { },
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = elevation,
    ) {
        HomeSidePanelDragVisual(snapshot.payload, state, panelState)
    }
}

@Composable
private fun HomeSidePanelDragVisual(
    payload: HomeSidePanelDragPayload,
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    when (payload) {
        is HomeSidePanelDragPayload.NewCard -> HomeSidePanelCardCandidateVisual(payload.type)
        is HomeSidePanelDragPayload.ExistingCard -> HomeSidePanelDraggedCard(
            card = state.renderedLayout.cards.single { it.id == payload.cardId },
            panelState = panelState,
        )

        is HomeSidePanelDragPayload.NewAction -> {
            val card = state.renderedLayout.cards.single { it.id == payload.cardId }
            HomeSidePanelDraggedActionItem(
                card = card,
                action = HomeSidePanelActionConfig("drag-preview-action", payload.kind),
            )
        }

        is HomeSidePanelDragPayload.ExistingAction -> {
            val card = state.renderedLayout.cards.single { it.id == payload.cardId }
            val actions = when (card) {
                is HorizontalActionsCardConfig -> card.actions
                is VerticalActionsCardConfig -> card.actions
                else -> error("Action drag payload points at non-action card '${card.id}'")
            }
            HomeSidePanelDraggedActionItem(
                card = card,
                action = actions.single { it.id == payload.actionId },
            )
        }
    }
}

@Composable
private fun HomeSidePanelDraggedCard(
    card: HomeSidePanelCardConfig,
    panelState: HomeSidePanelState,
) {
    when (card) {
        is DateTimeCardConfig -> HomeSidePanelDateTimeCard(
            card = card,
            content = DateTimeCardContent.Runtime,
            editMode = false,
        )

        is WeatherCardConfig -> {
            val runtime = checkNotNull(panelState.runtimeState(card.id)) {
                "Weather card '${card.id}' has no runtime state"
            }
            require(runtime is HomeSidePanelCardRuntimeState.Weather) {
                "Weather card '${card.id}' has mismatched runtime state $runtime"
            }
            HomeSidePanelWeatherCard(
                card = card,
                content = WeatherCardContent.Runtime(runtime.state),
                editMode = false,
                interactionEnabled = false,
            )
        }

        is WalletCardConfig -> {
            val runtime = checkNotNull(panelState.runtimeState(card.id)) {
                "Wallet card '${card.id}' has no runtime state"
            }
            require(runtime is HomeSidePanelCardRuntimeState.Wallet) {
                "Wallet card '${card.id}' has mismatched runtime state $runtime"
            }
            HomeSidePanelWalletCard(
                card = card,
                content = WalletCardContent.Runtime(runtime.state),
                editMode = false,
                interactionEnabled = false,
            )
        }

        is HitokotoCardConfig -> {
            val runtime = checkNotNull(panelState.runtimeState(card.id)) {
                "Hitokoto card '${card.id}' has no runtime state"
            }
            require(runtime is HomeSidePanelCardRuntimeState.Hitokoto) {
                "Hitokoto card '${card.id}' has mismatched runtime state $runtime"
            }
            HomeSidePanelHitokotoCard(
                card = card,
                content = HitokotoCardContent.Runtime(runtime.state),
                editMode = false,
                interactionEnabled = false,
            )
        }

        is ImageCardConfig -> HomeSidePanelImageCard(
            card = card,
            imageFile = panelState.imageFile(card.id),
            editMode = false,
            importing = false,
        )

        is HorizontalActionsCardConfig -> HomeSidePanelHorizontalActionsCard(
            card = card,
            content = HomeSidePanelActionCardContent.Runtime,
            editMode = true,
            interactionEnabled = false,
        )

        is VerticalActionsCardConfig -> HomeSidePanelVerticalActionsCard(
            card = card,
            content = HomeSidePanelActionCardContent.Runtime,
            editMode = true,
            interactionEnabled = false,
        )
    }
}

private fun Modifier.homeSidePanelRootPointerObserver(
    dragState: HomeSidePanelDragState,
    onCommit: (HomeSidePanelDragCommit) -> Unit,
): Modifier = pointerInput(dragState) {
    try {
        awaitPointerEventScope {
            while (true) {
                // Compose 1.12 marks its synthetic ACTION_CANCEL release consumed before Initial;
                // observing here keeps a real UP distinct from later descendant consumption.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    if (change.previousPressed && !change.pressed) {
                        dragState.releaseSourceClaim(change.id.value)
                    }
                }
                val active = dragState.snapshot ?: continue
                val change = event.changes.firstOrNull { it.id.value == active.pointerId } ?: continue
                when (
                    homeSidePanelPointerLifecycleDecision(
                        previousPressed = change.previousPressed,
                        pressed = change.pressed,
                        consumedAtInitialPass = change.isConsumed,
                    )
                ) {
                    HomeSidePanelPointerLifecycleDecision.Continue -> {
                        dragState.updateRootPosition(change.position.x, change.position.y)
                        change.consume()
                    }

                    HomeSidePanelPointerLifecycleDecision.Finish -> {
                        dragState.updateRootPosition(change.position.x, change.position.y)
                        dragState.finish()?.let(onCommit)
                        change.consume()
                    }

                    HomeSidePanelPointerLifecycleDecision.Cancel -> dragState.cancel()
                }
            }
        }
    } finally {
        dragState.cancel()
    }
}

internal fun LayoutCoordinates.homeSidePanelUnclippedBoundsInRoot() =
    findRootCoordinates().localBoundingBoxOf(this, clipBounds = false)

private fun androidx.compose.ui.geometry.Rect.toRootDragBounds(): RootDragBounds =
    RootDragBounds(left, top, right, bottom)

private val HOME_SIDE_PANEL_EDGE_SCROLL_ZONE = 56.dp
private val HOME_SIDE_PANEL_EDGE_SCROLL_STEP = 14.dp
