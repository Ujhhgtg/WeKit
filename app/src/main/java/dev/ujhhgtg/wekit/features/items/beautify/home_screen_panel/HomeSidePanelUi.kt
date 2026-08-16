package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Air
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Bookmark
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Cloudy_snowing
import com.composables.icons.materialsymbols.outlined.Cyclone
import com.composables.icons.materialsymbols.outlined.Device_thermostat
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Foggy
import com.composables.icons.materialsymbols.outlined.Format_quote
import com.composables.icons.materialsymbols.outlined.Grain
import com.composables.icons.materialsymbols.outlined.Humidity_percentage
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Mark_chat_read
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.My_location
import com.composables.icons.materialsymbols.outlined.Partly_cloudy_day
import com.composables.icons.materialsymbols.outlined.Person_pin
import com.composables.icons.materialsymbols.outlined.Qr_code_scanner
import com.composables.icons.materialsymbols.outlined.Question_mark
import com.composables.icons.materialsymbols.outlined.Rainy
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Rainy_heavy
import com.composables.icons.materialsymbols.outlined.Rainy_light
import com.composables.icons.materialsymbols.outlined.Rainy_snow
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Snowing
import com.composables.icons.materialsymbols.outlined.Snowing_heavy
import com.composables.icons.materialsymbols.outlined.Storm
import com.composables.icons.materialsymbols.outlined.Sunny
import com.composables.icons.materialsymbols.outlined.Sunny_snowing
import com.composables.icons.materialsymbols.outlined.Thunderstorm
import com.composables.icons.materialsymbols.outlined.Tornado
import com.composables.icons.materialsymbols.outlined.Wallet
import com.composables.icons.materialsymbols.outlined.Weather_hail
import com.composables.icons.materialsymbols.outlined.Weather_snowy
import dev.ujhhgtg.wekit.features.api.core.TextStatus
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
import dev.ujhhgtg.wekit.features.items.beautify.resolveBeautifyText
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal enum class HomeSidePanelIconKind {
    QR_CODE_SCANNER,
    WALLET,
    BOOKMARK,
    CAMERA,
    MOVIE,
    MARK_CHAT_READ,
    EXTENSION,
}

internal enum class HomeSidePanelShortcutPlacement {
    TILE,
    LIST_ITEM,
}

internal data class HomeSidePanelShortcutSpec(
    val shortcut: HomeSidePanelShortcut,
    @StringRes val labelRes: Int,
    val icon: HomeSidePanelIconKind,
    val placement: HomeSidePanelShortcutPlacement,
)

internal fun weatherCardSnapshot(state: WeatherUiState): WeatherSnapshot? = when (state) {
    is WeatherUiState.Ready -> state.snapshot
    is WeatherUiState.Error -> state.cached
    WeatherUiState.Loading -> null
}

internal fun homeSidePanelProfileDisplayName(profile: HomeSidePanelProfile, fallback: String): String =
    profile.nickname.ifBlank { fallback }

internal fun shortcutSpec(shortcut: HomeSidePanelShortcut): HomeSidePanelShortcutSpec = when (shortcut) {
    HomeSidePanelShortcut.SCAN -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_scan, HomeSidePanelIconKind.QR_CODE_SCANNER, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.PAYMENTS -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_payments, HomeSidePanelIconKind.WALLET, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.FAVORITES -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_favorites, HomeSidePanelIconKind.BOOKMARK, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.MOMENTS -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_moments, HomeSidePanelIconKind.CAMERA, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.VIDEO_CHANNELS -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_channels, HomeSidePanelIconKind.MOVIE, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.MARK_ALL_READ -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_mark_all_read, HomeSidePanelIconKind.MARK_CHAT_READ, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.WEKIT_SETTINGS -> HomeSidePanelShortcutSpec(shortcut, R.string.home_side_panel_wekit_settings, HomeSidePanelIconKind.EXTENSION, HomeSidePanelShortcutPlacement.LIST_ITEM)
}

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
            HomeSidePanelRoute.HOME -> HomeSidePanelHome(state, panelState)
            HomeSidePanelRoute.WEATHER_SETTINGS -> HomeSidePanelWeatherSettings(state, panelState)
            HomeSidePanelRoute.WALLET_SETTINGS -> HomeSidePanelWalletSettings(state.wallet, panelState)
            HomeSidePanelRoute.HITOKOTO_SETTINGS -> HomeSidePanelHitokotoSettings(state, panelState)
            HomeSidePanelRoute.PANEL_SETTINGS -> HomeSidePanelPanelSettings(state, panelState)
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
        HomeSidePanelDateTimeCard()
        HomeSidePanelWeatherCard(state.weather, panelState)
        HomeSidePanelWalletCard(state.wallet, panelState)
        HomeSidePanelShortcutList(panelState)
        HomeSidePanelHitokotoCard(state.hitokoto, state.hitokotoSettings, panelState)
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
private fun HomeSidePanelDateTimeCard() {
    val now = rememberHomeSidePanelNow()
    val localizedContext = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    now.format(HOME_SIDE_PANEL_TIME_FORMATTER),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    now.format(
                        DateTimeFormatter.ofPattern(
                            stringResource(R.string.home_side_panel_date_pattern),
                            localizedContext.resources.configuration.locales[0],
                        )
                    ),
                    modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stringResource(greetingResForHour(now.hour)), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSidePanelWeatherCard(
    weather: WeatherUiState,
    panelState: HomeSidePanelState,
) {
    val snapshot = weatherCardSnapshot(weather)
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = panelState::refreshWeather,
                onLongClick = panelState::openWeatherSettings,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        val location = snapshot?.city?.let { city ->
            listOfNotNull(city.city, city.district?.takeIf(String::isNotBlank))
                .distinct()
                .joinToString(" · ")
        } ?: stringResource(R.string.home_side_panel_weather)
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 17.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MaterialSymbols.Outlined.Location_on,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
                Text(
                    location,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 5.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                snapshot?.let {
                    Text(
                        stringResource(R.string.home_side_panel_updated_at, formatWeatherPublishedAt(it.publishedAt)),
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .widthIn(max = 112.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (snapshot != null) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${snapshot.temperature}°",
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = contentColor,
                                        maxLines = 1,
                                    )
                                    Text(
                                        stringResource(R.string.home_side_panel_feels_like, snapshot.feelsLike),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.72f),
                                    )
                                }
                                Column(
                                    modifier = Modifier.widthIn(min = 96.dp, max = 120.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        weatherIcon(snapshot.weatherCode),
                                        contentDescription = null,
                                        modifier = Modifier.size(52.dp),
                                        tint = contentColor,
                                    )
                                    Text(
                                        stringResource(weatherDescriptionRes(snapshot.weatherCode)),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = contentColor,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else if (weather is WeatherUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = contentColor,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Text(
                                stringResource(R.string.home_side_panel_no_weather_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                            )
                        }
                    }
                    HorizontalDivider(color = contentColor.copy(alpha = 0.14f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeSidePanelWeatherMetric(
                            icon = MaterialSymbols.Outlined.Device_thermostat,
                            value = snapshot?.let { "${it.high}° / ${it.low}°" } ?: "-- / --",
                            label = stringResource(R.string.home_side_panel_high_low),
                            modifier = Modifier.weight(1f),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 10.dp),
                            color = contentColor.copy(alpha = 0.12f),
                        )
                        HomeSidePanelWeatherMetric(
                            icon = MaterialSymbols.Outlined.Humidity_percentage,
                            value = snapshot?.let { "${it.humidity}%" } ?: "--",
                            label = stringResource(R.string.home_side_panel_humidity),
                            modifier = Modifier.weight(1f),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 10.dp),
                            color = contentColor.copy(alpha = 0.12f),
                        )
                        HomeSidePanelWeatherMetric(
                            icon = MaterialSymbols.Outlined.Air,
                            value = snapshot?.let { "${it.windSpeed} km/h" } ?: "--",
                            label = stringResource(R.string.home_side_panel_wind_speed),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (weather is WeatherUiState.Ready && weather.refreshing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = contentColor,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSidePanelWalletCard(
    wallet: HomeSidePanelWalletUiState,
    panelState: HomeSidePanelState,
) {
    val shape = RoundedCornerShape(24.dp)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = panelState::toggleWalletBalance,
                onLongClick = panelState::openWalletSettings,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    MaterialSymbols.Outlined.Wallet,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor,
                )
                Text(
                    stringResource(R.string.home_side_panel_current_balance),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = wallet.displayBalance,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (wallet.displayState.isMasked) 4.sp else 0.sp,
                    color = contentColor,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { panelState.runShortcut(HomeSidePanelShortcut.SCAN) },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Qr_code_scanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.home_side_panel_scan), modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
                Button(
                    onClick = { panelState.runShortcut(HomeSidePanelShortcut.PAYMENTS) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Wallet,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.home_side_panel_payment_code), modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HomeSidePanelWeatherMetric(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor,
        )
        Column(modifier = Modifier.padding(start = 6.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberHomeSidePanelNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current
            val nextMinute = current.plusMinutes(1).withSecond(0).withNano(0)
            delay(Duration.between(current, nextMinute).toMillis().coerceAtLeast(1L))
        }
    }
    return now
}

@Composable
private fun HomeSidePanelShortcutList(panelState: HomeSidePanelState) {
    val tiles = HomeSidePanelShortcut.entries.filter { shortcutSpec(it).placement == HomeSidePanelShortcutPlacement.TILE }
    val listItems = HomeSidePanelShortcut.entries.filter { shortcutSpec(it).placement == HomeSidePanelShortcutPlacement.LIST_ITEM }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        tiles.forEach { shortcut ->
            val spec = shortcutSpec(shortcut)
            val shape = RoundedCornerShape(18.dp)
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .combinedClickable(onClick = { panelState.runShortcut(shortcut) }, onLongClick = null),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(shortcutIcon(spec.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(spec.labelRes), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        listItems.forEachIndexed { index, shortcut ->
            val spec = shortcutSpec(shortcut)
            ListItem(
                headlineContent = { Text(stringResource(spec.labelRes)) },
                leadingContent = {
                    Icon(shortcutIcon(spec.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { panelState.runShortcut(shortcut) }, onLongClick = null),
            )
            if (index != listItems.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSidePanelHitokotoCard(
    hitokoto: HitokotoUiState,
    settings: HitokotoSettings,
    panelState: HomeSidePanelState,
) {
    val snapshot = when (hitokoto) {
        is HitokotoUiState.Ready -> hitokoto.snapshot
        is HitokotoUiState.Error -> hitokoto.cached
        else -> null
    }
    val shape = RoundedCornerShape(22.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = panelState::fetchAnotherHitokoto,
                onLongClick = panelState::openHitokotoSettings,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Outlined.Format_quote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.home_side_panel_hitokoto), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = snapshot?.text ?: stringResource(
                            if (hitokoto is HitokotoUiState.Loading) {
                                R.string.home_side_panel_hitokoto_loading
                            } else {
                                R.string.home_side_panel_hitokoto_tap
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (snapshot != null && (settings.showSource || settings.showAuthor)) {
                        val author = snapshot.author?.trim()?.takeIf { settings.showAuthor && it.isNotEmpty() }
                        val source = snapshot.source?.trim()?.takeIf { settings.showSource && it.isNotEmpty() }
                        val attribution = when {
                            author != null && source != null -> stringResource(
                                R.string.home_side_panel_attribution_author_source,
                                author,
                                source,
                            )
                            author != null -> stringResource(R.string.home_side_panel_attribution_author, author)
                            source != null -> stringResource(R.string.home_side_panel_attribution_source, source)
                            else -> null
                        }
                        attribution?.let {
                            Text(
                                text = it,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                val refreshing = hitokoto is HitokotoUiState.Loading ||
                    hitokoto is HitokotoUiState.Ready && hitokoto.refreshing
                if (refreshing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
    }
}

@StringRes
private fun greetingResForHour(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.home_side_panel_greeting_morning
    in 12..17 -> R.string.home_side_panel_greeting_afternoon
    else -> R.string.home_side_panel_greeting_evening
}

private fun weatherIcon(code: String): ImageVector = when (weatherIconKind(code)) {
    WeatherIconKind.SUNNY -> MaterialSymbols.Outlined.Sunny
    WeatherIconKind.PARTLY_CLOUDY -> MaterialSymbols.Outlined.Partly_cloudy_day
    WeatherIconKind.OVERCAST -> MaterialSymbols.Outlined.Cloud
    WeatherIconKind.SHOWER -> MaterialSymbols.Outlined.Rainy
    WeatherIconKind.THUNDERSTORM -> MaterialSymbols.Outlined.Thunderstorm
    WeatherIconKind.HAIL -> MaterialSymbols.Outlined.Weather_hail
    WeatherIconKind.SLEET -> MaterialSymbols.Outlined.Rainy_snow
    WeatherIconKind.LIGHT_RAIN -> MaterialSymbols.Outlined.Rainy_light
    WeatherIconKind.RAIN -> MaterialSymbols.Outlined.Rainy
    WeatherIconKind.HEAVY_RAIN -> MaterialSymbols.Outlined.Rainy_heavy
    WeatherIconKind.RAINSTORM -> MaterialSymbols.Outlined.Storm
    WeatherIconKind.SNOW_SHOWER -> MaterialSymbols.Outlined.Sunny_snowing
    WeatherIconKind.LIGHT_SNOW -> MaterialSymbols.Outlined.Snowing
    WeatherIconKind.SNOW -> MaterialSymbols.Outlined.Weather_snowy
    WeatherIconKind.HEAVY_SNOW -> MaterialSymbols.Outlined.Snowing_heavy
    WeatherIconKind.BLIZZARD -> MaterialSymbols.Outlined.Cloudy_snowing
    WeatherIconKind.FOG -> MaterialSymbols.Outlined.Foggy
    WeatherIconKind.FREEZING_RAIN -> MaterialSymbols.Outlined.Rainy_snow
    WeatherIconKind.DUST_STORM -> MaterialSymbols.Outlined.Storm
    WeatherIconKind.DUST -> MaterialSymbols.Outlined.Grain
    WeatherIconKind.SAND -> MaterialSymbols.Outlined.Grain
    WeatherIconKind.SQUALL -> MaterialSymbols.Outlined.Cyclone
    WeatherIconKind.TORNADO -> MaterialSymbols.Outlined.Tornado
    WeatherIconKind.HAZE -> MaterialSymbols.Outlined.Air
    WeatherIconKind.UNKNOWN -> MaterialSymbols.Outlined.Question_mark
}

@StringRes
private fun weatherDescriptionRes(code: String): Int = when (code.toIntOrNull()) {
    0 -> R.string.weather_sunny
    1 -> R.string.weather_cloudy
    2 -> R.string.weather_overcast
    3 -> R.string.weather_shower
    4 -> R.string.weather_thunderstorm
    5 -> R.string.weather_hail_thunderstorm
    6 -> R.string.weather_sleet
    7 -> R.string.weather_light_rain
    8 -> R.string.weather_moderate_rain
    9 -> R.string.weather_heavy_rain
    10 -> R.string.weather_rainstorm
    11 -> R.string.weather_heavy_rainstorm
    12 -> R.string.weather_severe_rainstorm
    13 -> R.string.weather_snow_shower
    14 -> R.string.weather_light_snow
    15 -> R.string.weather_moderate_snow
    16 -> R.string.weather_heavy_snow
    17 -> R.string.weather_blizzard
    18 -> R.string.weather_fog
    19 -> R.string.weather_freezing_rain
    20 -> R.string.weather_dust_storm
    21 -> R.string.weather_light_to_moderate_rain
    22 -> R.string.weather_moderate_to_heavy_rain
    23 -> R.string.weather_heavy_rain_to_rainstorm
    24 -> R.string.weather_rainstorm_to_heavy
    25 -> R.string.weather_heavy_to_severe_rainstorm
    26 -> R.string.weather_light_to_moderate_snow
    27 -> R.string.weather_moderate_to_heavy_snow
    28 -> R.string.weather_heavy_snow_to_blizzard
    29 -> R.string.weather_dust
    30 -> R.string.weather_sand
    31 -> R.string.weather_severe_dust_storm
    32 -> R.string.weather_squall
    33 -> R.string.weather_tornado
    34 -> R.string.weather_blowing_snow
    35 -> R.string.weather_mist
    53 -> R.string.weather_haze
    else -> R.string.unknown
}

private fun formatWeatherPublishedAt(publishedAt: String): String = runCatching {
    OffsetDateTime.parse(publishedAt).format(HOME_SIDE_PANEL_TIME_FORMATTER)
}.getOrDefault(publishedAt)

private fun shortcutIcon(kind: HomeSidePanelIconKind): ImageVector = when (kind) {
    HomeSidePanelIconKind.QR_CODE_SCANNER -> MaterialSymbols.Outlined.Qr_code_scanner
    HomeSidePanelIconKind.WALLET -> MaterialSymbols.Outlined.Wallet
    HomeSidePanelIconKind.BOOKMARK -> MaterialSymbols.Outlined.Bookmark
    HomeSidePanelIconKind.CAMERA -> MaterialSymbols.Outlined.Camera
    HomeSidePanelIconKind.MOVIE -> MaterialSymbols.Outlined.Movie
    HomeSidePanelIconKind.MARK_CHAT_READ -> MaterialSymbols.Outlined.Mark_chat_read
    HomeSidePanelIconKind.EXTENSION -> MaterialSymbols.Outlined.Extension
}

private val HOME_SIDE_PANEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

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
                        endInt = 500,
                        stepSize = 1,
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
                        endInt = 500,
                        stepSize = 1,
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
                LocalContext.current.resolveBeautifyText(state.hitokoto.message),
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
