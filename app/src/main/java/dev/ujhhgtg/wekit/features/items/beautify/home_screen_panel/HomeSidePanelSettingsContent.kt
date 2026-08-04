package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.My_location
import com.composables.icons.materialsymbols.outlined.Person_pin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeSidePanelSettingsContent(
    state: HomeSidePanelUiState,
    controller: HomeSidePanelController,
) {
    when (state.cardMode) {
        HomeSidePanelCardMode.WEATHER_SETTINGS -> HomeSidePanelWeatherSettings(state, controller)
        HomeSidePanelCardMode.HITOKOTO_SETTINGS -> HomeSidePanelHitokotoSettings(state, controller)
        HomeSidePanelCardMode.CONTENT -> HomeSidePanelContent(state, controller)
    }
}

@Composable
private fun HomeSidePanelWeatherSettings(
    state: HomeSidePanelUiState,
    controller: HomeSidePanelController,
) {
    val activity = LocalContext.current as Activity
    var query by remember(state.weatherSettings.searchQuery) { mutableStateOf(state.weatherSettings.searchQuery) }
    Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader("天气设置", controller::closeCardSettings)
        Text(
            "当前城市：${state.weatherSettings.selectedCity.province} ${state.weatherSettings.selectedCity.city}",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                controller.searchWeatherCities(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索城市") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { controller.detectWeatherLocation(activity) },
                modifier = Modifier.weight(1f),
                enabled = !state.weatherSettings.actionInProgress,
            ) {
                Icon(MaterialSymbols.Outlined.My_location, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("自动检测", modifier = Modifier.padding(start = 5.dp))
            }
            Button(
                onClick = controller::readWeatherFromProfile,
                modifier = Modifier.weight(1f),
                enabled = !state.weatherSettings.actionInProgress,
            ) {
                Icon(MaterialSymbols.Outlined.Person_pin, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("从个人资料读取", modifier = Modifier.padding(start = 5.dp))
            }
        }
        state.weatherSettings.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.weatherSettings.searchResults.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                state.weatherSettings.searchResults.forEach { city ->
                    ListItem(
                        headlineContent = { Text(city.city + city.district.orEmpty()) },
                        supportingContent = { Text(city.province) },
                        trailingContent = { Text(city.cityNum, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { controller.selectWeatherCity(city) }, modifier = Modifier.align(Alignment.End)) {
                        Text("选择")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSidePanelHitokotoSettings(
    state: HomeSidePanelUiState,
    controller: HomeSidePanelController,
) {
    var draft by remember(state.hitokotoSettings) { mutableStateOf(state.hitokotoSettings) }
    Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader("一言设置", controller::closeCardSettings)
        Text("分类", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hitokotoCategoryLabels.forEach { (code, label) ->
                FilterChip(
                    selected = code in draft.categories,
                    onClick = {
                        draft = draft.copy(
                            categories = if (code in draft.categories) draft.categories - code else draft.categories + code,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = draft.charset == "utf-8", onClick = { draft = draft.copy(charset = "utf-8") }, label = { Text("utf-8") })
            FilterChip(selected = draft.charset == "gbk", onClick = { draft = draft.copy(charset = "gbk") }, label = { Text("gbk") })
        }
        ListItem(
            headlineContent = { Text("显示来源") },
            trailingContent = { Switch(checked = draft.showSource, onCheckedChange = { draft = draft.copy(showSource = it) }) },
        )
        ListItem(
            headlineContent = { Text("显示作者") },
            trailingContent = { Switch(checked = draft.showAuthor, onCheckedChange = { draft = draft.copy(showAuthor = it) }) },
        )
        if (state.hitokoto is HitokotoUiState.Error) {
            Text(state.hitokoto.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { draft = HitokotoSettings() }, modifier = Modifier.weight(1f)) { Text("恢复默认") }
            Button(onClick = { controller.saveHitokotoSettings(draft) }, modifier = Modifier.weight(1f)) { Text("保存") }
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
