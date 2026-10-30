package dev.ujhhgtg.wekit.features.items.chat.spoof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.entity.SpoofedChatMessageEntity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.i18n.LocaleProvider
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SETTINGS_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.content.m3.SettingsConfirmDialog
import dev.ujhhgtg.wekit.ui.content.m3.SettingsEmptyState
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists every spoofed message, filtered by kind or searched, for editing and removal.
 *
 * Named `*SettingsActivity` so [dev.ujhhgtg.wekit.loader.utils.ActivityProxy] routes it through the
 * opaque splash proxy when launched from WeChat's host process.
 */
@Keep
class SpoofChatMessagesSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocaleProvider {
                ModuleTheme {
                    SpoofChatMessagesScreen(onFinish = ::finish)
                }
            }
        }
    }
}

@Composable
private fun SpoofChatMessagesScreen(onFinish: () -> Unit) {
    val spoofs by SpoofedMessageStore.all.collectAsState()
    val chatNames by
        produceState(emptyMap(), spoofs) {
            value =
                withContext(Dispatchers.IO) {
                    spoofs.map { it.talker }.distinct().associateWith(WeDatabaseApi::getDisplayName)
                }
        }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<SpoofKind?>(null) }
    var editing by remember { mutableStateOf<SpoofedChatMessageEntity?>(null) }
    var removing by remember { mutableStateOf<SpoofedChatMessageEntity?>(null) }
    var confirmRemoveAll by rememberSaveable { mutableStateOf(false) }
    // The picker can outlive this activity instance, so remember its row by saveable key parts.
    var pickingTalker by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingMsgId by rememberSaveable { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val failedMessage = stringResource(R.string.spoof_message_save_failed)

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val key = SpoofKey(pickingTalker!!, pickingMsgId)
            pickingTalker = null
            // The spoof may have been removed from a chat while the picker was open.
            val spoof = SpoofedMessageStore[key] ?: return@rememberLauncherForActivityResult
            if (uri != null) SpoofMessageEditor.importMedia(SpoofTarget.of(spoof), uri)
        }

    val filtered =
        remember(spoofs, chatNames, query, kindFilter) {
            val needle = query.trim()
            spoofs.filter { spoof ->
                (kindFilter == null || spoof.spoofKind == kindFilter) &&
                    (needle.isEmpty() ||
                        listOfNotNull(
                                chatNames[spoof.talker],
                                spoof.talker,
                                spoof.text,
                                spoof.original,
                                spoof.amount,
                            )
                            .any { it.contains(needle, ignoreCase = true) })
            }
        }

    fun launch(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e("SpoofChatMessages", "failed to update spoofs", e)
                showToast(failedMessage)
            }
        }
    }

    // A back press while searching clears the query first, like the features search.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    SettingsScaffold(
        title = stringResource(R.string.feature_spoof_chat_messages_name),
        onBack = onFinish,
        actions = {
            IconButton({ confirmRemoveAll = true }, enabled = spoofs.isNotEmpty()) {
                Icon(
                    MaterialSymbols.Outlined.Delete_sweep,
                    contentDescription = stringResource(R.string.spoof_messages_remove_all),
                )
            }
        },
    ) {
        if (spoofs.isEmpty()) {
            item {
                SettingsEmptyState(
                    title = stringResource(R.string.spoof_messages_empty_title),
                    message = stringResource(R.string.spoof_messages_empty_message),
                )
            }
        } else {
            spoofList(
                spoofs = spoofs,
                filtered = filtered,
                chatNames = chatNames,
                query = query,
                onQueryChange = { query = it },
                kindFilter = kindFilter,
                onKindFilterChange = { kindFilter = it },
                onEdit = { spoof ->
                    if (spoof.spoofKind.isMedia) {
                        pickingTalker = spoof.talker
                        pickingMsgId = spoof.msgId
                        picker.launch(SpoofMessageEditor.pickMediaRequest(spoof.spoofKind))
                    } else {
                        editing = spoof
                    }
                },
                onRemove = { removing = it },
            )
            item { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET)) }
        }
    }

    editing?.let { spoof ->
        SpoofValueDialog(
            spoof = spoof,
            onDismiss = { editing = null },
            onSave = { value ->
                editing = null
                launch {
                    val target = SpoofTarget.of(spoof)
                    if (spoof.spoofKind == SpoofKind.TEXT) {
                        SpoofedMessageStore.saveText(target, value)
                    } else {
                        SpoofedMessageStore.saveAmount(target, normalizeSpoofAmount(value)!!)
                    }
                }
            },
        )
    }

    SettingsConfirmDialog(
        show = removing != null,
        title = stringResource(R.string.spoof_message_remove),
        message = stringResource(R.string.spoof_messages_remove_message),
        confirmLabel = stringResource(R.string.spoof_message_remove),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            val key = removing!!.key
            removing = null
            launch { SpoofedMessageStore.remove(key) }
        },
        onDismiss = { removing = null },
    )

    SettingsConfirmDialog(
        show = confirmRemoveAll,
        title = stringResource(R.string.spoof_messages_remove_all),
        message = stringResource(R.string.spoof_messages_remove_all_message),
        confirmLabel = stringResource(R.string.spoof_messages_remove_all),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            confirmRemoveAll = false
            val keys = spoofs.map { it.key }
            launch { keys.forEach { SpoofedMessageStore.remove(it) } }
        },
        onDismiss = { confirmRemoveAll = false },
    )
}

private fun LazyListScope.spoofList(
    spoofs: List<SpoofedChatMessageEntity>,
    filtered: List<SpoofedChatMessageEntity>,
    chatNames: Map<String, String>,
    query: String,
    onQueryChange: (String) -> Unit,
    kindFilter: SpoofKind?,
    onKindFilterChange: (SpoofKind?) -> Unit,
    onEdit: (SpoofedChatMessageEntity) -> Unit,
    onRemove: (SpoofedChatMessageEntity) -> Unit,
) {
    item {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp).fillMaxWidth(),
            label = { Text(stringResource(R.string.spoof_messages_search_hint)) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    MaterialSymbols.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton({ onQueryChange("") }) {
                        Icon(
                            MaterialSymbols.Outlined.Close,
                            contentDescription = stringResource(R.string.features_clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
    item {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            item {
                FilterChip(
                    selected = kindFilter == null,
                    onClick = { onKindFilterChange(null) },
                    label = {
                        Text(stringResource(R.string.spoof_messages_filter_all, spoofs.size))
                    },
                )
            }
            items(SpoofKind.entries) { kind ->
                FilterChip(
                    selected = kindFilter == kind,
                    onClick = { onKindFilterChange(kind) },
                    label = {
                        Text(
                            stringResource(
                                R.string.spoof_messages_filter_kind,
                                stringResource(kind.labelRes),
                                spoofs.count { it.spoofKind == kind },
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(kind.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
    if (filtered.isEmpty()) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.spoof_messages_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        lazySegmentedItems(filtered, key = { "${it.talker}/${it.msgId}" }) { spoof ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                SpoofRow(
                    spoof = spoof,
                    chatName = chatNames[spoof.talker] ?: spoof.talker,
                    onClick = { onEdit(spoof) },
                    onRemove = { onRemove(spoof) },
                )
            }
        }
    }
}

@Composable
private fun SpoofRow(
    spoof: SpoofedChatMessageEntity,
    chatName: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val kind = spoof.spoofKind
    val summary =
        when (kind) {
            SpoofKind.TEXT -> {
                spoof.text!!.replace('\n', ' ').let {
                    if (it.length > 60) it.take(60) + "…" else it
                }
            }

            SpoofKind.TRANSFER -> {
                stringResource(
                    R.string.spoof_messages_amount_summary,
                    spoof.original!!,
                    formatSpoofAmount(spoof.original, spoof.amount!!),
                )
            }

            SpoofKind.IMAGE -> {
                stringResource(R.string.spoof_kind_image)
            }

            SpoofKind.VIDEO -> {
                stringResource(
                    R.string.spoof_messages_video_summary,
                    formatSpoofDuration(spoof.videoDurationSeconds!!),
                )
            }
        }
    BaseWidget(
        icon = kind.icon,
        leadingContent =
            if (kind.isMedia) {
                { SpoofPreview(spoof) }
            } else {
                null
            },
        title = chatName,
        description = "$summary\n${formatEpoch(spoof.createTime, true)}",
        onClick = onClick,
        trailingContent = {
            IconButton(onRemove) {
                Icon(
                    MaterialSymbols.Outlined.Delete,
                    contentDescription = stringResource(R.string.spoof_message_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun SpoofPreview(spoof: SpoofedChatMessageEntity) {
    // Materializing may copy the media out of Room, so it stays off the main thread.
    val file by
        produceState<File?>(null, spoof.updatedAt) {
            value = withContext(Dispatchers.IO) { SpoofedMessageStore.previewFile(spoof) }
        }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        imageLoader = GlobalImageLoader,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun SpoofValueDialog(
    spoof: SpoofedChatMessageEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val kind = spoof.spoofKind
    var draft by
        remember(spoof) {
            mutableStateOf(if (kind == SpoofKind.TEXT) spoof.text!! else spoof.amount!!)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(kind.titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind == SpoofKind.TEXT) {
                    Text(stringResource(R.string.spoof_message_original, spoof.original!!))
                }
                SpoofValueField(kind, spoof.original, draft, { draft = it }, enabled = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = isSpoofValueValid(kind, draft)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
