package dev.ujhhgtg.wekit.ui.automation

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeFavoriteApi
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SETTINGS_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.content.m3.SettingsEmptyState
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FAVORITE_PICKER_LIMIT = 1000

val FAVORITE_TYPE_LABELS =
    mapOf(
        1 to R.string.favorite_type_text,
        2 to R.string.favorite_type_image,
        3 to R.string.favorite_type_voice,
        4 to R.string.favorite_type_video,
        5 to R.string.favorite_type_link,
        6 to R.string.favorite_type_location,
        7 to R.string.favorite_type_music,
        8 to R.string.favorite_type_file,
        10 to R.string.favorite_type_note,
        14 to R.string.favorite_type_chat_history,
        18 to R.string.favorite_type_note,
        19 to R.string.favorite_type_mini_program,
    )

@Composable
fun favoriteTypeLabel(type: Int): String =
    FAVORITE_TYPE_LABELS[type]?.let { stringResource(it) }
        ?: stringResource(R.string.favorite_type_other, type)

/**
 * Full-screen multi-select list of the newest favorites with search and type filters; confirms with
 * the picked local ids in list order.
 */
@Composable
fun AutomationFavoritePickerScreen(
    title: String,
    initialSelected: Set<Long>,
    onBack: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    var favorites by remember { mutableStateOf<List<WeFavoriteApi.FavoriteSummary>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf(initialSelected) }

    LaunchedEffect(Unit) {
        try {
            favorites =
                withContext(Dispatchers.IO) { WeFavoriteApi.listFavorites(FAVORITE_PICKER_LIMIT) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.e("AutomationFavoritePicker", "failed to list favorites", e)
            failed = true
        }
    }

    val loaded = favorites
    val types = remember(loaded) { loaded.orEmpty().map { it.type }.distinct() }
    val filtered =
        remember(loaded, query, type) {
            loaded.orEmpty().filter { favorite ->
                (type == null || favorite.type == type) &&
                    (query.isBlank() ||
                        favorite.title.contains(query, ignoreCase = true) ||
                        favorite.localId.toString() == query.trim())
            }
        }

    SettingsScaffold(
        title = title,
        onBack = onBack,
        pinnedHeader = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(MaterialSymbols.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = type == null,
                        onClick = { type = null },
                        label = { Text(stringResource(R.string.automation_filter_all)) },
                    )
                }
                items(types) { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(favoriteTypeLabel(t)) },
                    )
                }
            }
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                onClick = {
                    val order = loaded.orEmpty().map { it.localId }
                    onConfirm(order.filter { it in selected } + selected.filter { it !in order })
                },
                icon = { Icon(MaterialSymbols.Outlined.Check, null) },
                text = {
                    Text(stringResource(R.string.contact_confirm_selected_count, selected.size))
                },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        },
    ) {
        when {
            failed -> {
                item {
                    SettingsEmptyState(
                        title = stringResource(R.string.automation_favorites_load_failed),
                    )
                }
            }

            loaded == null -> {
                item {
                    Box(
                        Modifier.fillParentMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            }

            filtered.isEmpty() -> {
                item {
                    SettingsEmptyState(title = stringResource(R.string.automation_favorites_empty))
                }
            }

            else -> {
                lazySegmentedItems(filtered, key = { it.localId }) { favorite ->
                    val checked = favorite.localId in selected
                    val typeLabel = favoriteTypeLabel(favorite.type)
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        BaseWidget(
                            title = favorite.title.ifBlank { typeLabel },
                            description =
                                "$typeLabel · ${DateFormat.getDateFormat(context).format(Date(favorite.updateTimeMs))}",
                            selected = checked,
                            onClick = {
                                selected =
                                    if (checked) {
                                        selected - favorite.localId
                                    } else {
                                        selected + favorite.localId
                                    }
                            },
                            trailingContent = {
                                Checkbox(checked = checked, onCheckedChange = null)
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET + 72.dp)) }
    }
}
