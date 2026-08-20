package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.My_location
import com.composables.icons.materialsymbols.outlined.Person_pin
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Settings
import dev.ujhhgtg.wekit.features.api.core.TextStatus
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
import dev.ujhhgtg.wekit.features.items.beautify.resolveBeautifyText
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

internal fun homeSidePanelProfileDisplayName(profile: HomeSidePanelProfile, fallback: String): String =
    profile.nickname.ifBlank { fallback }

private val LEGACY_DATE_TIME_CARD = DateTimeCardConfig("legacy-date-time")
private val LEGACY_WEATHER_CARD = WeatherCardConfig("legacy-weather", DEFAULT_WEATHER_CITY)
private val LEGACY_WALLET_CARD = WalletCardConfig("legacy-wallet")
private val LEGACY_HITOKOTO_CARD = HitokotoCardConfig("legacy-hitokoto")
private val LEGACY_HORIZONTAL_ACTIONS_CARD = HorizontalActionsCardConfig(
    id = "legacy-horizontal-actions",
    actions = listOf(
        HomeSidePanelActionConfig("legacy-scan", HomeSidePanelActionKind.SCAN),
        HomeSidePanelActionConfig("legacy-wallet-action", HomeSidePanelActionKind.WALLET),
        HomeSidePanelActionConfig("legacy-favorites", HomeSidePanelActionKind.FAVORITES),
    ),
)
private val LEGACY_VERTICAL_ACTIONS_CARD = VerticalActionsCardConfig(
    id = "legacy-vertical-actions",
    actions = listOf(
        HomeSidePanelActionConfig("legacy-moments", HomeSidePanelActionKind.MOMENTS),
        HomeSidePanelActionConfig("legacy-channels", HomeSidePanelActionKind.CHANNELS),
        HomeSidePanelActionConfig("legacy-mark-all-read", HomeSidePanelActionKind.MARK_ALL_READ),
        HomeSidePanelActionConfig("legacy-wekit-settings", HomeSidePanelActionKind.WEKIT_SETTINGS),
    ),
)

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
        when (panelState.route) {
            HomeSidePanelRoute.Home -> HomeSidePanelHome(state, panelState)
            HomeSidePanelRoute.LegacyWeatherSettings -> HomeSidePanelWeatherSettings(state, panelState)
            HomeSidePanelRoute.LegacyWalletSettings -> HomeSidePanelWalletSettings(state.wallet, panelState)
            HomeSidePanelRoute.LegacyHitokotoSettings -> HomeSidePanelHitokotoSettings(state, panelState)
            HomeSidePanelRoute.PanelSettings -> HomeSidePanelPanelSettings(state, panelState)
            HomeSidePanelRoute.EditHome,
            HomeSidePanelRoute.AddCard,
            is HomeSidePanelRoute.WeatherSettings,
            is HomeSidePanelRoute.WalletSettings,
            is HomeSidePanelRoute.HitokotoSettings,
            is HomeSidePanelRoute.AddAction,
            -> error("Route ${panelState.route} is not integrated into the legacy panel UI")
        }
    }
}

@Composable
private fun HomeSidePanelHome(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HomeSidePanelProfileHeader(state.profile, panelState)
        HomeSidePanelDateTimeCard(
            card = LEGACY_DATE_TIME_CARD,
            content = DateTimeCardContent.Runtime,
            editMode = false,
        )
        HomeSidePanelWeatherCard(
            card = LEGACY_WEATHER_CARD.copy(city = state.weatherSettings.selectedCity),
            content = WeatherCardContent.Runtime(state.weather),
            editMode = false,
            onRefresh = { panelState.refreshWeather() },
        )
        HomeSidePanelWalletCard(
            card = LEGACY_WALLET_CARD,
            content = WalletCardContent.Runtime(state.wallet),
            editMode = false,
            onToggleBalance = { panelState.toggleWalletBalance() },
            onRunAction = panelState::runAction,
        )
        HomeSidePanelHorizontalActionsCard(
            card = LEGACY_HORIZONTAL_ACTIONS_CARD,
            content = HomeSidePanelActionCardContent.Runtime,
            editMode = false,
            onRunAction = { _, _, kind -> panelState.runAction(kind) },
        )
        HomeSidePanelVerticalActionsCard(
            card = LEGACY_VERTICAL_ACTIONS_CARD,
            content = HomeSidePanelActionCardContent.Runtime,
            editMode = false,
            onRunAction = { _, _, kind -> panelState.runAction(kind) },
        )
        HomeSidePanelHitokotoCard(
            card = LEGACY_HITOKOTO_CARD.copy(settings = state.hitokotoSettings),
            content = HitokotoCardContent.Runtime(state.hitokoto),
            editMode = false,
            onRefresh = { panelState.fetchAnotherHitokoto() },
        )
    }
}

@Composable
private fun HomeSidePanelProfileHeader(
    profile: HomeSidePanelProfile,
    panelState: HomeSidePanelState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HomeSidePanelProfileAvatar(
            profile = profile,
            size = 58.dp,
            textStyle = MaterialTheme.typography.titleLarge,
            contentDescription = stringResource(R.string.home_side_panel_open_profile),
            onClick = panelState::openPersonalProfile,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = panelState::openStatusEditor)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Text(
                homeSidePanelProfileDisplayName(profile, stringResource(R.string.home_side_panel_wechat_user)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                HomeSidePanelStatus(
                    status = profile.status,
                    panelState = panelState,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = panelState::openPanelSettings) {
            Icon(MaterialSymbols.Outlined.Settings, contentDescription = stringResource(R.string.home_side_panel_settings))
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
            HomeSidePanelStatusUiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
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
                Icon(MaterialSymbols.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.home_side_panel_fetch_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                IconButton(onClick = panelState::refreshStatus, modifier = Modifier.size(24.dp)) {
                    Icon(MaterialSymbols.Outlined.Refresh, contentDescription = stringResource(R.string.home_side_panel_refresh_status), modifier = Modifier.size(16.dp))
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
    contentDescription: String,
    onClick: () -> Unit,
) {
    var imageFailed by remember(profile.avatarUrl) { mutableStateOf(false) }
    if (profile.avatarUrl.isNotBlank() && !imageFailed) {
        AsyncImage(
            model = profile.avatarUrl,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(onClick = onClick),
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
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profile.nickname.firstOrNull()?.toString() ?: stringResource(R.string.home_side_panel_fallback_initial),
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
                HomeSidePanelToolbarStatusText(stringResource(R.string.home_side_panel_fetch_failed), MaterialTheme.colorScheme.error)
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

@Composable
private fun HomeSidePanelWalletSettings(
    wallet: HomeSidePanelWalletUiState,
    panelState: HomeSidePanelState,
) {
    val hideBalance = wallet.displayState.defaultMaskEnabled
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(stringResource(R.string.home_side_panel_wallet_settings), panelState::closeCardSettings)
        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_hide_balance_default),
                    description = stringResource(R.string.home_side_panel_hide_balance_summary),
                    checked = hideBalance,
                    onCheckedChange = panelState::setHideWalletBalance,
                )
            }
        }
        Text(
            stringResource(R.string.home_side_panel_hide_balance_details),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun HomeSidePanelPanelSettings(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(stringResource(R.string.home_side_panel_settings), panelState::closeCardSettings)
        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_show_toolbar_profile),
                    checked = state.showToolbarProfile,
                    onCheckedChange = panelState::setShowToolbarProfile,
                )
            }
            item {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_hide_wechat_title),
                    checked = state.hideWeChatTitle,
                    enabled = state.showToolbarProfile,
                    onCheckedChange = panelState::setHideWeChatTitle,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelWeatherSettings(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    var query by remember(state.weatherSettings.searchQuery) {
        mutableStateOf(state.weatherSettings.searchQuery)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(stringResource(R.string.home_side_panel_weather_settings), panelState::closeCardSettings)
        Text(
            stringResource(
                R.string.home_side_panel_current_city,
                state.weatherSettings.selectedCity.province,
                state.weatherSettings.selectedCity.city,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = panelState::detectWeatherLocation,
                modifier = Modifier.weight(1f).height(72.dp),
                enabled = !state.weatherSettings.actionInProgress,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.My_location, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.home_side_panel_auto_detect), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            OutlinedButton(
                onClick = panelState::readWeatherFromProfile,
                modifier = Modifier.weight(1f).height(72.dp),
                enabled = !state.weatherSettings.actionInProgress,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Person_pin, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.home_side_panel_read_profile), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                panelState.searchWeatherCities(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.home_side_panel_search_city)) },
        )
        if (state.weatherSettings.searchResults.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                state.weatherSettings.searchResults.forEachIndexed { index, city ->
                    val selected = city.cityNum == state.weatherSettings.selectedCity.cityNum
                    ListItem(
                        headlineContent = { Text(city.city + city.district.orEmpty()) },
                        supportingContent = { Text("${city.province} · ${city.cityNum}") },
                        trailingContent = {
                            RadioButton(selected = selected, onClick = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { panelState.selectWeatherCity(city) },
                    )
                    if (index != state.weatherSettings.searchResults.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSidePanelHitokotoSettings(
    state: HomeSidePanelUiState,
    panelState: HomeSidePanelState,
) {
    var draft by remember(state.hitokotoSettings) { mutableStateOf(state.hitokotoSettings) }
    val lengthUpperBound = remember(state.hitokotoSettings) {
        maxOf(500, state.hitokotoSettings.minLength ?: 0, state.hitokotoSettings.maxLength ?: 0)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(stringResource(R.string.home_side_panel_hitokoto_settings), panelState::closeCardSettings)
        Text(stringResource(R.string.home_side_panel_categories), style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hitokotoCategoryLabels.forEach { (code, labelRes) ->
                FilterChip(
                    selected = code in draft.categories,
                    onClick = {
                        draft = draft.copy(
                            categories = if (code in draft.categories) {
                                draft.categories - code
                            } else {
                                draft.categories + code
                            },
                        )
                    },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item {
                BaseItemContainer {
                    IntNumberPickerWidget(
                        title = stringResource(R.string.home_side_panel_min_length),
                        value = draft.minLength ?: 0,
                        startInt = 0,
                        endInt = lengthUpperBound,
                        stepSize = 1,
                        subduedValue = draft.minLength == null,
                        onValueClick = {
                            draft = draft.copy(minLength = if (draft.minLength == null) 0 else null)
                        },
                        onValueChange = { draft = draft.copy(minLength = it) },
                    )
                }
            }
            item {
                BaseItemContainer {
                    IntNumberPickerWidget(
                        title = stringResource(R.string.home_side_panel_max_length),
                        value = draft.maxLength ?: 0,
                        startInt = 0,
                        endInt = lengthUpperBound,
                        stepSize = 1,
                        subduedValue = draft.maxLength == null,
                        onValueClick = {
                            draft = draft.copy(maxLength = if (draft.maxLength == null) 0 else null)
                        },
                        onValueChange = { draft = draft.copy(maxLength = it) },
                    )
                }
            }
            item {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_show_source),
                    checked = draft.showSource,
                    onCheckedChange = { draft = draft.copy(showSource = it) },
                )
            }
            item {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_show_author),
                    checked = draft.showAuthor,
                    onCheckedChange = { draft = draft.copy(showAuthor = it) },
                )
            }
        }
        if (state.hitokoto is HitokotoUiState.Error) {
            Text(
                LocalWeKitLocalizedContext.current.resolveBeautifyText(state.hitokoto.message),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { draft = HitokotoSettings() },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_restore_defaults))
            }
            Button(
                onClick = { panelState.saveHitokotoSettings(draft) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription = stringResource(R.string.action_back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

private val hitokotoCategoryLabels = linkedMapOf(
    "a" to R.string.home_side_panel_hitokoto_animation,
    "b" to R.string.home_side_panel_hitokoto_comics,
    "c" to R.string.home_side_panel_hitokoto_games,
    "d" to R.string.home_side_panel_hitokoto_literature,
    "e" to R.string.home_side_panel_hitokoto_original,
    "f" to R.string.home_side_panel_hitokoto_web,
    "g" to R.string.home_side_panel_hitokoto_other,
    "h" to R.string.home_side_panel_hitokoto_movies,
    "i" to R.string.home_side_panel_hitokoto_poetry,
    "j" to R.string.home_side_panel_hitokoto_netease_music,
    "k" to R.string.home_side_panel_hitokoto_philosophy,
    "l" to R.string.home_side_panel_hitokoto_witty,
)
