package dev.ujhhgtg.wekit.ui.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_drop_down
import com.composables.icons.materialsymbols.outlined.Autorenew
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Send
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

@Composable
internal fun panelListItemColors(): ListItemColors = ListItemDefaults.colors(
    containerColor = Color.Transparent,
)

@Composable
fun <T> PanelPackChips(
    packs: List<T>,
    selectedId: String?,
    id: (T) -> String,
    title: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(packs, key = id) { pack ->
            FilterChip(
                selected = id(pack) == selectedId,
                onClick = { onSelect(pack) },
                label = { Text(title(pack), maxLines = 1) },
                modifier = Modifier.animateItem(),
            )
        }
    }
    HorizontalDivider()
}

internal fun <T> panelItemsWithStableKeys(
    items: List<T>,
    key: (T) -> String,
): List<Pair<String, T>> {
    val occurrences = mutableMapOf<String, Int>()
    return items.map { item ->
        val base = key(item)
        val occurrence = occurrences.getOrDefault(base, 0)
        occurrences[base] = occurrence + 1
        "$base#$occurrence" to item
    }
}

@Composable
fun PanelAutoCloseSetting(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = panelListItemColors(),
        content = { Text(stringResource(R.string.panel_setting_auto_close)) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
fun PanelActionWrapSetting(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = panelListItemColors(),
        content = { Text(stringResource(R.string.panel_setting_wrap_actions)) },
        supportingContent = { Text(stringResource(R.string.panel_setting_wrap_actions_summary)) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
fun PanelNavigationMemorySetting(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = panelListItemColors(),
        content = { Text(stringResource(R.string.panel_setting_remember_navigation)) },
        supportingContent = { Text(stringResource(R.string.panel_setting_remember_navigation_summary)) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
fun PanelHistorySetting(
    value: Long,
    onValueChange: (Long) -> Unit,
    onCustomValue: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onCustomValue),
        colors = panelListItemColors(),
        content = { Text(stringResource(R.string.panel_setting_max_history)) },
        supportingContent = { Text(stringResource(R.string.panel_setting_custom_number_summary, value)) },
    )
    Slider(
        value = panelHistoryToSlider(value),
        onValueChange = { onValueChange(panelSliderToHistory(it)) },
        valueRange = 0f..1f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

@Composable
fun PanelConcurrencySetting(
    title: String,
    value: Int,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = panelListItemColors(),
        content = { Text(title) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.panel_concurrency_summary, value, value))
        },
    )
}

@Composable
fun PanelFunBoxApiClientIdSetting(onClick: () -> Unit) {
    val current = PanelSettings.effectiveFunBoxApiClientWxId
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = panelListItemColors(),
        content = { Text(stringResource(R.string.panel_funbox_client_id)) },
        supportingContent = { Text(current) },
    )
}

@Composable
fun PanelTelegramBotTokenSetting(
    configured: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = panelListItemColors(),
        content = { Text(stringResource(R.string.panel_telegram_bot_token)) },
        supportingContent = {
            Text(stringResource(if (configured) R.string.panel_value_set else R.string.panel_value_not_set))
        },
    )
}

@Composable
fun PanelTelegramBotTokenPrompt(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember(initialValue) { mutableStateOf(initialValue) }
    val normalized = input.trim()
    val valid = normalized.isBlank() || PanelSettings.isValidTelegramBotToken(normalized)
    PanelFullOverlay(onDismiss = onDismiss) {
        Text(stringResource(R.string.panel_telegram_bot_token), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.panel_telegram_bot_token_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.panel_telegram_bot_token_label)) },
            supportingText = if (valid) null else ({ Text(stringResource(R.string.panel_telegram_bot_token_invalid)) }),
            isError = !valid,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            TextButton(onClick = { onConfirm(normalized) }, enabled = valid) {
                Text(stringResource(R.string.dialog_confirm))
            }
        }
    }
}

@Composable
fun <T> PanelDropdownSetting(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            colors = panelListItemColors(),
            content = { Text(title) },
            supportingContent = { Text(selectedLabel) },
            trailingContent = { Icon(MaterialSymbols.Outlined.Arrow_drop_down, null) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (value == selected) ({
                        Icon(MaterialSymbols.Outlined.Check, null)
                    }) else null,
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
fun PanelFunBoxApiClientIdPrompt(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf(PanelSettings.effectiveFunBoxApiClientWxId) }
    val normalized = input.trim()
    val valid = PanelSettings.isValidFunBoxApiClientWxId(normalized)
    PanelFullOverlay(onDismiss = onDismiss) {
        Text(stringResource(R.string.panel_funbox_client_id), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.panel_funbox_client_id_summary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.panel_wechat_id)) },
            supportingText = if (valid) null else ({ Text(stringResource(R.string.panel_wechat_id_invalid)) }),
            isError = !valid,
            singleLine = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { input = WeApi.selfWxId }) {
                        Icon(MaterialSymbols.Outlined.Person, stringResource(R.string.panel_wechat_id_use_current))
                    }
                    IconButton(onClick = { input = PanelSettings.randomFunBoxApiClientWxId() }) {
                        Icon(MaterialSymbols.Outlined.Autorenew, stringResource(R.string.panel_wechat_id_generate))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            TextButton(onClick = { onConfirm(normalized) }, enabled = valid) {
                Text(stringResource(R.string.dialog_confirm))
            }
        }
    }
}

/** Adds the settings shared by both local collection panels in one stable order. */
fun LazyListScope.panelCollectionSettings(
    maxHistory: Long,
    onMaxHistoryChange: (Long) -> Unit,
    onCustomHistory: () -> Unit,
    downloadConcurrency: Int,
    onCustomDownloadConcurrency: () -> Unit,
    conversionConcurrency: Int,
    onCustomConversionConcurrency: () -> Unit,
    autoClose: Boolean,
    onAutoCloseChange: (Boolean) -> Unit,
    wrapActions: Boolean,
    onWrapActionsChange: (Boolean) -> Unit,
    rememberNavigation: Boolean,
    onRememberNavigationChange: (Boolean) -> Unit,
) {
    item {
        PanelHistorySetting(
            value = maxHistory,
            onValueChange = onMaxHistoryChange,
            onCustomValue = onCustomHistory,
        )
    }
    item {
        PanelConcurrencySetting(
            title = stringResource(R.string.panel_setting_download_concurrency),
            value = downloadConcurrency,
            onClick = onCustomDownloadConcurrency,
        )
    }
    item {
        PanelConcurrencySetting(
            title = stringResource(R.string.panel_setting_conversion_concurrency),
            value = conversionConcurrency,
            onClick = onCustomConversionConcurrency,
        )
    }
    item {
        PanelAutoCloseSetting(
            checked = autoClose,
            onCheckedChange = onAutoCloseChange,
        )
    }
    item {
        PanelActionWrapSetting(
            checked = wrapActions,
            onCheckedChange = onWrapActionsChange,
        )
    }
    item {
        PanelNavigationMemorySetting(
            checked = rememberNavigation,
            onCheckedChange = onRememberNavigationChange,
        )
    }
}

@Composable
fun PanelSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onSearch: (() -> Unit)? = null,
    extraTrailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, null) },
        trailingIcon = when {
            extraTrailingIcon != null || onSearch != null -> ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    extraTrailingIcon?.invoke()
                    if (onSearch != null) {
                        IconButton(onClick = onSearch, enabled = enabled && value.isNotBlank()) {
                            Icon(MaterialSymbols.Outlined.Send, stringResource(R.string.search_hint))
                        }
                    } else if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }) {
                            Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.panel_search_clear))
                        }
                    }
                }
            })

            value.isNotEmpty() -> ({
                IconButton(onClick = { onValueChange("") }) {
                    Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.panel_search_clear))
                }
            })

            else -> null
        },
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch == null) ImeAction.Done else ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (enabled) onSearch?.invoke() }),
    )
}

@Composable
fun RecentModeTitle(
    mostUsed: Boolean,
    onModeChange: (Boolean) -> Unit,
) {
    val recentColor by animateColorAsState(
        if (!mostUsed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "recent-title-color",
    )
    val mostColor by animateColorAsState(
        if (mostUsed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "most-used-title-color",
    )
    val recentSize by animateFloatAsState(if (!mostUsed) 16f else 14f, label = "recent-title-size")
    val mostSize by animateFloatAsState(if (mostUsed) 16f else 14f, label = "most-used-title-size")
    Row(
        modifier = Modifier.animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.panel_recent),
            color = recentColor,
            fontSize = recentSize.sp,
            fontWeight = if (!mostUsed) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier
                .clickable { onModeChange(false) }
                .padding(vertical = 8.dp),
        )
        Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.panel_most_used),
            color = mostColor,
            fontSize = mostSize.sp,
            fontWeight = if (mostUsed) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier
                .clickable { onModeChange(true) }
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
fun SendCountBadge(count: Long, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private fun panelHistoryToSlider(value: Long): Float = when {
    value <= 1L -> 0f
    value >= PANEL_HISTORY_SLIDER_MAX -> 1f
    else -> (ln(value.toDouble()) / ln(PANEL_HISTORY_SLIDER_MAX.toDouble())).toFloat()
}

private fun panelSliderToHistory(value: Float): Long =
    exp(value.coerceIn(0f, 1f) * ln(PANEL_HISTORY_SLIDER_MAX.toDouble()))
        .roundToLong()
        .coerceIn(1L, PANEL_HISTORY_SLIDER_MAX)

private const val PANEL_HISTORY_SLIDER_MAX = 1_000L
