package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import java.time.LocalDateTime

internal data class HomeSidePanelCandidatePointer(
    val pointerId: Long,
    val rootX: Float,
    val rootY: Float,
    val anchorX: Float,
    val anchorY: Float,
    val sourceLeft: Float,
    val sourceTop: Float,
    val sourceRight: Float,
    val sourceBottom: Float,
)

internal sealed interface HomeSidePanelAddCandidate {
    val pointer: HomeSidePanelCandidatePointer

    data class Card(
        val type: HomeSidePanelCardType,
        override val pointer: HomeSidePanelCandidatePointer,
    ) : HomeSidePanelAddCandidate

    data class Action(
        val cardId: String,
        val kind: HomeSidePanelActionKind,
        override val pointer: HomeSidePanelCandidatePointer,
    ) : HomeSidePanelAddCandidate
}

internal val HOME_SIDE_PANEL_PREVIEW_TIME: LocalDateTime =
    LocalDateTime.of(2026, 8, 20, 12, 34)

internal val HOME_SIDE_PANEL_PREVIEW_WEATHER = WeatherSnapshot(
    city = DEFAULT_WEATHER_CITY,
    weatherCode = "0",
    temperature = "26",
    feelsLike = "27",
    high = "30",
    low = "21",
    humidity = "48",
    windSpeed = "8",
    publishedAt = "12:00",
    fetchedAt = 0L,
)

private val HOME_SIDE_PANEL_PREVIEW_HITOKOTO = HitokotoSnapshot(
    uuid = "preview-hitokoto",
    text = "The best way out is always through.",
    type = "k",
    source = "A Servant to Servants",
    author = "Robert Frost",
    creator = null,
    createdAt = null,
    fetchedAt = 0L,
)

private val HOME_SIDE_PANEL_CARD_TYPES = listOf(
    HomeSidePanelCardType.DATE_TIME,
    HomeSidePanelCardType.WEATHER,
    HomeSidePanelCardType.WALLET,
    HomeSidePanelCardType.HITOKOTO,
    HomeSidePanelCardType.IMAGE,
    HomeSidePanelCardType.HORIZONTAL_ACTIONS,
    HomeSidePanelCardType.VERTICAL_ACTIONS,
)

private val HOME_SIDE_PANEL_ACTION_KINDS = listOf(
    HomeSidePanelActionKind.ADD_FRIEND,
    HomeSidePanelActionKind.SCAN,
    HomeSidePanelActionKind.MOMENTS,
    HomeSidePanelActionKind.WALLET,
    HomeSidePanelActionKind.CHANNELS,
    HomeSidePanelActionKind.WECHAT_SETTINGS,
    HomeSidePanelActionKind.FAVORITES,
    HomeSidePanelActionKind.WEKIT_SETTINGS,
    HomeSidePanelActionKind.RESTART_WECHAT,
    HomeSidePanelActionKind.FORCE_STOP_WECHAT,
    HomeSidePanelActionKind.MARK_ALL_READ,
)

@Composable
internal fun HomeSidePanelAddCardPage(
    onBack: () -> Unit,
    onAddCard: (HomeSidePanelCardType) -> Unit,
    onLongPressCard: (HomeSidePanelCardType, HomeSidePanelCandidatePointer) -> Unit,
) {
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.home_side_panel_add_card),
            onBack = onBack,
            modifier = Modifier
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues())
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = 4.dp,
                end = 18.dp,
                bottom = 18.dp + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(HOME_SIDE_PANEL_CARD_TYPES, key = HomeSidePanelCardType::name) { type ->
                HomeSidePanelCardCandidate(
                    type = type,
                    modifier = Modifier.homeSidePanelCandidateLongPress(
                        descriptionRes = R.string.home_side_panel_drag_add_card,
                    ) { pointer -> onLongPressCard(type, pointer) },
                    onClick = { onAddCard(type) },
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelCardCandidate(
    type: HomeSidePanelCardType,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    HomeSidePanelCardCandidateVisual(
        type = type,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun HomeSidePanelCardCandidateVisual(
    type: HomeSidePanelCardType,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(homeSidePanelCardNameRes(type)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        when (type) {
            HomeSidePanelCardType.DATE_TIME -> HomeSidePanelDateTimeCard(
                card = DateTimeCardConfig("preview-date-time"),
                content = DateTimeCardContent.Preview(HOME_SIDE_PANEL_PREVIEW_TIME),
                editMode = false,
            )

            HomeSidePanelCardType.WEATHER -> HomeSidePanelWeatherCard(
                card = WeatherCardConfig("preview-weather", DEFAULT_WEATHER_CITY),
                content = WeatherCardContent.Preview(HOME_SIDE_PANEL_PREVIEW_WEATHER),
                editMode = false,
            )

            HomeSidePanelCardType.WALLET -> HomeSidePanelWalletCard(
                card = WalletCardConfig("preview-wallet"),
                content = WalletCardContent.Preview("¥ 1,234.56"),
                editMode = false,
            )

            HomeSidePanelCardType.HITOKOTO -> HomeSidePanelHitokotoCard(
                card = HitokotoCardConfig("preview-hitokoto"),
                content = HitokotoCardContent.Preview(HOME_SIDE_PANEL_PREVIEW_HITOKOTO),
                editMode = false,
            )

            HomeSidePanelCardType.IMAGE -> HomeSidePanelImageCard(
                card = ImageCardConfig("preview-image"),
                imageFile = null,
                editMode = false,
                importing = false,
                preview = true,
            )

            HomeSidePanelCardType.HORIZONTAL_ACTIONS -> HomeSidePanelHorizontalActionsCard(
                card = HorizontalActionsCardConfig(
                    id = "preview-horizontal-actions",
                    actions = listOf(
                        HomeSidePanelActionConfig("preview-scan", HomeSidePanelActionKind.SCAN),
                        HomeSidePanelActionConfig("preview-wallet-action", HomeSidePanelActionKind.WALLET),
                        HomeSidePanelActionConfig("preview-favorites", HomeSidePanelActionKind.FAVORITES),
                    ),
                ),
                content = HomeSidePanelActionCardContent.Preview,
                editMode = false,
            )

            HomeSidePanelCardType.VERTICAL_ACTIONS -> HomeSidePanelVerticalActionsCard(
                card = VerticalActionsCardConfig(
                    id = "preview-vertical-actions",
                    actions = listOf(
                        HomeSidePanelActionConfig("preview-moments", HomeSidePanelActionKind.MOMENTS),
                        HomeSidePanelActionConfig("preview-channels", HomeSidePanelActionKind.CHANNELS),
                        HomeSidePanelActionConfig("preview-mark-read", HomeSidePanelActionKind.MARK_ALL_READ),
                        HomeSidePanelActionConfig("preview-wekit", HomeSidePanelActionKind.WEKIT_SETTINGS),
                    ),
                ),
                content = HomeSidePanelActionCardContent.Preview,
                editMode = false,
            )
        }
    }
}

@Composable
internal fun HomeSidePanelAddActionPage(
    card: HomeSidePanelCardConfig,
    onBack: () -> Unit,
    onAddAction: (String, HomeSidePanelActionKind) -> Unit,
    onLongPressAction: (
        cardId: String,
        kind: HomeSidePanelActionKind,
        pointer: HomeSidePanelCandidatePointer,
    ) -> Unit,
) {
    require(card is HorizontalActionsCardConfig || card is VerticalActionsCardConfig) {
        "Card '${card.id}' is ${card.type}; expected an action card"
    }
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.home_side_panel_add_action),
            onBack = onBack,
            modifier = Modifier
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues())
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = 4.dp,
                end = 18.dp,
                bottom = 18.dp + bottomInset,
            ),
        ) {
            item {
                if (card is HorizontalActionsCardConfig) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        maxItemsInEachRow = 2,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HOME_SIDE_PANEL_ACTION_KINDS.forEach { kind ->
                            HomeSidePanelActionCandidateTile(
                                kind = kind,
                                modifier = Modifier
                                    .weight(1f)
                                    .homeSidePanelCandidateLongPress(
                                        descriptionRes = R.string.home_side_panel_drag_add_action,
                                    ) { pointer -> onLongPressAction(card.id, kind, pointer) },
                                onClick = { onAddAction(card.id, kind) },
                            )
                        }
                    }
                } else {
                    HomeSidePanelActionCandidateList(
                        cardId = card.id,
                        onAddAction = onAddAction,
                        onLongPressAction = onLongPressAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSidePanelActionCandidateTile(
    kind: HomeSidePanelActionKind,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val spec = homeSidePanelActionSpec(kind)
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(spec.labelRes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HomeSidePanelActionCandidateList(
    cardId: String,
    onAddAction: (String, HomeSidePanelActionKind) -> Unit,
    onLongPressAction: (String, HomeSidePanelActionKind, HomeSidePanelCandidatePointer) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        HOME_SIDE_PANEL_ACTION_KINDS.forEachIndexed { index, kind ->
            val spec = homeSidePanelActionSpec(kind)
            ListItem(
                headlineContent = { Text(stringResource(spec.labelRes)) },
                leadingContent = {
                    Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .homeSidePanelCandidateLongPress(
                        descriptionRes = R.string.home_side_panel_drag_add_action,
                    ) { pointer -> onLongPressAction(cardId, kind, pointer) }
                    .clickable { onAddAction(cardId, kind) },
            )
            if (index != HOME_SIDE_PANEL_ACTION_KINDS.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}

@StringRes
internal fun homeSidePanelCardNameRes(type: HomeSidePanelCardType): Int = when (type) {
    HomeSidePanelCardType.DATE_TIME -> R.string.home_side_panel_card_date_time
    HomeSidePanelCardType.WEATHER -> R.string.home_side_panel_card_weather
    HomeSidePanelCardType.WALLET -> R.string.home_side_panel_card_wallet
    HomeSidePanelCardType.HITOKOTO -> R.string.home_side_panel_card_hitokoto
    HomeSidePanelCardType.IMAGE -> R.string.home_side_panel_card_image
    HomeSidePanelCardType.HORIZONTAL_ACTIONS -> R.string.home_side_panel_card_horizontal_actions
    HomeSidePanelCardType.VERTICAL_ACTIONS -> R.string.home_side_panel_card_vertical_actions
}

private fun Modifier.homeSidePanelCandidateLongPress(
    @StringRes descriptionRes: Int,
    onLongPress: (HomeSidePanelCandidatePointer) -> Unit,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val description = stringResource(descriptionRes)
    onGloballyPositioned { coordinates = it }
        .semantics { contentDescription = description }
        .pointerInput(onLongPress) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val bounds = coordinates?.homeSidePanelUnclippedBoundsInRoot() ?: return@awaitEachGesture
                onLongPress(longPress.toCandidatePointer(bounds.topLeft, bounds.left, bounds.top, bounds.right, bounds.bottom))
                longPress.consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach(PointerInputChange::consume)
                } while (event.changes.any(PointerInputChange::pressed))
            }
        }
}

private fun PointerInputChange.toCandidatePointer(
    rootOrigin: Offset,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): HomeSidePanelCandidatePointer = HomeSidePanelCandidatePointer(
    pointerId = id.value,
    rootX = rootOrigin.x + position.x,
    rootY = rootOrigin.y + position.y,
    anchorX = position.x,
    anchorY = position.y,
    sourceLeft = left,
    sourceTop = top,
    sourceRight = right,
    sourceBottom = bottom,
)
