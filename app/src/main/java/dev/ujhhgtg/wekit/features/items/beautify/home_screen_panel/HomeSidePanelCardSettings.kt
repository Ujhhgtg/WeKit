package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.My_location
import com.composables.icons.materialsymbols.outlined.Person_pin
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.beautify.resolveBeautifyText
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

@Composable
internal fun HomeSidePanelPanelSettings(
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
                BaseWidget(
                    icon = MaterialSymbols.Outlined.Edit,
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_enter_edit_mode),
                    onClick = panelState::enterEditMode,
                    trailingContent = {
                        Icon(MaterialSymbols.Outlined.Chevron_right, contentDescription = null)
                    },
                )
            }
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
internal fun HomeSidePanelDateTimeSettings(
    card: DateTimeCardConfig,
    panelState: HomeSidePanelState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(
            stringResource(R.string.home_side_panel_date_time_settings),
            panelState::closeCardSettings,
        )
        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.home_side_panel_show_lunar_calendar),
                    checked = card.showLunarCalendar,
                    onCheckedChange = {
                        panelState.updateDateTimeLunarCalendar(card.id, it)
                    },
                )
            }
        }
    }
}

@Composable
internal fun HomeSidePanelWeatherSettings(
    card: WeatherCardConfig,
    panelState: HomeSidePanelState,
) {
    val settingsByCard by panelState.weatherSettings.collectAsStateWithLifecycle()
    val settings = settingsByCard[card.id] ?: WeatherSettingsUiState(selectedCity = card.city)
    var query by remember(card.id, settings.searchQuery) { mutableStateOf(settings.searchQuery) }
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
                settings.selectedCity.province,
                settings.selectedCity.city,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { panelState.detectWeatherLocation(card.id) },
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp),
                enabled = !settings.actionInProgress,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.My_location, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.home_side_panel_auto_detect),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            OutlinedButton(
                onClick = { panelState.readWeatherFromProfile(card.id) },
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp),
                enabled = !settings.actionInProgress,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Person_pin, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.home_side_panel_read_profile),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (settings.actionInProgress) {
            Text(
                stringResource(R.string.loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                panelState.searchWeatherCities(card.id, it)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !settings.actionInProgress,
            singleLine = true,
            label = { Text(stringResource(R.string.home_side_panel_search_city)) },
        )
        if (settings.searchResults.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                settings.searchResults.forEachIndexed { index, city ->
                    val selected = city.cityNum == settings.selectedCity.cityNum
                    ListItem(
                        headlineContent = { Text(city.city + city.district.orEmpty()) },
                        supportingContent = { Text("${city.province} · ${city.cityNum}") },
                        trailingContent = { RadioButton(selected = selected, onClick = null) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !settings.actionInProgress) {
                                panelState.updateWeatherCity(card.id, city)
                            },
                    )
                    if (index != settings.searchResults.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelWalletSettings(
    card: WalletCardConfig,
    panelState: HomeSidePanelState,
) {
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
                    checked = card.hideBalanceByDefault,
                    onCheckedChange = { panelState.updateWalletMask(card.id, it) },
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
internal fun HomeSidePanelHitokotoSettings(
    card: HitokotoCardConfig,
    runtime: HitokotoUiState,
    panelState: HomeSidePanelState,
) {
    var draft by remember(card.id, card.settings) { mutableStateOf(card.settings) }
    val lengthUpperBound = remember(card.settings) {
        maxOf(500, card.settings.minLength ?: 0, card.settings.maxLength ?: 0)
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
        if (runtime is HitokotoUiState.Error) {
            Text(
                LocalWeKitLocalizedContext.current.resolveBeautifyText(runtime.message),
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
                onClick = { panelState.updateHitokotoSettings(card.id, draft) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
internal fun SettingsHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
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
