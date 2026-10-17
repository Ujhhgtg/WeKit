package dev.ujhhgtg.wekit.ui.automation

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Bookmark
import com.composables.icons.materialsymbols.outlined.Bookmarks
import com.composables.icons.materialsymbols.outlined.Campaign
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_indicator
import com.composables.icons.materialsymbols.outlined.Edit_note
import com.composables.icons.materialsymbols.outlined.Group
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Person_add
import com.composables.icons.materialsymbols.outlined.Public
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRuleValues
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSnapshot
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationStoredScope
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.LocalSegmentedItemShape
import dev.ujhhgtg.wekit.ui.content.m3.SETTINGS_CONTENT_BOTTOM_INSET
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SettingsConfirmDialog
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.content.m3.TitleTipCard
import dev.ujhhgtg.wekit.ui.content.m3.rememberCreationBackGuard
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlin.io.path.div
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.io.path.outputStream

private const val TAG = "AutomationScreens"

// ---- shared state ----------------------------------------------------------------------------

/** Current stored configuration; recomposes after every save. */
@Composable
fun rememberAutomationSnapshot(spec: AutomationSpec): AutomationSnapshot {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(spec) {
        val listener: () -> Unit = { version++ }
        spec.store.addListener(listener)
        onDispose { spec.store.removeListener(listener) }
    }
    return remember(version) { spec.store.snapshot() }
}

/** Launches the system document picker and copies picked files into module storage. */
@Composable
fun rememberAutomationFilePicker(): (Array<String>, (List<String>) -> Unit) -> Unit {
    val activity = LocalActivity.current as ComponentActivity
    var pending by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val callback = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uris.isNotEmpty()) callback(uris.mapNotNull { copyPickedFile(activity, it) })
    }
    return { mimeTypes, onPicked ->
        pending = onPicked
        launcher.launch(mimeTypes)
    }
}

private fun copyPickedFile(activity: ComponentActivity, uri: Uri): String? = runCatching {
    val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "file"
    val dir = (KnownPaths.moduleRoot / "automation_media").createDirsSafe()
    val target = dir / "${UUID.randomUUID().toString().take(8)}_${name.replace('/', '_')}"
    activity.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    target.toString()
}.onFailure { WeLogger.e(TAG, "failed to copy picked file $uri", it) }.getOrNull()

/** Runs [block] off the composition, toasting a failure. */
private fun ComponentActivity.persist(scope: kotlinx.coroutines.CoroutineScope, what: String, block: suspend () -> Unit) {
    scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to $what", e)
            showToast(this@persist, getString(R.string.structured_storage_save_failed))
        }
    }
}

/** Hides FABs while the list scrolls down and shows them again on scroll up (InstallerX pattern). */
@Composable
private fun rememberFabVisible(listState: LazyListState): Boolean {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { current ->
            val down = current.first > previous.first || (current.first == previous.first && current.second > previous.second + 8)
            val up = current.first < previous.first || (current.first == previous.first && current.second < previous.second - 8)
            if (down) visible = false else if (up) visible = true
            previous = current
        }
    }
    return visible
}

@Composable
private fun AutomationFab(visible: Boolean, text: String, icon: ImageVector, attention: Boolean = true, onClick: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = scaleIn(), exit = scaleOut(), modifier = Modifier.padding(bottom = 16.dp)) {
        SmallExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(icon, null) },
            text = { Text(text) },
            containerColor = if (attention) FloatingActionButtonDefaults.containerColor else MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

// ---- scope titles ----------------------------------------------------------------------------

@Composable
fun automationScopeTitle(spec: AutomationSpec, scope: AutomationScope, name: String?): String = when (scope.type) {
    AutomationScopeType.GLOBAL -> stringResource(R.string.automation_global_settings)
    AutomationScopeType.CATEGORY -> stringResource(categoryDefaultLabel(scope.category!!))
    AutomationScopeType.CONTACT -> rememberContactName(scope.scopeId)
    AutomationScopeType.GROUP_MEMBER -> rememberMemberName(scope.scopeId, scope.memberId)
    AutomationScopeType.PRESET -> name ?: scope.scopeId
    AutomationScopeType.ITEM -> spec.fixedItems.firstOrNull { it.first == scope.scopeId }?.let { stringResource(it.second) }
        ?: name ?: scope.scopeId
}

fun categoryDefaultLabel(category: AutomationTalkerCategory): Int = when (category) {
    AutomationTalkerCategory.PRIVATE -> R.string.automation_category_private_default
    AutomationTalkerCategory.GROUP -> R.string.automation_category_group_default
    AutomationTalkerCategory.OFFICIAL -> R.string.automation_category_official_default
}

private fun categoryIcon(category: AutomationTalkerCategory): ImageVector = when (category) {
    AutomationTalkerCategory.PRIVATE -> MaterialSymbols.Outlined.Person
    AutomationTalkerCategory.GROUP -> MaterialSymbols.Outlined.Group
    AutomationTalkerCategory.OFFICIAL -> MaterialSymbols.Outlined.Campaign
}

/** Stored rules shown as overrides, not counting the [AutomationSpec.scopeSwitch]. */
private fun ownKeys(spec: AutomationSpec, stored: AutomationStoredScope): List<String> =
    stored.values.keyIds.filter { id -> id != spec.scopeSwitch?.id && spec.editors.any { it.key.id == id } }

/** "Delay · Keywords · Reply" style list of what [stored] overrides, or null when nothing. */
@Composable
private fun overriddenRuleNames(spec: AutomationSpec, stored: AutomationStoredScope): String? {
    val names = ownKeys(spec, stored).map { id -> stringResource(spec.editors.first { it.key.id == id }.titleRes) }
    if (names.isEmpty()) return null
    return if (names.size <= 3) names.joinToString(" · ")
    else stringResource(R.string.automation_overridden_more, names.take(3).joinToString(" · "), names.size)
}

@Composable
private fun PresetTags(spec: AutomationSpec, snapshot: AutomationSnapshot, presets: List<String>) {
    presets.forEach { id ->
        AutomationTag(snapshot[AutomationScope.preset(id)].name ?: id, Modifier.padding(start = 8.dp), AutomationTagTone.Tertiary)
    }
}

private fun AutomationSpec.isOn(snapshot: AutomationSnapshot, scope: AutomationScope) = snapshot.isSwitchedOn(scope)

private fun AutomationSpec.switchedValues(stored: AutomationStoredScope, on: Boolean): AutomationRuleValues {
    val key = scopeSwitch!!
    return if (on) stored.values.without(key) else stored.values.with(key, AutomationToggleRule(false))
}

// ---- home ------------------------------------------------------------------------------------

@Composable
fun AutomationHomeScreen(
    spec: AutomationSpec,
    context: AutomationEditorContext,
    onBack: () -> Unit,
    onOpenScope: (AutomationScope, String?, Int) -> Unit,
    onBatch: (List<AutomationScope>) -> Unit,
) {
    if (AutomationScopeType.ITEM in spec.scopeTypes) {
        AutomationItemsHome(spec, context, onBack, onOpenScope)
    } else {
        AutomationScopesHome(spec, context, onBack, onOpenScope, onBatch)
    }
}

@Composable
private fun AutomationScopesHome(
    spec: AutomationSpec,
    context: AutomationEditorContext,
    onBack: () -> Unit,
    onOpenScope: (AutomationScope, String?, Int) -> Unit,
    onBatch: (List<AutomationScope>) -> Unit,
) {
    val activity = context.activity
    val snapshot = rememberAutomationSnapshot(spec)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val fabVisible = rememberFabVisible(listState)
    var selection by remember { mutableStateOf<Set<AutomationScope>?>(null) }
    var search by remember { mutableStateOf<String?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val configured = snapshot.ofType(AutomationScopeType.CONTACT).map { it.scope }
    val memberOnly = snapshot.scopes.keys.filter { it.type == AutomationScopeType.GROUP_MEMBER }
        .map { AutomationScope.contact(it.scopeId) }.filter { it !in configured }.distinct()
    val chats = configured + memberOnly
    val chatTitles = chats.associateWith { rememberContactName(it.scopeId) }
    val query = search.orEmpty().trim()
    val shownChats = chats.filter { query.isEmpty() || chatTitles.getValue(it).contains(query, ignoreCase = true) }
    val selected = selection

    BackHandler(enabled = selected != null || search != null) {
        if (selected != null) selection = null else search = null
    }
    val addChat = {
        context.picker.open(
            AutomationPick.Contact(
                title = activity.getString(R.string.automation_add_contact),
                loadContacts = { loadAutomationContacts(spec.contactKinds) },
                isMarked = { c -> AutomationScope.contact(c.wxId) in snapshot.scopes },
                onPick = { contact -> onOpenScope(AutomationScope.contact(contact.wxId), null, 0) },
            )
        )
    }

    SettingsScaffold(
        title = if (selected != null) pluralStringResource(R.plurals.automation_selected_count, selected.size, selected.size)
        else stringResource(spec.titleRes),
        onBack = { if (selected != null) selection = null else onBack() },
        listState = listState,
        actions = {
            if (selected != null) {
                IconButton(onClick = { selection = chats.toSet() }) { Icon(MaterialSymbols.Outlined.Select_all, stringResource(R.string.automation_select_all)) }
                IconButton(enabled = selected.isNotEmpty(), onClick = { confirmBatchDelete = true }) {
                    Icon(MaterialSymbols.Outlined.Delete, stringResource(R.string.action_delete))
                }
            } else if (search == null && chats.isNotEmpty()) {
                IconButton(onClick = { search = "" }) { Icon(MaterialSymbols.Outlined.Search, stringResource(R.string.search_hint)) }
            }
        },
        pinnedHeader = if (search != null && selected == null) {
            {
                OutlinedTextField(
                    value = search.orEmpty(),
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.automation_search_chats)) },
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, null) },
                    trailingIcon = { IconButton(onClick = { search = null }) { Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.dialog_cancel)) } },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        } else null,
        floatingActionButton = {
            if (selected != null) {
                AutomationFab(fabVisible, pluralStringResource(R.plurals.automation_batch_edit_count, selected.size, selected.size), MaterialSymbols.Outlined.Edit_note, selected.isNotEmpty()) {
                    if (selected.isNotEmpty()) {
                        selection = null
                        onBatch(selected.toList())
                    }
                }
            } else if (search == null) {
                AutomationFab(fabVisible, stringResource(R.string.automation_add_chat), MaterialSymbols.Outlined.Person_add, onClick = addChat)
            }
        },
    ) {
        if (selected == null && search == null) {
            item(key = "tip") {
                val chain = buildList {
                    add(stringResource(R.string.automation_chain_global))
                    if (AutomationScopeType.CATEGORY in spec.scopeTypes) add(stringResource(R.string.automation_chain_category))
                    add(stringResource(R.string.automation_chain_chat))
                    if (AutomationScopeType.GROUP_MEMBER in spec.scopeTypes) add(stringResource(R.string.automation_chain_member))
                }
                TitleTipCard(
                    title = stringResource(R.string.automation_inheritance_title),
                    text = stringResource(R.string.automation_inheritance_text) + "\n" + chain.joinToString("  ›  "),
                )
            }
            item(key = "defaults") {
                SegmentedColumn(title = stringResource(R.string.automation_section_defaults)) {
                    item(key = "global") {
                        val stored = snapshot[AutomationScope.Global]
                        val count = ownKeys(spec, stored).size
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Public,
                            title = stringResource(R.string.automation_global_settings),
                            description = stringResource(spec.globalSummaryRes),
                            headlineTrailingContent = {
                                if (count > 0) AutomationTag(pluralStringResource(R.plurals.automation_rules_set, count, count), Modifier.padding(start = 8.dp), AutomationTagTone.Muted)
                            },
                            onClick = { onOpenScope(AutomationScope.Global, null, 0) },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                        )
                    }
                    if (AutomationScopeType.CATEGORY in spec.scopeTypes) {
                        spec.categories.forEach { category ->
                            val scope = AutomationScope.category(category)
                            item(key = "category_$category") {
                                val stored = snapshot[scope]
                                val count = ownKeys(spec, stored).size
                                BaseWidget(
                                    icon = categoryIcon(category),
                                    title = stringResource(categoryDefaultLabel(category)),
                                    description = overriddenRuleNames(spec, stored) ?: stringResource(R.string.automation_follow_global_all),
                                    headlineTrailingContent = {
                                        if (count > 0) AutomationTag(pluralStringResource(R.plurals.automation_overrides, count, count), Modifier.padding(start = 8.dp))
                                        PresetTags(spec, snapshot, stored.presets)
                                    },
                                    onClick = { onOpenScope(scope, null, 0) },
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "chats") {
            SegmentedColumn(
                title = stringResource(if (selected != null) R.string.automation_select_chats else R.string.automation_section_chats),
                titleAction = if (selected == null && search == null) {
                    { TitleActionButton(stringResource(R.string.automation_add_item), MaterialSymbols.Outlined.Person_add, addChat) }
                } else null,
            ) {
                if (shownChats.isEmpty()) {
                    item(key = "empty") {
                        BaseWidget(
                            title = stringResource(if (query.isEmpty()) R.string.automation_no_chats else R.string.contact_no_matches),
                            description = if (query.isEmpty()) stringResource(R.string.automation_no_chats_summary) else null,
                        )
                    }
                }
                shownChats.forEach { scope ->
                    item(key = scope.scopeId) {
                        val stored = snapshot.scopes[scope]
                        val members = snapshot.membersOf(scope.scopeId).size
                        val count = stored?.let { ownKeys(spec, it).size } ?: 0
                        val switchedOff = stored != null && !spec.isOn(snapshot, scope)
                        val isSelected = selected != null && scope in selected
                        BaseWidget(
                            title = chatTitles.getValue(scope),
                            description = stored?.let { overriddenRuleNames(spec, it) } ?: stringResource(
                                if (stored == null) R.string.automation_only_members else R.string.automation_follow_parent_settings
                            ),
                            descriptionContent = if (members > 0) {
                                { Text(pluralStringResource(R.plurals.automation_configured_members, members, members), style = MaterialTheme.typography.bodySmall) }
                            } else null,
                            headlineTrailingContent = {
                                when {
                                    switchedOff -> AutomationTag(stringResource(R.string.automation_switched_off), Modifier.padding(start = 8.dp), AutomationTagTone.Warning)
                                    count > 0 -> AutomationTag(pluralStringResource(R.plurals.automation_rules_set, count, count), Modifier.padding(start = 8.dp))
                                }
                                if (stored != null) PresetTags(spec, snapshot, stored.presets)
                            },
                            leadingContent = {
                                if (selected != null) Checkbox(checked = isSelected, onCheckedChange = null)
                                else AutomationAvatar(scope.scopeId)
                            },
                            selected = isSelected,
                            onClick = {
                                if (selected != null) selection = if (isSelected) selected - scope else selected + scope
                                else onOpenScope(scope, null, 0)
                            },
                            onLongClick = if (selected == null) ({ selection = setOf(scope) }) else null,
                            trailingDivider = selected == null && stored != null && spec.scopeSwitch != null,
                            trailingContent = {
                                if (selected == null && stored != null && spec.scopeSwitch != null) {
                                    AutomationSwitch(!switchedOff, { on ->
                                        activity.persist(coroutineScope, "switch ${spec.id} $scope") {
                                            spec.store.save(stored.copy(values = spec.switchedValues(stored, on)))
                                        }
                                    })
                                }
                            },
                        )
                    }
                }
            }
            if (selected == null && search == null && chats.size > 1) {
                Text(
                    text = stringResource(R.string.automation_long_press_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        if (AutomationScopeType.PRESET in spec.scopeTypes && selected == null && search == null) {
            item(key = "presets") {
                val presets = snapshot.ofType(AutomationScopeType.PRESET)
                SegmentedColumn(
                    title = stringResource(R.string.automation_presets),
                    titleAction = {
                        TitleActionButton(stringResource(R.string.automation_new), MaterialSymbols.Outlined.Add) {
                            onOpenScope(
                                AutomationScope.preset(UUID.randomUUID().toString()),
                                activity.getString(R.string.automation_new_preset_name, presets.size + 1),
                                (presets.maxOfOrNull { it.position } ?: -1) + 1,
                            )
                        }
                    },
                ) {
                    if (presets.isEmpty()) {
                        item(key = "empty") {
                            BaseWidget(icon = MaterialSymbols.Outlined.Bookmarks, title = stringResource(R.string.automation_no_presets), description = stringResource(R.string.automation_presets_summary))
                        }
                    }
                    presets.forEach { stored ->
                        item(key = stored.scope.scopeId) {
                            val used = snapshot.scopes.values.count { stored.scope.scopeId in it.presets }
                            val switchedOff = !spec.isOn(snapshot, stored.scope)
                            val count = ownKeys(spec, stored).size
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Bookmark,
                                title = stored.name ?: stored.scope.scopeId,
                                description = pluralStringResource(R.plurals.automation_rules_set, count, count) + " · " +
                                        if (used == 0) stringResource(R.string.automation_preset_unused)
                                        else pluralStringResource(R.plurals.automation_preset_used_by, used, used),
                                headlineTrailingContent = {
                                    if (switchedOff) AutomationTag(stringResource(R.string.automation_switched_off), Modifier.padding(start = 8.dp), AutomationTagTone.Warning)
                                },
                                onClick = { onOpenScope(stored.scope, null, 0) },
                                trailingDivider = spec.scopeSwitch != null,
                                trailingContent = {
                                    if (spec.scopeSwitch != null) {
                                        AutomationSwitch(!switchedOff, { on ->
                                            activity.persist(coroutineScope, "switch ${spec.id} ${stored.scope}") {
                                                spec.store.save(stored.copy(values = spec.switchedValues(stored, on)))
                                            }
                                        })
                                    } else {
                                        Icon(MaterialSymbols.Outlined.Chevron_right, null)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET + 72.dp)) }
    }

    val batchSelection = selected.orEmpty()
    SettingsConfirmDialog(
        show = confirmBatchDelete,
        title = pluralStringResource(R.plurals.automation_delete_chats_title, batchSelection.size, batchSelection.size),
        message = stringResource(R.string.automation_delete_chats_message),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            confirmBatchDelete = false
            selection = null
            activity.persist(coroutineScope, "delete ${spec.id} chats") { batchSelection.forEach { spec.store.delete(it) } }
        },
        onDismiss = { confirmBatchDelete = false },
    )
}

@Composable
private fun TitleActionButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(text)
    }
}

/** Summaries of an ITEM's non-default rules, e.g. its triggers, as chips. */
@Composable
private fun itemChips(spec: AutomationSpec, rules: AutomationRules): List<String> =
    spec.itemSummaryKeys.mapNotNull { key ->
        @Suppress("UNCHECKED_CAST")
        val editor = spec.editors.first { it.key == key } as AutomationRuleEditor<Any>
        val value = rules.getAny(editor)
        if (value == editor.key.default || editor.isOn?.invoke(value) == false) null else editor.summary(value)
    }

@Composable
private fun AutomationItemsHome(
    spec: AutomationSpec,
    context: AutomationEditorContext,
    onBack: () -> Unit,
    onOpenScope: (AutomationScope, String?, Int) -> Unit,
) {
    val activity = context.activity
    val snapshot = rememberAutomationSnapshot(spec)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val fabVisible = rememberFabVisible(listState)
    val itemSwitch = spec.itemSwitch
    val items = snapshot.ofType(AutomationScopeType.ITEM).filter { stored -> spec.fixedItems.none { it.first == stored.scope.scopeId } }
    var order by remember(items) { mutableStateOf(items) }
    var sheet by remember { mutableStateOf<Pair<AutomationRuleEditor<*>, Any>?>(null) }
    val globalRules = snapshot.effectiveRules(AutomationScope.Global)

    fun setItemSwitch(stored: AutomationStoredScope, on: Boolean) = activity.persist(coroutineScope, "switch ${spec.id} ${stored.scope}") {
        spec.store.save(stored.copy(values = stored.values.with(itemSwitch!!, AutomationToggleRule(on))))
    }

    fun saveGlobal(key: AutomationRuleKey<*>, value: Any) = activity.persist(coroutineScope, "save ${spec.id} global") {
        val stored = snapshot[AutomationScope.Global]
        @Suppress("UNCHECKED_CAST")
        spec.store.save(stored.copy(values = stored.values.with(key as AutomationRuleKey<Any>, value)))
        showToast(activity, activity.getString(R.string.settings_saved))
    }

    val newName = spec.newItemNameRes.let { stringResource(it, items.size + 1) }
    SettingsScaffold(
        title = stringResource(spec.titleRes),
        onBack = onBack,
        listState = listState,
        floatingActionButton = {
            if (spec.itemsTitleRes != null) {
                AutomationFab(fabVisible, stringResource(R.string.automation_new_rule), MaterialSymbols.Outlined.Add) {
                    onOpenScope(AutomationScope.item(UUID.randomUUID().toString()), newName, (items.maxOfOrNull { it.position } ?: -1) + 1)
                }
            }
        },
    ) {
        val globalEditors = spec.editors.filter { AutomationScopeType.GLOBAL in spec.scopeTypes && it.visibleIn(AutomationScope.Global) }
        if (globalEditors.isNotEmpty()) {
            item(key = "global") {
                SegmentedColumn(title = stringResource(R.string.automation_section_scope)) {
                    globalEditors.forEach { editor ->
                        @Suppress("UNCHECKED_CAST")
                        val typed = editor as AutomationRuleEditor<Any>
                        automationRuleItem(
                            spec, typed, globalRules.getAny(typed), AutomationRuleMode.PLAIN, AutomationScope.Global, globalRules, context,
                            AutomationRuleActions(onChange = ::saveGlobal, onOpen = { e, v -> sheet = e to v }),
                        )
                    }
                }
            }
        }
        if (spec.fixedItems.isNotEmpty()) {
            item(key = "fixed") {
                SegmentedColumn(title = stringResource(R.string.automation_section_scenarios)) {
                    spec.fixedItems.forEach { (id, titleRes) ->
                        val scope = AutomationScope.item(id)
                        item(key = id) {
                            val stored = snapshot[scope]
                            val rules = snapshot.effectiveRules(scope)
                            val on = itemSwitch?.let { rules[it].enabled } ?: true
                            val chips = itemChips(spec, rules)
                            BaseWidget(
                                title = stringResource(titleRes),
                                description = if (chips.isEmpty()) stringResource(R.string.automation_item_no_extras) else null,
                                descriptionContent = if (chips.isNotEmpty()) ({ AutomationMiniChips(chips) }) else null,
                                onClick = { onOpenScope(scope, null, 0) },
                                trailingDivider = itemSwitch != null,
                                trailingContent = { if (itemSwitch != null) AutomationSwitch(on, { setItemSwitch(stored, it) }) },
                            )
                        }
                    }
                }
            }
        }
        spec.itemsTitleRes?.let { titleRes ->
            item(key = "items") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                    if (order.isEmpty()) {
                        BaseWidget(title = stringResource(R.string.automation_no_rules), description = stringResource(R.string.automation_no_rules_summary))
                    }
                    val reorder = rememberAutomationReorderState { from, to ->
                        val moved = order.toMutableList().apply { add(to, removeAt(from)) }
                        order = moved
                        activity.persist(coroutineScope, "reorder ${spec.id}") {
                            spec.store.reorder(AutomationScopeType.ITEM, moved.map { it.scope.scopeId })
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        order.forEachIndexed { index, stored ->
                            val rules = snapshot.effectiveRules(stored.scope)
                            val on = itemSwitch?.let { rules[it].enabled } ?: true
                            val chips = itemChips(spec, rules)
                            Box(Modifier.reorderableItem(reorder, index)) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalSegmentedItemShape provides segmentShape(index, order.size)) {
                                    BaseWidget(
                                        title = stored.name ?: stored.scope.scopeId,
                                        headlineTrailingContent = {
                                            if (!on) AutomationTag(stringResource(R.string.automation_item_disabled), Modifier.padding(start = 8.dp), AutomationTagTone.Muted)
                                        },
                                        description = if (chips.isEmpty()) stringResource(R.string.automation_item_no_extras) else null,
                                        descriptionContent = if (chips.isNotEmpty()) ({ AutomationMiniChips(chips) }) else null,
                                        leadingContent = {
                                            Icon(
                                                MaterialSymbols.Outlined.Drag_indicator,
                                                contentDescription = stringResource(R.string.automation_drag_to_reorder),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.reorderHandle(reorder, index),
                                            )
                                        },
                                        onClick = { onOpenScope(stored.scope, null, 0) },
                                        trailingDivider = itemSwitch != null,
                                        trailingContent = { if (itemSwitch != null) AutomationSwitch(on, { setItemSwitch(stored, it) }) },
                                    )
                                }
                            }
                        }
                    }
                    if (order.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.automation_items_order_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        )
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET + 72.dp)) }
    }

    sheet?.let { (editor, value) ->
        @Suppress("UNCHECKED_CAST")
        AutomationRuleSheet(
            spec, editor as AutomationRuleEditor<Any>, value, AutomationScope.Global, globalRules, context,
            onDismiss = { sheet = null },
            onDone = {
                sheet = null
                saveGlobal(editor.key, it)
            },
        )
    }
}

/** Corner shape of item [index] of [count] in a segmented group laid out by hand. */
private fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val outer = dev.ujhhgtg.wekit.ui.content.m3.CornerRadius
    val inner = dev.ujhhgtg.wekit.ui.content.m3.ConnectionRadius
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

// ---- scope editor ------------------------------------------------------------------------------

@Composable
fun AutomationScopeScreen(
    spec: AutomationSpec,
    draft: AutomationScopeDraft,
    context: AutomationEditorContext,
    onBack: () -> Unit,
    onOpenMembers: (String) -> Unit,
    onOpenReply: (AutomationRuleKey<AutomationReplyRule>) -> Unit,
) {
    val activity = context.activity
    val snapshot = rememberAutomationSnapshot(spec)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val fabVisible = rememberFabVisible(listState)
    val snackbar = remember { SnackbarHostState() }
    val scope = draft.scope
    val batch = draft.batch
    val isNew = batch == null && scope !in snapshot.scopes
    val plain = batch == null && (scope.type == AutomationScopeType.GLOBAL || scope.type == AutomationScopeType.ITEM)
    val fixed = scope.type == AutomationScopeType.ITEM && spec.fixedItems.any { it.first == scope.scopeId }
    val deletable = batch == null && !isNew && scope.type != AutomationScopeType.GLOBAL && scope.type != AutomationScopeType.CATEGORY && !fixed

    val parentRules = snapshot.parentRules(scope)
    val presetRules = draft.presets.orEmpty().fold(parentRules) { acc, id -> acc.overlay(snapshot[AutomationScope.preset(id)].values) }
    val effective = presetRules.overlay(draft.values)
    val sources = remember(snapshot, scope, draft.presets) { snapshot.inheritedSources(scope, draft.presets.orEmpty()) }
    val parent = snapshot.ancestors(scope).lastOrNull()
    val itemSwitch = spec.itemSwitch
    val editors = spec.sections.flatMap { it.keys }.mapNotNull { key -> spec.editors.firstOrNull { it.key == key } }
        .filter { it.visibleIn(scope) && it.key != itemSwitch }
    val errors = editors.filter { plain || it.key.id in draft.values.keyIds }.mapNotNull { editor ->
        spec.ruleError(effective, editor.key)?.let { editor.key.id to it }
    }.toMap()
    var showErrors by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Pair<AutomationRuleEditor<*>, Any>?>(null) }
    var pickingPresets by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val dirty = draft.modified || isNew
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(1200)
            highlighted = null
        }
    }

    val guardedBack = rememberCreationBackGuard(
        guardActive = draft.modified,
        onBack = onBack,
        dialogTitle = stringResource(R.string.python_discard_changes_title),
        dialogMessage = stringResource(R.string.automation_discard_changes_message),
        confirmLabel = stringResource(R.string.python_discard_changes_confirm),
    )

    fun open(editor: AutomationRuleEditor<*>, value: Any) {
        if (editor.opensReplyPage) {
            draft.openedValues[editor.key.id] = value
            @Suppress("UNCHECKED_CAST")
            onOpenReply(editor.key as AutomationRuleKey<AutomationReplyRule>)
        } else {
            sheet = editor to value
        }
    }

    val actions = AutomationRuleActions(
        onChange = { key, value -> draft.setAny(key, value) },
        onOpen = ::open,
        onOverride = { editor ->
            @Suppress("UNCHECKED_CAST")
            val value = presetRules.getAny(editor as AutomationRuleEditor<Any>)
            draft.setAny(editor.key, value)
            highlighted = editor.key.id
            if (editor.opensEditor) open(editor, value)
        },
        onRevert = { key ->
            val previous = draft.values
            draft.values = draft.values.without(key)
            coroutineScope.launch {
                val result = snackbar.showSnackbar(
                    message = activity.getString(R.string.automation_reverted, activity.getString(spec.editors.first { it.key == key }.titleRes)),
                    actionLabel = activity.getString(R.string.automation_undo),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) draft.values = previous
            }
        },
    )

    fun save() {
        if (errors.isNotEmpty()) {
            showErrors = true
            coroutineScope.launch {
                val first = spec.editors.first { it.key.id == errors.keys.first() }
                snackbar.showSnackbar(activity.resources.getQuantityString(R.plurals.automation_errors_to_fix, errors.size, errors.size, activity.getString(first.titleRes)))
            }
            return
        }
        saving = true
        activity.persist(coroutineScope, "save ${spec.id} $scope") {
            try {
                if (batch != null) {
                    val current = spec.store.snapshot()
                    spec.store.saveAll(batch.map { target ->
                        val stored = current[target]
                        stored.copy(values = AutomationRuleValues(stored.values.values + draft.values.values), presets = draft.presets ?: stored.presets)
                    })
                } else {
                    spec.store.save(draft.stored().copy(name = draft.name?.trim()))
                }
                showToast(activity, activity.getString(R.string.settings_saved))
                onBack()
            } finally {
                saving = false
            }
        }
    }

    val title = when {
        batch != null -> pluralStringResource(R.plurals.automation_batch_title, batch.size, batch.size)
        else -> automationScopeTitle(spec, scope, draft.name)
    }
    SettingsScaffold(
        title = title,
        onBack = guardedBack,
        listState = listState,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (dirty && !saving) {
                AutomationFab(
                    visible = fabVisible,
                    text = if (batch != null) pluralStringResource(R.plurals.automation_apply_to_chats, batch.size, batch.size) else stringResource(R.string.action_save),
                    icon = MaterialSymbols.Outlined.Save,
                    attention = errors.isEmpty(),
                    onClick = ::save,
                )
            }
        },
    ) {
        item(key = "header") { ScopeHeader(spec, snapshot, scope, batch) }

        // name + item switch
        val named = scope.type == AutomationScopeType.PRESET || (scope.type == AutomationScopeType.ITEM && !fixed)
        if (batch == null && (named || (scope.type == AutomationScopeType.ITEM && itemSwitch != null))) {
            item(key = "basics") {
                SegmentedColumn {
                    if (named) {
                        item(key = "name") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.automation_name),
                                value = draft.name.orEmpty(),
                                onValueChange = { if (it.isNotBlank()) draft.name = it.trim() },
                                dialogTitle = stringResource(R.string.automation_name),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                            )
                        }
                    }
                    if (scope.type == AutomationScopeType.ITEM && itemSwitch != null) {
                        @Suppress("UNCHECKED_CAST")
                        val editor = spec.editors.first { it.key == itemSwitch } as AutomationRuleEditor<AutomationToggleRule>
                        automationRuleItem(spec, editor, effective[itemSwitch], AutomationRuleMode.PLAIN, scope, effective, context, actions)
                    }
                }
            }
        }

        // presets + members
        val offersPresets = AutomationScopeType.PRESET in spec.scopeTypes && (batch != null || scope.type in setOf(
            AutomationScopeType.CATEGORY, AutomationScopeType.CONTACT, AutomationScopeType.GROUP_MEMBER,
        ))
        val offersMembers = batch == null && scope.type == AutomationScopeType.CONTACT &&
                AutomationScopeType.GROUP_MEMBER in spec.scopeTypes &&
                AutomationTalkerCategory.of(scope.scopeId) == AutomationTalkerCategory.GROUP
        if (offersPresets || offersMembers) {
            item(key = "links") {
                SegmentedColumn {
                    if (offersPresets) {
                        item(key = "presets") {
                            val presets = draft.presets
                            val available = snapshot.ofType(AutomationScopeType.PRESET)
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Bookmarks,
                                title = stringResource(R.string.automation_apply_presets),
                                description = when {
                                    presets == null -> stringResource(R.string.automation_batch_presets_unchanged)
                                    presets.isEmpty() -> stringResource(if (available.isEmpty()) R.string.automation_no_presets else R.string.automation_none_selected)
                                    else -> null
                                },
                                descriptionContent = if (!presets.isNullOrEmpty()) {
                                    {
                                        Row(Modifier.padding(top = 4.dp)) {
                                            presets.forEachIndexed { index, id ->
                                                AutomationTag(
                                                    "${index + 1} · ${snapshot[AutomationScope.preset(id)].name ?: id}",
                                                    if (index == 0) Modifier else Modifier.padding(start = 6.dp),
                                                    AutomationTagTone.Tertiary,
                                                )
                                            }
                                        }
                                    }
                                } else null,
                                enabled = available.isNotEmpty(),
                                onClick = { pickingPresets = true },
                                trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                            )
                        }
                    }
                    if (offersMembers) {
                        item(key = "members") {
                            val count = snapshot.membersOf(scope.scopeId).size
                            BaseWidget(
                                icon = MaterialSymbols.Outlined.Groups,
                                title = stringResource(R.string.automation_group_member_settings),
                                description = if (count == 0) stringResource(R.string.automation_all_members_follow_group)
                                else pluralStringResource(R.plurals.automation_configured_members, count, count),
                                onClick = { onOpenMembers(scope.scopeId) },
                                trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                            )
                        }
                    }
                }
            }
        }

        if (plain) {
            spec.sections.forEachIndexed { index, section ->
                val sectionEditors = section.keys.mapNotNull { key -> editors.firstOrNull { it.key == key } }
                if (sectionEditors.isEmpty()) return@forEachIndexed
                item(key = "section_$index") {
                    SegmentedColumn(title = section.titleRes?.let { stringResource(it) }.orEmpty()) {
                        sectionEditors.forEach { editor ->
                            @Suppress("UNCHECKED_CAST")
                            val typed = editor as AutomationRuleEditor<Any>
                            automationRuleItem(
                                spec, typed, effective.getAny(typed), AutomationRuleMode.PLAIN, scope, effective, context, actions,
                                error = errors[editor.key.id]?.takeIf { showErrors || typed.isOn?.invoke(effective.getAny(typed)) != false },
                                highlighted = highlighted == editor.key.id,
                            )
                        }
                    }
                }
            }
        } else {
            val own = editors.filter { it.key.id in draft.values.keyIds }
            val rest = editors.filter { it.key.id !in draft.values.keyIds }
            item(key = "own") {
                SegmentedColumn(title = stringResource(ownSectionTitle(scope, batch != null))) {
                    if (own.isEmpty()) {
                        item(key = "own_empty") {
                            BaseWidget(
                                title = stringResource(R.string.automation_no_overrides),
                                description = stringResource(
                                    when {
                                        batch != null -> R.string.automation_no_overrides_batch
                                        scope.type == AutomationScopeType.PRESET -> R.string.automation_no_overrides_preset
                                        else -> R.string.automation_no_overrides_summary
                                    }
                                ),
                            )
                        }
                    }
                    own.forEach { editor ->
                        @Suppress("UNCHECKED_CAST")
                        val typed = editor as AutomationRuleEditor<Any>
                        automationRuleItem(
                            spec, typed, effective.getAny(typed), AutomationRuleMode.OWN, scope, effective, context, actions,
                            error = errors[editor.key.id],
                            highlighted = highlighted == editor.key.id,
                        )
                    }
                }
                if (own.isNotEmpty() && batch == null) {
                    Text(
                        text = stringResource(R.string.automation_revert_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            item(key = "inherited") {
                val parentTitle = parent?.let { automationScopeTitle(spec, it, snapshot[it].name) }
                SegmentedColumn(
                    title = when {
                        batch != null -> stringResource(R.string.automation_section_unchanged)
                        scope.type == AutomationScopeType.PRESET -> stringResource(R.string.automation_section_not_in_preset)
                        else -> stringResource(R.string.automation_section_inherited, parentTitle!!)
                    },
                ) {
                    rest.forEach { editor ->
                        @Suppress("UNCHECKED_CAST")
                        val typed = editor as AutomationRuleEditor<Any>
                        val sourceScope = sources[editor.key.id]
                        val sourceTitle: (@Composable () -> String)? =
                            if (batch == null && sourceScope != null && sourceScope != parent) ({ sourceTitle(spec, snapshot, sourceScope) }) else null
                        if (batch != null) {
                            item(key = editor.key.id) {
                                BaseWidget(
                                    title = stringResource(editor.titleFor(scope)),
                                    description = stringResource(R.string.automation_batch_keep),
                                    contentAlpha = 0.62f,
                                    onClick = { actions.onOverride(editor) },
                                    trailingContent = { AutomationOverrideMarker() },
                                )
                            }
                        } else {
                            automationRuleItem(
                                spec, typed, presetRules.getAny(typed), AutomationRuleMode.INHERITED, scope, presetRules, context, actions,
                                source = sourceTitle,
                            )
                        }
                    }
                }
            }
        }

        if (deletable) {
            item(key = "delete") {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(MaterialSymbols.Outlined.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(
                            when (scope.type) {
                                AutomationScopeType.ITEM -> R.string.automation_delete_rule
                                AutomationScopeType.PRESET -> R.string.automation_delete_preset
                                else -> R.string.automation_delete_chat_settings
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET + 72.dp)) }
    }

    sheet?.let { (editor, value) ->
        @Suppress("UNCHECKED_CAST")
        AutomationRuleSheet(
            spec, editor as AutomationRuleEditor<Any>, value, scope, presetRules, context,
            onDismiss = { sheet = null },
            onDone = {
                sheet = null
                draft.setAny(editor.key, it)
                highlighted = editor.key.id
            },
        )
    }
    if (pickingPresets) {
        PresetSheet(
            presets = snapshot.ofType(AutomationScopeType.PRESET),
            initial = draft.presets.orEmpty(),
            batch = batch != null,
            spec = spec,
            onDismiss = { pickingPresets = false },
            onConfirm = {
                draft.presets = it
                pickingPresets = false
            },
        )
    }
    SettingsConfirmDialog(
        show = confirmDelete,
        title = stringResource(
            when (scope.type) {
                AutomationScopeType.ITEM -> R.string.automation_delete_rule_title
                AutomationScopeType.PRESET -> R.string.automation_delete_preset_title
                else -> R.string.automation_delete_scope_title
            }
        ),
        message = stringResource(
            when (scope.type) {
                AutomationScopeType.ITEM -> R.string.automation_delete_rule_message
                AutomationScopeType.PRESET -> R.string.automation_delete_preset_message
                else -> R.string.automation_delete_scope_message
            }
        ),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            confirmDelete = false
            activity.persist(coroutineScope, "delete ${spec.id} $scope") {
                spec.store.delete(scope)
                if (scope.type == AutomationScopeType.PRESET) {
                    val users = spec.store.snapshot().scopes.values.filter { scope.scopeId in it.presets }
                    if (users.isNotEmpty()) spec.store.saveAll(users.map { it.copy(presets = it.presets - scope.scopeId) })
                }
                onBack()
            }
        },
        onDismiss = { confirmDelete = false },
    )
}

private fun ownSectionTitle(scope: AutomationScope, batch: Boolean): Int = when {
    batch -> R.string.automation_section_batch_own
    scope.type == AutomationScopeType.CATEGORY -> R.string.automation_section_category_own
    scope.type == AutomationScopeType.GROUP_MEMBER -> R.string.automation_section_member_own
    scope.type == AutomationScopeType.PRESET -> R.string.automation_section_preset_own
    else -> R.string.automation_section_chat_own
}

@Composable
private fun sourceTitle(spec: AutomationSpec, snapshot: AutomationSnapshot, source: AutomationScope): String =
    if (source.type == AutomationScopeType.PRESET) stringResource(R.string.automation_preset_named, snapshot[source].name ?: source.scopeId)
    else automationScopeTitle(spec, source, snapshot[source].name)

/** The chat / member card with its inheritance chain, the batch selection, or a one-line summary. */
@Composable
private fun ScopeHeader(spec: AutomationSpec, snapshot: AutomationSnapshot, scope: AutomationScope, batch: List<AutomationScope>?) {
    val summary: String? = when {
        batch != null -> null
        scope.type == AutomationScopeType.GLOBAL -> stringResource(spec.globalSummaryRes)
        scope.type == AutomationScopeType.CATEGORY -> stringResource(R.string.automation_category_summary)
        scope.type == AutomationScopeType.PRESET -> {
            val used = snapshot.scopes.values.count { scope.scopeId in it.presets }
            if (used == 0) stringResource(R.string.automation_preset_unused) else pluralStringResource(R.plurals.automation_preset_used_by, used, used)
        }
        else -> null
    }
    if (summary != null) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )
        return
    }
    if (batch == null && scope.type != AutomationScopeType.CONTACT && scope.type != AutomationScopeType.GROUP_MEMBER) return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (batch != null) {
                Row {
                    batch.take(4).forEachIndexed { index, target ->
                        Box(Modifier.offset(x = (-10 * index).dp)) { AutomationAvatar(target.scopeId, 40.dp) }
                    }
                }
                Text(
                    text = batch.map { rememberContactName(it.scopeId) }.joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                AutomationScopeAvatar(scope, 56.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val chain = snapshot.ancestors(scope).map { automationScopeTitle(spec, it, snapshot[it].name) } +
                            stringResource(if (scope.type == AutomationScopeType.GROUP_MEMBER) R.string.automation_chain_this_member else R.string.automation_chain_this_chat)
                    Text(
                        text = chain.joinToString("  ›  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (scope.type == AutomationScopeType.GROUP_MEMBER) scope.memberId else scope.scopeId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetSheet(
    presets: List<AutomationStoredScope>,
    initial: List<String>,
    batch: Boolean,
    spec: AutomationSpec,
    onDismiss: () -> Unit,
    onConfirm: (List<String>?) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    AutomationSheet(
        title = stringResource(R.string.automation_apply_presets),
        subtitle = stringResource(R.string.automation_presets_order_hint),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
        footerStart = { finish ->
            if (batch) TextButton(onClick = { finish { onConfirm(null) } }) { Text(stringResource(R.string.automation_keep_unchanged)) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            presets.forEachIndexed { index, preset ->
                val id = preset.scope.scopeId
                val position = selected.indexOf(id)
                androidx.compose.runtime.CompositionLocalProvider(LocalSegmentedItemShape provides segmentShape(index, presets.size)) {
                    BaseWidget(
                        title = preset.name ?: id,
                        description = overriddenRuleNames(spec, preset) ?: stringResource(R.string.automation_no_overrides),
                        leadingContent = {
                            if (position >= 0) {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${position + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                Checkbox(checked = false, onCheckedChange = null)
                            }
                        },
                        onClick = { selected = if (position >= 0) selected - id else selected + id },
                    )
                }
            }
        }
    }
}

// ---- members -------------------------------------------------------------------------------------

@Composable
fun AutomationMembersScreen(
    spec: AutomationSpec,
    groupId: String,
    context: AutomationEditorContext,
    onBack: () -> Unit,
    onOpenScope: (AutomationScope) -> Unit,
) {
    val snapshot = rememberAutomationSnapshot(spec)
    val listState = rememberLazyListState()
    val fabVisible = rememberFabVisible(listState)
    val configured = snapshot.membersOf(groupId)
    val groupName = rememberContactName(groupId)
    val title = stringResource(R.string.automation_group_member_settings_title, groupName)
    SettingsScaffold(
        title = title,
        onBack = onBack,
        listState = listState,
        floatingActionButton = {
            AutomationFab(fabVisible, stringResource(R.string.automation_add_member), MaterialSymbols.Outlined.Person_add) {
                context.picker.open(
                    AutomationPick.Contact(
                        title = title,
                        loadContacts = { loadGroupMembers(groupId) },
                        isMarked = { m -> AutomationScope.member(groupId, m.wxId) in snapshot.scopes },
                        onPick = { member -> onOpenScope(AutomationScope.member(groupId, member.wxId)) },
                    )
                )
            }
        },
    ) {
        item(key = "hint") {
            Text(
                text = stringResource(R.string.automation_members_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
        item(key = "members") {
            SegmentedColumn(title = stringResource(R.string.automation_configured)) {
                if (configured.isEmpty()) {
                    item(key = "empty") { BaseWidget(title = stringResource(R.string.automation_all_members_follow_group)) }
                }
                configured.forEach { stored ->
                    item(key = stored.scope.memberId) {
                        val count = ownKeys(spec, stored).size
                        BaseWidget(
                            title = rememberMemberName(groupId, stored.scope.memberId),
                            description = overriddenRuleNames(spec, stored) ?: stringResource(R.string.automation_follow_parent_settings),
                            headlineTrailingContent = {
                                if (count > 0) AutomationTag(pluralStringResource(R.plurals.automation_rules_set, count, count), Modifier.padding(start = 8.dp))
                                PresetTags(spec, snapshot, stored.presets)
                            },
                            leadingContent = { AutomationAvatar(stored.scope.memberId) },
                            onClick = { onOpenScope(stored.scope) },
                            trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null) },
                        )
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET + 72.dp)) }
    }
}
