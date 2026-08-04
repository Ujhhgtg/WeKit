package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Cloudy
import com.composables.icons.materialsymbols.outlined.Collections_bookmark
import com.composables.icons.materialsymbols.outlined.Foggy
import com.composables.icons.materialsymbols.outlined.Format_quote
import com.composables.icons.materialsymbols.outlined.Mark_email_read
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Qr_code_scanner
import com.composables.icons.materialsymbols.outlined.Question_mark
import com.composables.icons.materialsymbols.outlined.Rainy
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Sunny
import com.composables.icons.materialsymbols.outlined.Thunderstorm
import com.composables.icons.materialsymbols.outlined.Video_library
import com.composables.icons.materialsymbols.outlined.Weather_snowy
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class HomeSidePanelIconKind {
    QR_CODE_SCANNER,
    PAYMENTS,
    COLLECTIONS_BOOKMARK,
    PHOTO_LIBRARY,
    VIDEO_LIBRARY,
    MARK_EMAIL_READ,
    SETTINGS,
}

enum class HomeSidePanelShortcutPlacement {
    TILE,
    LIST_ITEM,
}

data class HomeSidePanelShortcutSpec(
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
    HomeSidePanelShortcut.PAYMENTS -> HomeSidePanelShortcutSpec(shortcut, "收付款", HomeSidePanelIconKind.PAYMENTS, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.FAVORITES -> HomeSidePanelShortcutSpec(shortcut, "收藏", HomeSidePanelIconKind.COLLECTIONS_BOOKMARK, HomeSidePanelShortcutPlacement.TILE)
    HomeSidePanelShortcut.MOMENTS -> HomeSidePanelShortcutSpec(shortcut, "朋友圈", HomeSidePanelIconKind.PHOTO_LIBRARY, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.VIDEO_CHANNELS -> HomeSidePanelShortcutSpec(shortcut, "视频号", HomeSidePanelIconKind.VIDEO_LIBRARY, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.MARK_ALL_READ -> HomeSidePanelShortcutSpec(shortcut, "清空未读", HomeSidePanelIconKind.MARK_EMAIL_READ, HomeSidePanelShortcutPlacement.LIST_ITEM)
    HomeSidePanelShortcut.WEKIT_SETTINGS -> HomeSidePanelShortcutSpec(shortcut, "WeKit 设置", HomeSidePanelIconKind.SETTINGS, HomeSidePanelShortcutPlacement.LIST_ITEM)
}

@Composable
fun HomeSidePanelContent(
    state: HomeSidePanelUiState,
    controller: HomeSidePanelController,
) {
    if (state.cardMode != HomeSidePanelCardMode.CONTENT) {
        HomeSidePanelSettingsContent(state, controller)
        return
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HomeSidePanelProfileHeader(state.profile, controller)
            HomeSidePanelWeatherCard(state.weather, controller)
            HomeSidePanelShortcutList(controller)
            HomeSidePanelHitokotoCard(state.hitokoto, state.hitokotoSettings, controller)
        }
    }
}

@Composable
private fun HomeSidePanelProfileHeader(
    profile: HomeSidePanelProfile,
    controller: HomeSidePanelController,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile.avatarUrl.isNotBlank()) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(58.dp).clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.nickname.firstOrNull()?.toString() ?: "微",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(homeSidePanelProfileDisplayName(profile), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            HomeSidePanelStatus(profile.status, controller)
        }
    }
}

@Composable
private fun HomeSidePanelStatus(
    status: HomeSidePanelStatusUiState,
    controller: HomeSidePanelController,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (status) {
            HomeSidePanelStatusUiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            HomeSidePanelStatusUiState.NoStatus -> {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF31B36B)))
                Text("在线", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is HomeSidePanelStatusUiState.Ready -> {
                val emojiUrl = status.status.emoji?.thumbUrl?.takeIf(String::isNotBlank)
                    ?: status.status.emoji?.url?.takeIf(String::isNotBlank)
                emojiUrl?.let { url ->
                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(status.status.description.ifBlank { "在线" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is HomeSidePanelStatusUiState.Error -> {
                Icon(MaterialSymbols.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text("获取失败", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                IconButton(onClick = controller::refreshStatus, modifier = Modifier.size(24.dp)) {
                    Icon(MaterialSymbols.Outlined.Refresh, contentDescription = "刷新状态", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSidePanelWeatherCard(
    weather: WeatherUiState,
    controller: HomeSidePanelController,
) {
    val snapshot = weatherCardSnapshot(weather)
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = controller::refreshWeather,
                onLongClick = controller::openWeatherSettings,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val now = LocalDateTime.now()
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    now.format(DateTimeFormatter.ofPattern("M月d日 E")),
                    modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
            Text(greetingForHour(now.hour), style = MaterialTheme.typography.titleMedium)
            if (snapshot != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(weatherIcon(snapshot.weatherCode), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("${snapshot.city.city}${snapshot.city.district.orEmpty()}  ${snapshot.temperature}°C", style = MaterialTheme.typography.bodyMedium)
                    Text("体感 ${snapshot.feelsLike}°C", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                }
                Text(
                    "最高 ${snapshot.high}°  最低 ${snapshot.low}°  湿度 ${snapshot.humidity}%  风速 ${snapshot.windSpeed}km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Text("更新于 ${snapshot.publishedAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f))
            } else {
                Text(
                    if (weather is WeatherUiState.Loading) "天气加载中…" else "暂无天气数据，点击卡片重试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelShortcutList(controller: HomeSidePanelController) {
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
                    .combinedClickable(onClick = { controller.runShortcut(shortcut) }, onLongClick = null),
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
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { controller.runShortcut(shortcut) }, onLongClick = null),
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
    controller: HomeSidePanelController,
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
                onClick = controller::fetchAnotherHitokoto,
                onLongClick = controller::openHitokotoSettings,
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
                        IconButton(onClick = controller::fetchAnotherHitokoto, modifier = Modifier.size(28.dp)) {
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
    WeatherIconKind.CLOUDY -> MaterialSymbols.Outlined.Cloudy
    WeatherIconKind.RAIN -> MaterialSymbols.Outlined.Rainy
    WeatherIconKind.SNOW -> MaterialSymbols.Outlined.Weather_snowy
    WeatherIconKind.FOG -> MaterialSymbols.Outlined.Foggy
    WeatherIconKind.THUNDER -> MaterialSymbols.Outlined.Thunderstorm
    WeatherIconKind.UNKNOWN -> MaterialSymbols.Outlined.Question_mark
}

private fun shortcutIcon(kind: HomeSidePanelIconKind): ImageVector = when (kind) {
    HomeSidePanelIconKind.QR_CODE_SCANNER -> MaterialSymbols.Outlined.Qr_code_scanner
    HomeSidePanelIconKind.PAYMENTS -> MaterialSymbols.Outlined.Payments
    HomeSidePanelIconKind.COLLECTIONS_BOOKMARK -> MaterialSymbols.Outlined.Collections_bookmark
    HomeSidePanelIconKind.PHOTO_LIBRARY -> MaterialSymbols.Outlined.Photo_library
    HomeSidePanelIconKind.VIDEO_LIBRARY -> MaterialSymbols.Outlined.Video_library
    HomeSidePanelIconKind.MARK_EMAIL_READ -> MaterialSymbols.Outlined.Mark_email_read
    HomeSidePanelIconKind.SETTINGS -> MaterialSymbols.Outlined.Settings
}
