package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Air
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Cloudy_snowing
import com.composables.icons.materialsymbols.outlined.Cyclone
import com.composables.icons.materialsymbols.outlined.Device_thermostat
import com.composables.icons.materialsymbols.outlined.Foggy
import com.composables.icons.materialsymbols.outlined.Format_quote
import com.composables.icons.materialsymbols.outlined.Grain
import com.composables.icons.materialsymbols.outlined.Humidity_percentage
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Partly_cloudy_day
import com.composables.icons.materialsymbols.outlined.Qr_code_scanner
import com.composables.icons.materialsymbols.outlined.Question_mark
import com.composables.icons.materialsymbols.outlined.Rainy
import com.composables.icons.materialsymbols.outlined.Rainy_heavy
import com.composables.icons.materialsymbols.outlined.Rainy_light
import com.composables.icons.materialsymbols.outlined.Rainy_snow
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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.beautify.resolveBeautifyText
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal fun weatherCardSnapshot(state: WeatherUiState): WeatherSnapshot? = when (state) {
    is WeatherUiState.Ready -> state.snapshot
    is WeatherUiState.Error -> state.cached
    WeatherUiState.Loading -> null
}

@Composable
internal fun HomeSidePanelDateTimeCard(
    card: DateTimeCardConfig,
    content: DateTimeCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val now = when (content) {
        DateTimeCardContent.Runtime -> rememberHomeSidePanelNow()
        is DateTimeCardContent.Preview -> content.now
    }
    val localizedContext = LocalWeKitLocalizedContext.current
    val dateText = now.format(
        DateTimeFormatter.ofPattern(
            stringResource(R.string.home_side_panel_date_pattern),
            localizedContext.resources.configuration.locales[0],
        ),
    )
    val lunarDate = if (card.showLunarCalendar) {
        remember(now.toLocalDate()) { homeSidePanelLunarDate(now) }
    } else {
        null
    }
    val lunarText = lunarDate?.let {
        formatHomeSidePanelLunarDate(
            date = it,
            text = HomeSidePanelLunarDateText(
                prefix = stringResource(R.string.home_side_panel_lunar_prefix),
                leapPrefix = stringResource(R.string.home_side_panel_lunar_leap_prefix),
                separator = stringResource(R.string.home_side_panel_lunar_separator),
                monthNames = stringArrayResource(R.array.home_side_panel_lunar_month_names).asList(),
                dayNames = stringArrayResource(R.array.home_side_panel_lunar_day_names).asList(),
            ),
        )
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    now.format(HOME_SIDE_PANEL_TIME_FORMATTER),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildString {
                        append(dateText)
                        lunarText?.let { append(" · ").append(it) }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, bottom = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stringResource(greetingResForHour(now.hour)), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun HomeSidePanelWeatherCard(
    card: WeatherCardConfig,
    content: WeatherCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onRefresh: (String) -> Unit = {},
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val localizedContext = LocalWeKitLocalizedContext.current
    val runtime = content as? WeatherCardContent.Runtime
    val weather = runtime?.state
    val snapshot = when (content) {
        is WeatherCardContent.Runtime -> weatherCardSnapshot(content.state)
        is WeatherCardContent.Preview -> content.snapshot
    }
    val shape = RoundedCornerShape(24.dp)
    val clickModifier = if (interactionEnabled && !editMode && runtime != null) {
        Modifier.clickable { onRefresh(card.id) }
    } else {
        Modifier
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier)
            .then(clickModifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
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
            if (weather is WeatherUiState.Error) {
                Text(
                    text = localizedContext.resolveBeautifyText(weather.message),
                    modifier = Modifier.padding(start = 18.dp, top = 5.dp, end = 18.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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

@Composable
internal fun HomeSidePanelWalletCard(
    card: WalletCardConfig,
    content: WalletCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onToggleBalance: (String) -> Unit = {},
    onRunAction: (HomeSidePanelActionKind) -> Unit = {},
    onOpenPaymentCode: () -> Unit = {},
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val runtime = content as? WalletCardContent.Runtime
    val displayBalance = when (content) {
        is WalletCardContent.Runtime -> content.state.displayBalance
        is WalletCardContent.Preview -> content.displayBalance
    }
    val isMasked = runtime?.state?.displayState?.isMasked == true
    val interactive = interactionEnabled && !editMode && runtime != null
    val clickModifier = if (interactive) {
        Modifier.clickable { onToggleBalance(card.id) }
    } else {
        Modifier
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier)
            .then(clickModifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
            Text(
                text = displayBalance,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (isMasked) 4.sp else 0.sp,
                color = contentColor,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (interactive) onRunAction(HomeSidePanelActionKind.SCAN)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = interactive,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Qr_code_scanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.home_side_panel_scan), modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
                Button(
                    onClick = {
                        if (interactive) onOpenPaymentCode()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = interactive,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Wallet, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.home_side_panel_payment_code), modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelHitokotoCard(
    card: HitokotoCardConfig,
    content: HitokotoCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onRefresh: (String) -> Unit = {},
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val localizedContext = LocalWeKitLocalizedContext.current
    val runtime = content as? HitokotoCardContent.Runtime
    val hitokoto = runtime?.state
    val snapshot = when (content) {
        is HitokotoCardContent.Runtime -> when (val state = content.state) {
            is HitokotoUiState.Ready -> state.snapshot
            is HitokotoUiState.Error -> state.cached
            HitokotoUiState.Loading -> null
        }

        is HitokotoCardContent.Preview -> content.snapshot
    }
    val clickModifier = if (interactionEnabled && !editMode && runtime != null) {
        Modifier.clickable { onRefresh(card.id) }
    } else {
        Modifier
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier)
            .then(clickModifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Outlined.Format_quote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.home_side_panel_hitokoto),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
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
                    if (snapshot != null && (card.settings.showSource || card.settings.showAuthor)) {
                        val author = snapshot.author?.trim()?.takeIf { card.settings.showAuthor && it.isNotEmpty() }
                        val source = snapshot.source?.trim()?.takeIf { card.settings.showSource && it.isNotEmpty() }
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
            if (hitokoto is HitokotoUiState.Error) {
                Text(
                    text = localizedContext.resolveBeautifyText(hitokoto.message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
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
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
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

private val HOME_SIDE_PANEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
