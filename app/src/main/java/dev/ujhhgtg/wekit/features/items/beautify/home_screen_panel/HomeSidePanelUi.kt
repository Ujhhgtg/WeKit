package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
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
    val label: String,
    val icon: HomeSidePanelIconKind,
    val placement: HomeSidePanelShortcutPlacement,
)

internal fun weatherCardSnapshot(state: WeatherUiState): WeatherSnapshot? = when (state) {
    is WeatherUiState.Ready -> state.snapshot
    is WeatherUiState.Error -> state.cached
    WeatherUiState.Loading -> null
}

internal fun hitokotoCardErrorMessage(state: HitokotoUiState): String? =
    (state as? HitokotoUiState.Error)?.message

internal fun homeSidePanelProfileDisplayName(profile: HomeSidePanelProfile): String =
    profile.nickname.ifBlank { "微信用户" }

internal fun homeSidePanelAttribution(author: String?, source: String?): String? {
    val normalizedAuthor = author?.trim()?.takeIf(String::isNotEmpty)
    val normalizedSource = source?.trim()?.takeIf(String::isNotEmpty)
    return when {
        normalizedAuthor != null && normalizedSource != null -> "—— $normalizedAuthor「$normalizedSource」"
        normalizedAuthor != null -> "—— $normalizedAuthor"
        normalizedSource != null -> "——「$normalizedSource」"
        else -> null
    }
}

internal fun shortcutSpec(shortcut: HomeSidePanelShortcut): HomeSidePanelShortcutSpec = when (shortcut) {
    HomeSidePanelShortcut.SCAN -> HomeSidePanelShortcutSpec(shortcut, "扫一扫", HomeSidePanelIconKind.QR_CODE_SCANNER, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.PAYMENTS -> HomeSidePanelShortcutSpec(shortcut, "收付款", HomeSidePanelIconKind.WALLET, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.FAVORITES -> HomeSidePanelShortcutSpec(shortcut, "收藏", HomeSidePanelIconKind.BOOKMARK, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.MOMENTS -> HomeSidePanelShortcutSpec(shortcut, "朋友圈", HomeSidePanelIconKind.CAMERA, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.VIDEO_CHANNELS -> HomeSidePanelShortcutSpec(shortcut, "视频号", HomeSidePanelIconKind.MOVIE, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.MARK_ALL_READ -> HomeSidePanelShortcutSpec(shortcut, "清空未读", HomeSidePanelIconKind.MARK_CHAT_READ, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.WEKIT_SETTINGS -> HomeSidePanelShortcutSpec(shortcut, "WeKit 设置", HomeSidePanelIconKind.EXTENSION, HomeSidePanelShortcutPlacement.LIST_ITEM)
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
            contentDescription = "打开个人资料",
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
                homeSidePanelProfileDisplayName(profile),
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
            Icon(MaterialSymbols.Outlined.Settings, contentDescription = "侧栏设置")
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
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (status) {
            HomeSidePanelStatusUiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            HomeSidePanelStatusUiState.NoStatus -> {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF31B36B)))
                Text(
                    "在线",
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

            is HomeSidePanelStatusUiState.Error -> {
                Icon(MaterialSymbols.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(
                    "获取失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                IconButton(onClick = panelState::refreshStatus, modifier = Modifier.size(24.dp)) {
                    Icon(MaterialSymbols.Outlined.Refresh, contentDescription = "刷新状态", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeSidePanelDateTimeCard() {
    val now = rememberHomeSidePanelNow()
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
                    now.format(HOME_SIDE_PANEL_DATE_FORMATTER),
                    modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(greetingForHour(now.hour), style = MaterialTheme.typography.titleMedium)
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
        } ?: "天气"
        Column {
            Column(Modifier.padding(start = 18.dp, top = 17.dp, end = 18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            "更新于 ${formatWeatherPublishedAt(it.publishedAt)}",
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
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
                                    "体感 ${snapshot.feelsLike}°",
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
                                    weatherDescription(snapshot.weatherCode),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = contentColor,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (weather is WeatherUiState.Ready && weather.refreshing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
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
                    } else if (weather is WeatherUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = contentColor,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Text(
                            "暂无天气数据，点击卡片重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )
                    }
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
                    label = "最高 / 最低",
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
                    label = "湿度",
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
                    label = "风速",
                    modifier = Modifier.weight(1f),
                )
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
                    Text(spec.label, style = MaterialTheme.typography.labelMedium)
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
                headlineContent = { Text(spec.label) },
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
                Text("一言", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = snapshot?.text ?: if (hitokoto is HitokotoUiState.Error) hitokoto.message else "一言加载中…",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (snapshot != null) {
                hitokotoCardErrorMessage(hitokoto)?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        IconButton(onClick = panelState::fetchAnotherHitokoto, modifier = Modifier.size(28.dp)) {
                            Icon(MaterialSymbols.Outlined.Refresh, contentDescription = "重试一言", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (snapshot != null && (settings.showSource || settings.showAuthor)) {
                homeSidePanelAttribution(
                    author = snapshot.author?.takeIf { settings.showAuthor },
                    source = snapshot.source?.takeIf { settings.showSource },
                )?.let { attribution ->
                    Text(
                        text = attribution,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "早上好，今天也要保持好心情。"
    in 12..17 -> "下午好，愿今天一切顺利。"
    else -> "晚上好，愿你今晚安心入睡。"
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

private fun weatherDescription(code: String): String = when (code.toIntOrNull()) {
    0 -> "晴"
    1 -> "多云"
    2 -> "阴"
    3 -> "阵雨"
    4 -> "雷阵雨"
    5 -> "雷阵雨并伴有冰雹"
    6 -> "雨夹雪"
    7 -> "小雨"
    8 -> "中雨"
    9 -> "大雨"
    10 -> "暴雨"
    11 -> "大暴雨"
    12 -> "特大暴雨"
    13 -> "阵雪"
    14 -> "小雪"
    15 -> "中雪"
    16 -> "大雪"
    17 -> "暴雪"
    18 -> "雾"
    19 -> "冻雨"
    20 -> "沙尘暴"
    21 -> "小雨-中雨"
    22 -> "中雨-大雨"
    23 -> "大雨-暴雨"
    24 -> "暴雨-大暴雨"
    25 -> "大暴雨-特大暴雨"
    26 -> "小雪-中雪"
    27 -> "中雪-大雪"
    28 -> "大雪-暴雪"
    29 -> "浮尘"
    30 -> "扬沙"
    31 -> "强沙尘暴"
    32 -> "飑"
    33 -> "龙卷风"
    34 -> "若高吹雪"
    35 -> "轻雾"
    53 -> "霾"
    else -> "未知"
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
private val HOME_SIDE_PANEL_DATE_FORMATTER = DateTimeFormatter.ofPattern("M月d日 E")

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
            contentDescription = "打开侧栏",
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
                text = homeSidePanelProfileDisplayName(profile),
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
                text = profile.nickname.firstOrNull()?.toString() ?: "微",
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
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (status) {
            HomeSidePanelStatusUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(9.dp), strokeWidth = 1.5.dp)
                HomeSidePanelToolbarStatusText("加载中")
            }

            HomeSidePanelStatusUiState.NoStatus -> {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF31B36B)))
                HomeSidePanelToolbarStatusText("在线")
            }

            is HomeSidePanelStatusUiState.Ready -> {
                HomeSidePanelTextStatusIcon(status.status, 18.dp)
                HomeSidePanelToolbarStatusText(status.status.description)
            }

            is HomeSidePanelStatusUiState.Error -> {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                HomeSidePanelToolbarStatusText("获取失败", MaterialTheme.colorScheme.error)
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
        SettingsHeader("侧栏设置", panelState::closeCardSettings)
        ListItem(
            headlineContent = { Text("隐藏微信标题栏微信字样") },
            trailingContent = {
                Switch(
                    checked = state.hideWeChatTitle,
                    onCheckedChange = panelState::setHideWeChatTitle,
                )
            },
            modifier = Modifier.clickable {
                panelState.setHideWeChatTitle(!state.hideWeChatTitle)
            },
        )
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
        SettingsHeader("天气设置", panelState::closeCardSettings)
        Text(
            "当前城市：${state.weatherSettings.selectedCity.province} ${state.weatherSettings.selectedCity.city}",
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
                    Text("自动检测", maxLines = 1, style = MaterialTheme.typography.labelMedium)
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
                    Text("从个人资料读取", maxLines = 1, style = MaterialTheme.typography.labelMedium)
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
            label = { Text("搜索城市") },
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
        SettingsHeader("一言设置", panelState::closeCardSettings)
        Text("分类", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hitokotoCategoryLabels.forEach { (code, label) ->
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
                    label = { Text(label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft.minLength?.toString().orEmpty(),
                onValueChange = { draft = draft.copy(minLength = it.toIntOrNull()) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("最短长度") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = draft.maxLength?.toString().orEmpty(),
                onValueChange = { draft = draft.copy(maxLength = it.toIntOrNull()) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("最长长度") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        ListItem(
            headlineContent = { Text("显示来源") },
            trailingContent = {
                Switch(
                    checked = draft.showSource,
                    onCheckedChange = { draft = draft.copy(showSource = it) },
                )
            },
        )
        ListItem(
            headlineContent = { Text("显示作者") },
            trailingContent = {
                Switch(
                    checked = draft.showAuthor,
                    onCheckedChange = { draft = draft.copy(showAuthor = it) },
                )
            },
        )
        if (state.hitokoto is HitokotoUiState.Error) {
            Text(state.hitokoto.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { draft = HitokotoSettings() },
                modifier = Modifier.weight(1f),
            ) {
                Text("恢复默认")
            }
            Button(
                onClick = { panelState.saveHitokotoSettings(draft) },
                modifier = Modifier.weight(1f),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

private val hitokotoCategoryLabels = linkedMapOf(
    "a" to "动画",
    "b" to "漫画",
    "c" to "游戏",
    "d" to "文学",
    "e" to "原创",
    "f" to "网络",
    "g" to "其他",
    "h" to "影视",
    "i" to "诗词",
    "j" to "网易云",
    "k" to "哲学",
    "l" to "抖机灵",
)
