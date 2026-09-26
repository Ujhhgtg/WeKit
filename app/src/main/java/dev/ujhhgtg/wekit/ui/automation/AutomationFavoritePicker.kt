package dev.ujhhgtg.wekit.ui.automation

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeFavoriteApi
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

private const val FAVORITE_PICKER_LIMIT = 1000

private val FAVORITE_TYPE_LABELS = mapOf(
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

/** Multi-select list of the newest favorites; confirms with the picked local ids in list order. */
@Composable
fun AutomationFavoritePicker(
    title: String,
    initialSelected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    var favorites by remember { mutableStateOf<List<WeFavoriteApi.FavoriteSummary>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelected) }

    LaunchedEffect(Unit) {
        try {
            favorites = withContext(Dispatchers.IO) { WeFavoriteApi.listFavorites(FAVORITE_PICKER_LIMIT) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.e("AutomationFavoritePicker", "failed to list favorites", e)
            failed = true
        }
    }

    AlertDialogContent(
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val loaded = favorites
                when {
                    failed -> Text(stringResource(R.string.automation_favorites_load_failed))
                    loaded == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    else -> {
                        val filtered = remember(loaded, query) {
                            loaded.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.localId.toString() == query.trim() }
                        }
                        if (filtered.isEmpty()) Text(stringResource(R.string.automation_favorites_empty))
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(filtered, key = { it.localId }) { favorite ->
                                val checked = favorite.localId in selected
                                val typeLabel = FAVORITE_TYPE_LABELS[favorite.type]?.let { stringResource(it) }
                                    ?: stringResource(R.string.favorite_type_other, favorite.type)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = if (checked) selected - favorite.localId else selected + favorite.localId },
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                        Text(favorite.title.ifBlank { typeLabel }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "$typeLabel · ${DateFormat.getDateFormat(context).format(Date(favorite.updateTimeMs))} · ${favorite.localId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val order = favorites.orEmpty().map { it.localId }
                onConfirm(order.filter { it in selected } + selected.filter { it !in order })
            }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
