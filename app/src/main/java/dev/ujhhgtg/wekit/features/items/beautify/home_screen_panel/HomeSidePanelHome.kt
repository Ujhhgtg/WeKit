package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Settings
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.TextStatus
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi

internal fun homeSidePanelProfileDisplayName(profile: HomeSidePanelProfile, fallback: String): String =
    profile.nickname.ifBlank { fallback }

@Composable
internal fun HomeSidePanelHome(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
    dragState: HomeSidePanelDragState,
    listState: LazyListState,
) {
    val dragSnapshot = dragState.snapshot
    val draggedCardId = (dragSnapshot?.payload as? HomeSidePanelDragPayload.ExistingCard)?.cardId
    val displayedCards = state.renderedLayout.cards
        .withIndex()
        .filterNot { it.value.id == draggedCardId }
    val cardInsertionIndex = dragSnapshot?.visualCardInsertionIndex(state.renderedLayout.cards)
    val editorMotion = remember { Animatable(0f) }
    var editorMotionDirection by remember { mutableStateOf(1f) }
    var previousRoute by remember { mutableStateOf<HomeSidePanelRoute?>(null) }
    LaunchedEffect(state.route) {
        val previous = previousRoute
        previousRoute = state.route
        when (previous?.let { homeSidePanelTransitionKind(it, state.route) }) {
            HomeSidePanelTransitionKind.ENTER_EDITOR -> {
                editorMotionDirection = 1f
                editorMotion.snapTo(1f)
                editorMotion.animateTo(0f, tween(220))
            }

            HomeSidePanelTransitionKind.EXIT_EDITOR -> {
                editorMotionDirection = -1f
                editorMotion.snapTo(1f)
                editorMotion.animateTo(0f, tween(220))
            }

            else -> Unit
        }
    }
    val editorTranslation = with(LocalDensity.current) { 8.dp.toPx() }
    LazyColumn(
        state = listState,
        userScrollEnabled = dragSnapshot == null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = editorMotion.value
                alpha = 1f - 0.08f * progress
                scaleX = 1f - 0.015f * progress
                scaleY = 1f - 0.015f * progress
                translationY = editorTranslation * progress * editorMotionDirection
            }
            .homeSidePanelDragViewport(dragState),
        contentPadding = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "profile-header") {
            HomeSidePanelProfileHeader(
                profile = state.profile,
                editing = state.editing,
                saveEnabled = state.imageImportingCardIds.isEmpty(),
                panelState = panelState,
                modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 18.dp),
            )
        }
        displayedCards.forEachIndexed { visualIndex, indexedCard ->
            if (cardInsertionIndex == visualIndex) {
                val gapSnapshot = checkNotNull(dragSnapshot)
                item(
                    key = "card-insertion-${gapSnapshot.startToken}-${gapSnapshot.targetChangeToken}",
                ) {
                    HomeSidePanelCardInsertionGap(
                        snapshot = gapSnapshot,
                    )
                }
            }
            val index = indexedCard.index
            val card = indexedCard.value
            val actionAxis = when (card) {
                is HorizontalActionsCardConfig -> HomeSidePanelDragAxis.Horizontal
                is VerticalActionsCardConfig -> HomeSidePanelDragAxis.Vertical
                else -> null
            }
            val cardDragPayload = homeSidePanelExistingDragPayload(
                cardId = card.id,
                source = HomeSidePanelExistingDragSource.CardBackground,
            )
            item(key = card.id) {
                HomeSidePanelLayoutCard(
                    card = card,
                    editMode = state.editing,
                    imageImportingCardIds = state.imageImportingCardIds,
                    panelState = panelState,
                    dragState = dragState,
                    dragSnapshot = dragSnapshot,
                    modifier = Modifier
                        .animateItem(fadeOutSpec = null)
                        .padding(horizontal = 18.dp)
                        .homeSidePanelCardDragTarget(
                            dragState = dragState,
                            cardId = card.id,
                            index = index,
                            actionAxis = actionAxis,
                        )
                        .then(
                            if (state.editing) {
                                Modifier.homeSidePanelDragSource(
                                    dragState = dragState,
                                    payload = cardDragPayload,
                                    descriptionRes = R.string.home_side_panel_drag_card,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        if (cardInsertionIndex == displayedCards.size) {
            val gapSnapshot = checkNotNull(dragSnapshot)
            item(
                key = "card-insertion-${gapSnapshot.startToken}-${gapSnapshot.targetChangeToken}",
            ) {
                HomeSidePanelCardInsertionGap(
                    snapshot = gapSnapshot,
                )
            }
        }
        item(key = "bottom-space") {
            Box(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun HomeSidePanelLayoutCard(
    card: HomeSidePanelCardConfig,
    editMode: Boolean,
    imageImportingCardIds: Set<String>,
    panelState: HomeSidePanelState,
    dragState: HomeSidePanelDragState,
    dragSnapshot: HomeSidePanelDragSnapshot?,
    modifier: Modifier,
) {
    when (card) {
        is DateTimeCardConfig -> HomeSidePanelDateTimeCard(
            card = card,
            content = DateTimeCardContent.Runtime,
            editMode = editMode,
            modifier = modifier,
            onEditCard = panelState::openDateTimeSettings,
            onDeleteCard = panelState::removeCard,
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
                editMode = editMode,
                modifier = modifier,
                onRefresh = panelState::refreshWeather,
                onEditCard = panelState::openWeatherSettings,
                onDeleteCard = panelState::removeCard,
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
                editMode = editMode,
                modifier = modifier,
                onToggleBalance = panelState::toggleWallet,
                onRunAction = panelState::runAction,
                onOpenPaymentCode = panelState::openPaymentCode,
                onEditCard = panelState::openWalletSettings,
                onDeleteCard = panelState::removeCard,
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
                editMode = editMode,
                modifier = modifier,
                onRefresh = panelState::refreshHitokoto,
                onEditCard = panelState::openHitokotoSettings,
                onDeleteCard = panelState::removeCard,
            )
        }

        is ImageCardConfig -> HomeSidePanelImageCard(
            card = card,
            imageFile = panelState.imageFile(card.id),
            editMode = editMode,
            importing = card.id in imageImportingCardIds,
            modifier = modifier,
            onSelectImage = panelState::selectImage,
            onScaleModeChange = panelState::updateImageScaleMode,
            onHeightChange = panelState::updateImageHeight,
            onImageDimensionsChange = panelState::updateImageDimensions,
            onDeleteCard = panelState::removeCard,
        )

        is HorizontalActionsCardConfig -> HomeSidePanelHorizontalActionsCard(
            card = card,
            content = HomeSidePanelActionCardContent.Runtime,
            editMode = editMode,
            modifier = modifier,
            insertionSnapshot = dragSnapshot,
            actionDragModifier = { cardId, actionId ->
                val index = card.actions.indexOfFirst { it.id == actionId }
                Modifier
                    .homeSidePanelActionDragTarget(dragState, cardId, actionId, index)
                    .homeSidePanelDragSource(
                        dragState = dragState,
                        payload = homeSidePanelExistingDragPayload(
                            cardId,
                            HomeSidePanelExistingDragSource.Action(actionId),
                        ),
                        descriptionRes = R.string.home_side_panel_drag_action,
                    )
            },
            actionTerminalModifier = Modifier.homeSidePanelActionTerminalDragTarget(
                dragState = dragState,
                cardId = card.id,
                insertionIndex = card.actions.size,
            ),
            cardDragModifier = Modifier.homeSidePanelDragSource(
                dragState = dragState,
                payload = homeSidePanelExistingDragPayload(
                    card.id,
                    HomeSidePanelExistingDragSource.VirtualAdd,
                ),
                descriptionRes = R.string.home_side_panel_drag_card,
                additionalDescriptionRes = R.string.home_side_panel_add_action,
            ),
            onRunAction = { _, _, kind -> panelState.runAction(kind) },
            onDeleteAction = panelState::removeAction,
            onAddAction = panelState::openAddAction,
            onDeleteCard = panelState::removeCard,
        )

        is VerticalActionsCardConfig -> HomeSidePanelVerticalActionsCard(
            card = card,
            content = HomeSidePanelActionCardContent.Runtime,
            editMode = editMode,
            modifier = modifier,
            insertionSnapshot = dragSnapshot,
            actionDragModifier = { cardId, actionId ->
                val index = card.actions.indexOfFirst { it.id == actionId }
                Modifier
                    .homeSidePanelActionDragTarget(dragState, cardId, actionId, index)
                    .homeSidePanelDragSource(
                        dragState = dragState,
                        payload = homeSidePanelExistingDragPayload(
                            cardId,
                            HomeSidePanelExistingDragSource.Action(actionId),
                        ),
                        descriptionRes = R.string.home_side_panel_drag_action,
                    )
            },
            actionTerminalModifier = Modifier.homeSidePanelActionTerminalDragTarget(
                dragState = dragState,
                cardId = card.id,
                insertionIndex = card.actions.size,
            ),
            cardDragModifier = Modifier.homeSidePanelDragSource(
                dragState = dragState,
                payload = homeSidePanelExistingDragPayload(
                    card.id,
                    HomeSidePanelExistingDragSource.VirtualAdd,
                ),
                descriptionRes = R.string.home_side_panel_drag_card,
                additionalDescriptionRes = R.string.home_side_panel_add_action,
            ),
            onRunAction = { _, _, kind -> panelState.runAction(kind) },
            onDeleteAction = panelState::removeAction,
            onAddAction = panelState::openAddAction,
            onDeleteCard = panelState::removeCard,
        )
    }
}

@Composable
private fun HomeSidePanelProfileHeader(
    profile: HomeSidePanelProfile,
    editing: Boolean,
    saveEnabled: Boolean,
    panelState: HomeSidePanelState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HomeSidePanelProfileAvatar(
            profile = profile,
            size = 58.dp,
            textStyle = MaterialTheme.typography.titleLarge,
            contentDescription = if (editing) {
                null
            } else {
                stringResource(R.string.home_side_panel_open_profile)
            },
            onClick = if (editing) null else panelState::openPersonalProfile,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (editing) {
                        Modifier
                    } else {
                        Modifier.clickable(onClick = panelState::openStatusEditor)
                    },
                )
                .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Text(
                homeSidePanelProfileDisplayName(profile, stringResource(R.string.home_side_panel_wechat_user)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                HomeSidePanelStatus(
                    status = profile.status,
                    panelState = panelState,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!editing) {
                    Icon(
                        MaterialSymbols.Outlined.Chevron_right,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (editing) {
            Row {
                val discardDescription = stringResource(R.string.home_side_panel_discard_editing)
                IconButton(
                    onClick = panelState::discardEditing,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = discardDescription },
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Close,
                        contentDescription = null,
                    )
                }
                val saveDescription = stringResource(R.string.home_side_panel_save_editing)
                IconButton(
                    onClick = panelState::saveEditing,
                    enabled = saveEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = saveDescription },
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Save,
                        contentDescription = null,
                    )
                }
                val addDescription = stringResource(R.string.home_side_panel_add_card)
                IconButton(
                    onClick = panelState::openAddCard,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = addDescription },
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Add,
                        contentDescription = null,
                    )
                }
            }
        } else {
            IconButton(onClick = panelState::openPanelSettings) {
                Icon(
                    MaterialSymbols.Outlined.Settings,
                    contentDescription = stringResource(R.string.home_side_panel_settings),
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelStatus(
    status: HomeSidePanelStatusUiState,
    panelState: HomeSidePanelState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (status == HomeSidePanelStatusUiState.NoStatus) 5.dp else 3.dp,
        ),
    ) {
        when (status) {
            HomeSidePanelStatusUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
            )

            HomeSidePanelStatusUiState.NoStatus -> {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF31B36B)))
                Text(
                    stringResource(R.string.home_side_panel_online),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            is HomeSidePanelStatusUiState.Ready -> {
                HomeSidePanelTextStatusIcon(status.status, 22.dp)
                Text(
                    status.status.description,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HomeSidePanelStatusUiState.Error -> {
                Icon(
                    MaterialSymbols.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.home_side_panel_fetch_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                IconButton(onClick = panelState::refreshStatus, modifier = Modifier.size(24.dp)) {
                    Icon(
                        MaterialSymbols.Outlined.Refresh,
                        contentDescription = stringResource(R.string.home_side_panel_refresh_status),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelToolbarContent(
    profile: HomeSidePanelProfile,
    onAvatarClick: () -> Unit,
    onStatusClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 280.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HomeSidePanelProfileAvatar(
            profile = profile,
            size = 32.dp,
            textStyle = MaterialTheme.typography.labelLarge,
            contentDescription = stringResource(R.string.home_side_panel_open_panel),
            onClick = onAvatarClick,
        )
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onStatusClick)
                .padding(horizontal = 5.dp, vertical = 3.dp),
        ) {
            Text(
                text = homeSidePanelProfileDisplayName(profile, stringResource(R.string.home_side_panel_wechat_user)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                HomeSidePanelToolbarStatus(
                    status = profile.status,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun HomeSidePanelProfileAvatar(
    profile: HomeSidePanelProfile,
    size: Dp,
    textStyle: TextStyle,
    contentDescription: String?,
    onClick: (() -> Unit)?,
) {
    var imageFailed by remember(profile.avatarUrl) { mutableStateOf(false) }
    if (profile.avatarUrl.isNotBlank() && !imageFailed) {
        AsyncImage(
            model = profile.avatarUrl,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) imageFailed = true
            },
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profile.nickname.firstOrNull()?.toString()
                    ?: stringResource(R.string.home_side_panel_fallback_initial),
                style = textStyle,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun HomeSidePanelToolbarStatus(
    status: HomeSidePanelStatusUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (status == HomeSidePanelStatusUiState.NoStatus) 3.dp else 2.dp,
        ),
    ) {
        when (status) {
            HomeSidePanelStatusUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(9.dp), strokeWidth = 1.5.dp)
                HomeSidePanelToolbarStatusText(stringResource(R.string.loading))
            }

            HomeSidePanelStatusUiState.NoStatus -> {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF31B36B)))
                HomeSidePanelToolbarStatusText(stringResource(R.string.home_side_panel_online))
            }

            is HomeSidePanelStatusUiState.Ready -> {
                HomeSidePanelTextStatusIcon(status.status, 18.dp)
                HomeSidePanelToolbarStatusText(status.status.description)
            }

            HomeSidePanelStatusUiState.Error -> {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                HomeSidePanelToolbarStatusText(
                    stringResource(R.string.home_side_panel_fetch_failed),
                    MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelTextStatusIcon(status: TextStatus, size: Dp) {
    val iconTint = MaterialTheme.colorScheme.onSurface.toArgb()
    key(status.iconId) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = status.description
                    WeTextStatusApi.renderIcon(this, status.iconId)
                    setColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                }
            },
            update = { imageView ->
                imageView.contentDescription = status.description
                imageView.setColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
            },
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun HomeSidePanelToolbarStatusText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
