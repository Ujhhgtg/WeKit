package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Add_reaction
import com.composables.icons.materialsymbols.outlined.Alternate_email
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Code
import com.composables.icons.materialsymbols.outlined.Contact_page
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Description
import com.composables.icons.materialsymbols.outlined.Drag_indicator
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Group_add
import com.composables.icons.materialsymbols.outlined.Image
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Notes
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Shuffle
import com.composables.icons.materialsymbols.outlined.Star
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeFavoriteApi
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStep
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStepKind
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.splitReplyValues
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.content.m3.SETTINGS_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val REPLY_STEP_KIND_LABELS =
    mapOf(
        AutomationReplyStepKind.TEXT to R.string.automation_step_text,
        AutomationReplyStepKind.IMAGE to R.string.automation_step_image,
        AutomationReplyStepKind.VOICE to R.string.automation_step_voice,
        AutomationReplyStepKind.RANDOM_VOICE to R.string.automation_step_random_voice,
        AutomationReplyStepKind.EMOJI to R.string.automation_step_emoji,
        AutomationReplyStepKind.VIDEO to R.string.automation_step_video,
        AutomationReplyStepKind.FILE to R.string.automation_step_file,
        AutomationReplyStepKind.FAVORITE to R.string.automation_step_favorite,
        AutomationReplyStepKind.CARD to R.string.automation_step_card,
        AutomationReplyStepKind.INVITE_GROUP to R.string.automation_step_invite_group,
        AutomationReplyStepKind.XML to R.string.automation_step_xml,
    )

private fun kindIcon(kind: AutomationReplyStepKind): ImageVector =
    when (kind) {
        AutomationReplyStepKind.TEXT -> MaterialSymbols.Outlined.Notes
        AutomationReplyStepKind.IMAGE -> MaterialSymbols.Outlined.Image
        AutomationReplyStepKind.VOICE -> MaterialSymbols.Outlined.Mic
        AutomationReplyStepKind.RANDOM_VOICE -> MaterialSymbols.Outlined.Shuffle
        AutomationReplyStepKind.EMOJI -> MaterialSymbols.Outlined.Add_reaction
        AutomationReplyStepKind.VIDEO -> MaterialSymbols.Outlined.Movie
        AutomationReplyStepKind.FILE -> MaterialSymbols.Outlined.Description
        AutomationReplyStepKind.FAVORITE -> MaterialSymbols.Outlined.Star
        AutomationReplyStepKind.CARD -> MaterialSymbols.Outlined.Contact_page
        AutomationReplyStepKind.INVITE_GROUP -> MaterialSymbols.Outlined.Group_add
        AutomationReplyStepKind.XML -> MaterialSymbols.Outlined.Code
    }

/** Step kinds grouped as shown in the "add step" sheet. */
private val KIND_GROUPS =
    listOf(
        R.string.automation_step_group_messages to
            listOf(AutomationReplyStepKind.TEXT, AutomationReplyStepKind.XML),
        R.string.automation_step_group_media to
            listOf(
                AutomationReplyStepKind.IMAGE,
                AutomationReplyStepKind.VIDEO,
                AutomationReplyStepKind.EMOJI,
                AutomationReplyStepKind.VOICE,
                AutomationReplyStepKind.RANDOM_VOICE,
                AutomationReplyStepKind.FILE,
            ),
        R.string.automation_step_group_share to
            listOf(
                AutomationReplyStepKind.FAVORITE,
                AutomationReplyStepKind.CARD,
                AutomationReplyStepKind.INVITE_GROUP,
            ),
    )

private fun mimeTypesFor(kind: AutomationReplyStepKind): Array<String> =
    when (kind) {
        AutomationReplyStepKind.IMAGE,
        AutomationReplyStepKind.EMOJI -> arrayOf("image/*")

        AutomationReplyStepKind.VOICE,
        AutomationReplyStepKind.RANDOM_VOICE -> arrayOf("audio/*", "application/octet-stream")

        AutomationReplyStepKind.VIDEO -> arrayOf("video/*")

        else -> arrayOf("*/*")
    }

private val AT_REGEX = Regex("""\[AtWx=([^]]+)]""")

/** Short chip text for a step on the rule row, e.g. "Text: Thanks for…". */
@Composable
fun replyStepChip(step: AutomationReplyStep): String {
    val label = stringResource(REPLY_STEP_KIND_LABELS.getValue(step.kind))
    return if (step.kind == AutomationReplyStepKind.TEXT && step.content.isNotBlank()) {
        "$label: ${step.content.take(12)}"
    } else {
        label
    }
}

// ---- page ------------------------------------------------------------------------------------

/** The reply sequence of [key] in [draft], edited as a reorderable timeline of steps. */
@Composable
fun AutomationReplyScreen(
    spec: AutomationSpec,
    draft: AutomationScopeDraft,
    key: AutomationRuleKey<AutomationReplyRule>,
    context: AutomationEditorContext,
    onBack: () -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    val editor = spec.editors.first { it.key == key } as AutomationRuleEditor<AutomationReplyRule>
    val rule =
        draft.values[key] ?: draft.openedValues[key.id] as AutomationReplyRule? ?: key.default
    val switchable = editor.isOn != null
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var addingKind by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Int, AutomationReplyStep>?>(null) }
    var editingDelay by remember { mutableStateOf<Int?>(null) }
    val favorites by
        produceState(emptyMap()) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { WeFavoriteApi.listFavorites(1000).associateBy { it.localId } }
                        .getOrDefault(emptyMap())
                }
        }

    fun update(steps: List<AutomationReplyStep>) = draft.set(key, rule.copy(steps = steps))

    fun delete(index: Int) {
        val previous = rule.steps
        update(previous.toMutableList().apply { removeAt(index) })
        coroutineScope.launch {
            val result =
                snackbar.showSnackbar(
                    message = context.activity.getString(R.string.automation_step_deleted),
                    actionLabel = context.activity.getString(R.string.automation_undo),
                    withDismissAction = true,
                )
            if (result == SnackbarResult.ActionPerformed) update(previous)
        }
    }

    SettingsScaffold(
        title = stringResource(editor.titleFor(draft.scope)),
        onBack = onBack,
        listState = listState,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                onClick = { addingKind = true },
                icon = { Icon(MaterialSymbols.Outlined.Add, null) },
                text = { Text(stringResource(R.string.automation_add_step)) },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        },
    ) {
        item(key = "hint") {
            Text(
                text = stringResource(R.string.automation_reply_page_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
        item(key = "options") {
            SegmentedColumn {
                if (switchable) {
                    item(key = "enabled") {
                        SwitchWidget(
                            title = stringResource(editor.titleFor(draft.scope)),
                            description =
                                stringResource(
                                    if (rule.enabled) {
                                        R.string.automation_item_enabled
                                    } else {
                                        R.string.automation_reply_off_hint
                                    },
                                ),
                            checked = rule.enabled,
                            onCheckedChange = { draft.set(key, rule.copy(enabled = it)) },
                        )
                    }
                }
                item(key = "quote") {
                    SwitchWidget(
                        title = stringResource(R.string.automation_reply_as_quote),
                        description = stringResource(R.string.automation_reply_as_quote_summary),
                        checked = rule.asQuote,
                        onCheckedChange = { draft.set(key, rule.copy(asQuote = it)) },
                    )
                }
            }
        }
        item(key = "timeline") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.automation_reply_order),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp),
                ) {
                    Box(
                        Modifier.size(24.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Bolt,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.automation_reply_trigger),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rule.steps.isEmpty()) {
                    TimelineConnector {}
                    BaseWidget(
                        title = stringResource(R.string.automation_reply_no_steps),
                        description = stringResource(R.string.automation_reply_no_steps_summary),
                    )
                }
                val reorder = rememberAutomationReorderState { from, to ->
                    update(rule.steps.toMutableList().apply { add(to, removeAt(from)) })
                }
                Column {
                    rule.steps.forEachIndexed { index, step ->
                        Column(Modifier.reorderableItem(reorder, index)) {
                            TimelineConnector {
                                DelayPill(step) { editingDelay = index }
                            }
                            StepCard(
                                index = index,
                                step = step,
                                count = rule.steps.size,
                                favorites = favorites,
                                handle = Modifier.reorderHandle(reorder, index),
                                onEdit = { editing = index to step },
                                onDuplicate = {
                                    update(
                                        rule.steps.toMutableList().apply { add(index + 1, step) },
                                    )
                                },
                                onMove = { to ->
                                    update(
                                        rule.steps.toMutableList().apply {
                                            add(to, removeAt(index))
                                        },
                                    )
                                },
                                onDelete = { delete(index) },
                            )
                        }
                    }
                }
                if (rule.steps.isEmpty() && (!switchable || rule.enabled)) {
                    Text(
                        text = stringResource(R.string.automation_reply_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET + 72.dp)) }
    }

    if (addingKind) {
        StepKindSheet(onDismiss = { addingKind = false }) { kind ->
            addingKind = false
            editing = -1 to AutomationReplyStep(kind = kind)
        }
    }
    editing?.let { (index, step) ->
        StepSheet(
            initial = step,
            index = index,
            placeholders = editor.replyPlaceholders,
            context = context,
            favorites = favorites,
            onDismiss = { editing = null },
            onDelete =
                if (index >= 0) {
                    ({
                        editing = null
                        delete(index)
                    })
                } else {
                    null
                },
            onDone = { edited ->
                editing = null
                update(
                    if (index >= 0) {
                        rule.steps.toMutableList().apply { set(index, edited) }
                    } else {
                        rule.steps + edited
                    },
                )
            },
        )
    }
    editingDelay?.let { index ->
        val step = rule.steps[index]
        StepDelayDialog(
            step = step,
            onDismiss = { editingDelay = null },
            onConfirm = { delay, random ->
                editingDelay = null
                update(
                    rule.steps.toMutableList().apply {
                        set(index, step.copy(delayMs = delay, randomDelay = random))
                    },
                )
            },
        )
    }
}

/** A vertical line from the previous step with optional [content] (the delay pill) beside it. */
@Composable
private fun TimelineConnector(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 21.dp),
    ) {
        Box(
            Modifier.width(2.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))
        content()
    }
}

@Composable
private fun DelayPill(step: AutomationReplyStep, onClick: () -> Unit) {
    val immediate = step.delayMs == 0L && !step.randomDelay
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color =
            if (immediate) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MaterialSymbols.Outlined.Schedule,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text =
                    when {
                        immediate -> {
                            stringResource(R.string.automation_step_send_now)
                        }

                        step.randomDelay -> {
                            stringResource(R.string.automation_step_wait_random, step.delayMs)
                        }

                        else -> {
                            stringResource(R.string.automation_step_wait, step.delayMs)
                        }
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    step: AutomationReplyStep,
    count: Int,
    favorites: Map<Long, WeFavoriteApi.FavoriteSummary>,
    handle: Modifier,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            KindTile(step.kind)
            Column(
                Modifier.weight(1f).padding(start = 12.dp),
            ) {
                Text(
                    text =
                        "${index + 1} · " +
                            stringResource(REPLY_STEP_KIND_LABELS.getValue(step.kind)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepPreview(step, favorites)
            }
            Icon(
                MaterialSymbols.Outlined.Drag_indicator,
                contentDescription = stringResource(R.string.automation_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handle.padding(8.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        MaterialSymbols.Outlined.More_vert,
                        stringResource(R.string.automation_more),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Edit, null) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.automation_duplicate)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Content_copy, null) },
                        onClick = {
                            menu = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.automation_move_up)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Arrow_upward, null) },
                        enabled = index > 0,
                        onClick = {
                            menu = false
                            onMove(index - 1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.automation_move_down)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Arrow_downward, null) },
                        enabled = index < count - 1,
                        onClick = {
                            menu = false
                            onMove(index + 1)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KindTile(kind: AutomationReplyStepKind, size: Int = 40) {
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kindIcon(kind), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** "[AtWx=wxid]" replaced by "@name". */
@Composable
private fun withMentionNames(text: String): String =
    remember(text) {
        AT_REGEX.replace(text) {
            "@" + WeDatabaseApi.getDisplayName(it.groupValues[1]).ifBlank { it.groupValues[1] }
        }
    }

@Composable
private fun StepPreview(
    step: AutomationReplyStep,
    favorites: Map<Long, WeFavoriteApi.FavoriteSummary>,
) {
    val values = splitReplyValues(step.content)
    when (step.kind) {
        AutomationReplyStepKind.TEXT -> {
            val messages =
                withMentionNames(step.content)
                    .split('|')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                messages.forEach { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier =
                            Modifier.clip(
                                    RoundedCornerShape(
                                        topStart = 4.dp,
                                        topEnd = 16.dp,
                                        bottomStart = 16.dp,
                                        bottomEnd = 16.dp,
                                    ),
                                )
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        AutomationReplyStepKind.XML -> {
            Text(
                text = step.content.take(120),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        AutomationReplyStepKind.FAVORITE -> {
            AutomationMiniChips(
                values.map { id ->
                    favorites[id.toLongOrNull()]?.title?.ifBlank { null } ?: "#$id"
                },
                max = 6,
            )
        }

        AutomationReplyStepKind.CARD,
        AutomationReplyStepKind.INVITE_GROUP -> {
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                values.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AutomationAvatar(id, 24.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(rememberContactName(id), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        else -> {
            if (
                step.kind == AutomationReplyStepKind.IMAGE ||
                    step.kind == AutomationReplyStepKind.EMOJI
            ) {
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    values.take(4).forEach { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            imageLoader = GlobalImageLoader,
                            modifier =
                                Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                        )
                    }
                }
            }
            Text(
                text = values.joinToString("、") { File(it).name.substringAfter('_') },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---- sheets & dialogs
// ------------------------------------------------------------------------------

@Composable
private fun StepKindSheet(onDismiss: () -> Unit, onPick: (AutomationReplyStepKind) -> Unit) {
    AutomationSheet(
        title = stringResource(R.string.automation_add_step),
        onDismiss = onDismiss,
        onConfirm = null,
    ) {
        KIND_GROUPS.forEach { (groupRes, kinds) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationFieldLabel(stringResource(groupRes))
                kinds.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { kind ->
                            Surface(
                                onClick = { onPick(kind) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceBright,
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(
                                    Modifier.padding(vertical = 14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    KindTile(kind, 44)
                                    Text(
                                        stringResource(REPLY_STEP_KIND_LABELS.getValue(kind)),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDelayDialog(
    step: AutomationReplyStep,
    onDismiss: () -> Unit,
    onConfirm: (Long, Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(step.delayMs.toString()) }
    var random by remember { mutableStateOf(step.randomDelay) }
    val parsed = text.toLongOrNull()?.takeIf { it <= 600_000L }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_step_wait_before)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    suffix = { Text(stringResource(R.string.automation_unit_ms)) },
                    isError = parsed == null,
                    supportingText = { Text(stringResource(R.string.automation_step_wait_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                AutomationInlineSwitch(
                    stringResource(R.string.automation_step_random_delay),
                    random,
                ) {
                    random = it
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { onConfirm(parsed!!, random) }) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Editor of one step: a content editor specific to its kind, then the wait before it. */
@Composable
private fun StepSheet(
    initial: AutomationReplyStep,
    index: Int,
    placeholders: List<String>,
    context: AutomationEditorContext,
    favorites: Map<Long, WeFavoriteApi.FavoriteSummary>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onDone: (AutomationReplyStep) -> Unit,
) {
    var step by remember { mutableStateOf(initial) }
    var text by remember {
        mutableStateOf(TextFieldValue(initial.content, TextRange(initial.content.length)))
    }
    var focused by remember { mutableStateOf(false) }
    val kind = step.kind
    val usesText = kind == AutomationReplyStepKind.TEXT || kind == AutomationReplyStepKind.XML
    val content = if (usesText) text.text else step.content
    val values = splitReplyValues(step.content)

    fun setValues(list: List<String>) {
        step = step.copy(content = list.joinToString("\n"))
    }

    AutomationSheet(
        title = stringResource(REPLY_STEP_KIND_LABELS.getValue(kind)),
        subtitle =
            if (index >= 0) {
                stringResource(R.string.automation_step_number, index + 1)
            } else {
                stringResource(R.string.automation_step_new)
            },
        icon = kindIcon(kind),
        onDismiss = onDismiss,
        onConfirm = { onDone(step.copy(content = content.trim())) },
        confirmEnabled = content.isNotBlank(),
        footerStart = { finish ->
            if (onDelete != null) {
                TextButton(onClick = { finish(onDelete) }) {
                    Icon(
                        MaterialSymbols.Outlined.Delete,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    ) {
        when {
            kind == AutomationReplyStepKind.TEXT -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.automation_step_message)) },
                        supportingText = {
                            Text(stringResource(R.string.automation_step_text_hint))
                        },
                        minLines = 3,
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    )
                    AutomationFieldLabel(stringResource(R.string.automation_insert_placeholder))
                    PlaceholderChips(placeholders, text, focused, { text = it })
                    AssistChip(
                        onClick = {
                            val selection = text.selection
                            context.picker.open(
                                AutomationPick.Contact(
                                    title =
                                        context.activity.getString(
                                            R.string.automation_mention_contact,
                                        ),
                                    loadContacts = {
                                        loadAutomationContacts(
                                            setOf(AutomationTalkerCategory.PRIVATE),
                                        )
                                    },
                                    onPick = { contact ->
                                        val token = "[AtWx=${contact.wxId}]"
                                        val start = minOf(selection.start, selection.end)
                                        val end = maxOf(selection.start, selection.end)
                                        text =
                                            TextFieldValue(
                                                text.text.replaceRange(start, end, token),
                                                TextRange(start + token.length),
                                            )
                                    },
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.automation_mention_contact)) },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Alternate_email,
                                null,
                                Modifier.size(18.dp),
                            )
                        },
                    )
                }
                if (text.text.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AutomationFieldLabel(stringResource(R.string.automation_preview))
                        StepPreview(step.copy(content = text.text), favorites)
                    }
                }
            }

            kind == AutomationReplyStepKind.XML -> {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.automation_step_xml)) },
                    supportingText = { Text(stringResource(R.string.automation_step_xml_hint)) },
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            kind.usesFiles -> {
                ValueList(
                    label =
                        stringResource(
                            if (kind == AutomationReplyStepKind.RANDOM_VOICE) {
                                R.string.automation_step_random_voice_hint
                            } else {
                                R.string.automation_step_files_hint
                            },
                        ),
                    values = values,
                    icon = kindIcon(kind),
                    name = { File(it).name.substringAfter('_').ifEmpty { it } },
                    detail = { stringResource(R.string.automation_step_file_copied) },
                    onRemove = { setValues(values - it) },
                    buttonLabel = stringResource(R.string.automation_pick_files),
                    buttonIcon = MaterialSymbols.Outlined.Attach_file,
                    onAdd = {
                        context.pickFiles(mimeTypesFor(kind)) { paths -> setValues(values + paths) }
                    },
                )
            }

            kind == AutomationReplyStepKind.FAVORITE -> {
                ValueList(
                    label = stringResource(R.string.automation_step_favorite_hint),
                    values = values,
                    icon = MaterialSymbols.Outlined.Star,
                    name = { id ->
                        favorites[id.toLongOrNull()]?.title?.ifBlank { null } ?: "#$id"
                    },
                    detail = { id ->
                        favorites[id.toLongOrNull()]?.let { favoriteTypeLabel(it.type) }.orEmpty()
                    },
                    onRemove = { setValues(values - it) },
                    buttonLabel = stringResource(R.string.automation_pick_favorites),
                    buttonIcon = MaterialSymbols.Outlined.Star,
                    onAdd = {
                        context.picker.open(
                            AutomationPick.Favorites(
                                title =
                                    context.activity.getString(R.string.automation_pick_favorites),
                                initialSelected = values.mapNotNull(String::toLongOrNull).toSet(),
                                onConfirm = { ids -> setValues(ids.map(Long::toString)) },
                            ),
                        )
                    },
                )
            }

            else -> {
                val groups = kind == AutomationReplyStepKind.INVITE_GROUP
                ValueList(
                    label =
                        stringResource(
                            if (groups) {
                                R.string.automation_step_invite_group_hint
                            } else {
                                R.string.automation_step_card_hint
                            },
                        ),
                    values = values,
                    avatar = true,
                    name = { rememberContactName(it) },
                    detail = { it },
                    onRemove = { setValues(values - it) },
                    buttonLabel =
                        stringResource(
                            if (groups) {
                                R.string.automation_pick_groups
                            } else {
                                R.string.automation_pick_contacts
                            },
                        ),
                    buttonIcon = kindIcon(kind),
                    onAdd = {
                        context.picker.open(
                            AutomationPick.Contacts(
                                title =
                                    context.activity.getString(
                                        REPLY_STEP_KIND_LABELS.getValue(kind),
                                    ),
                                loadContacts = {
                                    loadAutomationContacts(
                                        if (groups) {
                                            setOf(AutomationTalkerCategory.GROUP)
                                        } else {
                                            setOf(
                                                AutomationTalkerCategory.PRIVATE,
                                                AutomationTalkerCategory.OFFICIAL,
                                            )
                                        },
                                    )
                                },
                                initialSelected = values.toSet(),
                                onConfirm = { setValues(it.toList()) },
                            ),
                        )
                    },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomationNumberSlider(
                title = stringResource(R.string.automation_step_wait_before),
                value = step.delayMs,
                range = 0..10_000,
                step = 100,
                unit = stringResource(R.string.automation_unit_ms),
                maxExact = 600_000,
                onChange = { step = step.copy(delayMs = it) },
            )
            AutomationInlineSwitch(
                title = stringResource(R.string.automation_step_random_delay),
                checked = step.randomDelay,
                description = stringResource(R.string.automation_step_random_delay_summary),
            ) {
                step = step.copy(randomDelay = it)
            }
        }
    }
}

/** Picked items (files, favorites, contacts) with remove buttons and a button adding more. */
@Composable
private fun ValueList(
    label: String,
    values: List<String>,
    name: @Composable (String) -> String,
    detail: @Composable (String) -> String,
    onRemove: (String) -> Unit,
    buttonLabel: String,
    buttonIcon: ImageVector,
    onAdd: () -> Unit,
    icon: ImageVector? = null,
    avatar: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(label)
        if (values.isEmpty()) {
            Text(
                stringResource(R.string.automation_none_selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            values.forEach { value ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceBright)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (avatar) {
                        AutomationAvatar(value)
                    } else if (icon != null) {
                        Box(
                            Modifier.size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                    Column(
                        Modifier.weight(1f).padding(start = 12.dp),
                    ) {
                        Text(
                            name(value),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = detail(value)
                        if (sub.isNotEmpty()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(value) }) {
                        Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.action_delete))
                    }
                }
            }
        }
        FilledTonalButton(onClick = onAdd) {
            Icon(buttonIcon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(buttonLabel)
        }
    }
}
