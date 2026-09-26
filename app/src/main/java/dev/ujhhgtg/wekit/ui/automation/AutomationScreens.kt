package dev.ujhhgtg.wekit.ui.automation

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.automation.AutomationRoute
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRuleValues
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSnapshot
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationStoredScope
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SettingsConfirmDialog
import dev.ujhhgtg.wekit.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3.rememberCreationBackGuard
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.io.path.div
import kotlin.io.path.outputStream

private const val TAG = "AutomationScreens"

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

@Composable
fun automationScopeTitle(spec: AutomationSpec, stored: AutomationStoredScope): String {
    val scope = stored.scope
    return when (scope.type) {
        AutomationScopeType.GLOBAL -> stringResource(R.string.automation_global_settings)
        AutomationScopeType.CATEGORY -> stringResource(categoryDefaultLabel(scope.category!!))
        AutomationScopeType.CONTACT -> remember(scope) { WeDatabaseApi.getDisplayName(scope.scopeId) }
        AutomationScopeType.GROUP_MEMBER -> remember(scope) {
            WeDatabaseApi.getGroupMemberDisplayName(scope.scopeId, scope.memberId).ifBlank { scope.memberId }
        }
        AutomationScopeType.PRESET -> stored.name ?: scope.scopeId
        AutomationScopeType.ITEM -> spec.fixedItems.firstOrNull { it.first == scope.scopeId }
            ?.let { stringResource(it.second) } ?: stored.name ?: scope.scopeId
    }
}

fun categoryDefaultLabel(category: AutomationTalkerCategory): Int = when (category) {
    AutomationTalkerCategory.PRIVATE -> R.string.automation_category_private_default
    AutomationTalkerCategory.GROUP -> R.string.automation_category_group_default
    AutomationTalkerCategory.OFFICIAL -> R.string.automation_category_official_default
}

@Composable
private fun scopeSummary(spec: AutomationSpec, stored: AutomationStoredScope): String {
    val count = ruleCount(spec, stored)
    val presets = stored.presets.size
    return when {
        count == 0 && presets == 0 -> stringResource(R.string.automation_follow_parent_settings)
        presets == 0 -> pluralStringResource(R.plurals.automation_overrides, count, count)
        else -> pluralStringResource(R.plurals.automation_overrides, count, count) + " · " +
                pluralStringResource(R.plurals.automation_preset_count, presets, presets)
    }
}

/** Stored rules shown as overrides, not counting the [AutomationSpec.scopeSwitch]. */
private fun ruleCount(spec: AutomationSpec, stored: AutomationStoredScope): Int =
    stored.values.size - if (spec.scopeSwitch?.let { it in stored.values } == true) 1 else 0

/**
 * A configured chat, member or preset row. When [spec] has a [AutomationSpec.scopeSwitch], the
 * trailing switch turns the scope off without touching its rules.
 */
@Composable
private fun ConfiguredScopeRow(
    spec: AutomationSpec,
    stored: AutomationStoredScope,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val key = spec.scopeSwitch
    if (key == null) {
        AutomationNavigationRow(title = title, description = description, onClick = onClick)
        return
    }
    val activity = LocalActivity.current as ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    val failedText = stringResource(R.string.structured_storage_save_failed)
    SwitchWidget(
        title = title,
        description = description,
        onClick = onClick,
        trailingDivider = true,
        checked = stored.values[key]?.enabled != false,
        onCheckedChange = { on ->
            coroutineScope.launch {
                try {
                    val values = if (on) stored.values.without(key) else stored.values.with(key, AutomationToggleRule(false))
                    spec.store.save(stored.copy(values = values))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WeLogger.e(TAG, "failed to switch ${spec.id} ${stored.scope}", e)
                    showToast(activity, failedText)
                }
            }
        },
    )
}

// ---- home ------------------------------------------------------------------------------------

@Composable
fun AutomationHomeScreen(spec: AutomationSpec, onBack: () -> Unit, onOpen: (AutomationRoute) -> Unit) {
    val snapshot = rememberAutomationSnapshot(spec)
    SettingsScaffold(title = stringResource(spec.titleRes), onBack = onBack) {
        if (AutomationScopeType.GLOBAL in spec.scopeTypes) item {
            SegmentedColumn(title = stringResource(R.string.automation_section_rules)) {
                item(key = "global") {
                    AutomationNavigationRow(
                        title = stringResource(R.string.automation_global_settings),
                        description = stringResource(spec.globalSummaryRes),
                        onClick = { onOpen(AutomationRoute.Scope(AutomationScope.Global)) },
                    )
                }
                if (AutomationScopeType.CATEGORY in spec.scopeTypes) {
                    spec.categories.forEach { category ->
                        val scope = AutomationScope.category(category)
                        item(key = "category_$category") {
                            AutomationNavigationRow(
                                title = stringResource(categoryDefaultLabel(category)),
                                description = scopeSummary(spec, snapshot[scope]),
                                onClick = { onOpen(AutomationRoute.Scope(scope)) },
                            )
                        }
                    }
                }
                if (AutomationScopeType.CONTACT in spec.scopeTypes) {
                    item(key = "contacts") {
                        val count = snapshot.ofType(AutomationScopeType.CONTACT).size
                        AutomationNavigationRow(
                            title = stringResource(R.string.automation_contact_settings),
                            description = if (count == 0) stringResource(R.string.automation_contact_settings_summary)
                            else pluralStringResource(R.plurals.automation_configured_contacts, count, count),
                            onClick = { onOpen(AutomationRoute.Contacts) },
                        )
                    }
                }
                if (AutomationScopeType.PRESET in spec.scopeTypes) {
                    item(key = "presets") {
                        val count = snapshot.ofType(AutomationScopeType.PRESET).size
                        AutomationNavigationRow(
                            title = stringResource(R.string.automation_presets),
                            description = if (count == 0) stringResource(R.string.automation_presets_summary)
                            else pluralStringResource(R.plurals.automation_preset_count, count, count),
                            onClick = { onOpen(AutomationRoute.Presets) },
                        )
                    }
                }
            }
        }
        if (spec.fixedItems.isNotEmpty() || spec.itemsTitleRes != null) {
            item {
                SegmentedColumn {
                    spec.fixedItems.forEach { (id, titleRes) ->
                        item(key = "fixed_$id") {
                            AutomationNavigationRow(
                                title = stringResource(titleRes),
                                description = scopeSummaryForItem(spec, snapshot, id),
                                onClick = { onOpen(AutomationRoute.Scope(AutomationScope.item(id))) },
                            )
                        }
                    }
                    spec.itemsTitleRes?.let { titleRes ->
                        item(key = "items") {
                            val count = snapshot.ofType(AutomationScopeType.ITEM).size
                            AutomationNavigationRow(
                                title = stringResource(titleRes),
                                description = pluralStringResource(R.plurals.automation_item_count, count, count),
                                onClick = { onOpen(AutomationRoute.Items) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun scopeSummaryForItem(spec: AutomationSpec, snapshot: AutomationSnapshot, id: String): String {
    val enabledKey = spec.keys.firstOrNull { it.id == "enabled" }
    val enabled = enabledKey?.let {
        (snapshot.effectiveRules(AutomationScope.item(id))[it] as? dev.ujhhgtg.wekit.features.automation.AutomationToggleRule)?.enabled
    }
    return stringResource(if (enabled == true) R.string.automation_item_enabled else R.string.automation_item_disabled)
}

// ---- scope editor -----------------------------------------------------------------------------

@Composable
fun AutomationScopeScreen(
    spec: AutomationSpec,
    scope: AutomationScope,
    onBack: () -> Unit,
    onOpen: (AutomationRoute) -> Unit,
) {
    val activity = LocalActivity.current as ComponentActivity
    val snapshot = rememberAutomationSnapshot(spec)
    val coroutineScope = rememberCoroutineScope()
    val initial = remember(scope) { spec.store.snapshot()[scope] }
    var draft by remember(scope) { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf<AutomationTextEditRequest?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pickFiles = rememberAutomationFilePicker()

    val plain = scope.type == AutomationScopeType.GLOBAL || scope.type == AutomationScopeType.ITEM
    val parentRules = snapshot.parentRules(scope)
    val presetRules = draft.presets.fold(parentRules) { acc, id -> acc.overlay(snapshot[AutomationScope.preset(id)].values) }
    val effective = presetRules.overlay(draft.values)
    val validationError = spec.validate(effective, if (plain) null else draft.values.keyIds)
    val modified = draft != initial
    val savedText = stringResource(R.string.settings_saved)
    val failedText = stringResource(R.string.structured_storage_save_failed)
    val guardedBack = rememberCreationBackGuard(
        guardActive = modified,
        onBack = onBack,
        dialogTitle = stringResource(R.string.python_discard_changes_title),
        dialogMessage = stringResource(R.string.automation_discard_changes_message),
        confirmLabel = stringResource(R.string.python_discard_changes_confirm),
    )
    val deletable = scope.type != AutomationScopeType.GLOBAL && scope.type != AutomationScopeType.CATEGORY &&
            !(scope.type == AutomationScopeType.ITEM && spec.fixedItems.any { it.first == scope.scopeId })

    fun save() {
        saving = true
        coroutineScope.launch {
            try {
                spec.store.save(draft)
                showToast(activity, savedText)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to save ${spec.id} $scope", e)
                showToast(activity, failedText)
            } finally {
                saving = false
            }
        }
    }

    val context = AutomationEditorContext(activity, scope, { editText = it }, pickFiles, LocalAutomationContactPicker.current)
    val parentLabel = parentLabel(spec, scope)

    SettingsScaffold(
        title = automationScopeTitle(spec, draft),
        onBack = guardedBack,
        actions = {
            if (deletable) {
                IconButton(enabled = !saving, onClick = { confirmDelete = true }) {
                    Icon(MaterialSymbols.Outlined.Delete, stringResource(R.string.action_delete))
                }
            }
            IconButton(enabled = !saving && validationError == null && (modified || initial.isBlank), onClick = ::save) {
                Icon(MaterialSymbols.Outlined.Check, stringResource(R.string.action_save))
            }
        },
    ) {
        if (scope.type == AutomationScopeType.PRESET || (scope.type == AutomationScopeType.ITEM && deletable)) {
            item {
                SegmentedColumn {
                    item(key = "name") {
                        val title = stringResource(R.string.automation_name)
                        AutomationValueRow(
                            title = title,
                            value = draft.name.orEmpty(),
                            onClick = {
                                editText = AutomationTextEditRequest(
                                    title = title,
                                    initial = draft.name.orEmpty(),
                                    validator = { if (it.isBlank()) activity.getString(R.string.automation_name_required) else null },
                                    onCommit = { draft = draft.copy(name = it.trim()) },
                                )
                            },
                        )
                    }
                }
            }
        }
        val offersPresets = AutomationScopeType.PRESET in spec.scopeTypes && scope.type in setOf(
            AutomationScopeType.CATEGORY, AutomationScopeType.CONTACT, AutomationScopeType.GROUP_MEMBER,
        )
        val offersMembers = scope.type == AutomationScopeType.CONTACT &&
                AutomationScopeType.GROUP_MEMBER in spec.scopeTypes &&
                AutomationTalkerCategory.of(scope.scopeId) == AutomationTalkerCategory.GROUP
        if (offersPresets || offersMembers) {
            item {
                SegmentedColumn {
                    if (offersPresets) {
                        item(key = "presets") {
                            val presets = snapshot.ofType(AutomationScopeType.PRESET)
                            val title = stringResource(R.string.automation_apply_presets)
                            AutomationNavigationRow(
                                title = title,
                                description = if (draft.presets.isEmpty()) stringResource(R.string.automation_none_selected)
                                else draft.presets.joinToString(", ") { id -> presets.firstOrNull { it.scope.scopeId == id }?.name ?: id },
                                enabled = presets.isNotEmpty(),
                                onClick = {
                                    showComposeDialog(activity) {
                                        PresetPicker(title, presets, draft.presets, onDismiss) {
                                            draft = draft.copy(presets = it)
                                            onDismiss()
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (offersMembers) {
                        item(key = "members") {
                            val count = snapshot.membersOf(scope.scopeId).size
                            AutomationNavigationRow(
                                title = stringResource(R.string.automation_group_member_settings),
                                description = if (count == 0) stringResource(R.string.automation_all_members_follow_group)
                                else pluralStringResource(R.plurals.automation_configured_members, count, count),
                                onClick = { onOpen(AutomationRoute.Members(scope.scopeId)) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SegmentedColumn {
                automationRuleItems(
                    editors = spec.editors,
                    rules = effective,
                    overridden = if (plain) null else draft.values.keyIds,
                    parentLabel = parentLabel,
                    context = context,
                    onActivate = { key -> draft = draft.copy(values = activate(key, presetRules, draft.values)) },
                    onReset = { key -> draft = draft.copy(values = draft.values.without(key)) },
                    onChange = { key, value -> draft = draft.copy(values = withValue(key, value, draft.values)) },
                )
                if (validationError != null) {
                    item(key = "validation_error") { AutomationErrorRow(validationError) }
                }
            }
        }
    }

    editText?.let { AutomationTextEditDialog(it, onClose = { editText = null }) }
    SettingsConfirmDialog(
        show = confirmDelete,
        title = stringResource(R.string.automation_delete_scope_title),
        message = stringResource(R.string.automation_delete_scope_message),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        loading = saving,
        onConfirm = {
            saving = true
            coroutineScope.launch {
                try {
                    spec.store.delete(scope)
                    confirmDelete = false
                    onBack()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WeLogger.e(TAG, "failed to delete ${spec.id} $scope", e)
                    showToast(activity, failedText)
                } finally {
                    saving = false
                }
            }
        },
        onDismiss = { confirmDelete = false },
    )
}

@Suppress("UNCHECKED_CAST")
private fun activate(key: AutomationRuleKey<*>, rules: dev.ujhhgtg.wekit.features.automation.AutomationRules, values: AutomationRuleValues) =
    rules.copyInto(key as AutomationRuleKey<Any>, values)

@Suppress("UNCHECKED_CAST")
private fun withValue(key: AutomationRuleKey<*>, value: Any, values: AutomationRuleValues) =
    values.with(key as AutomationRuleKey<Any>, value)

@Composable
private fun parentLabel(spec: AutomationSpec, scope: AutomationScope): String = when (scope.type) {
    AutomationScopeType.CONTACT -> if (AutomationScopeType.CATEGORY in spec.scopeTypes) {
        stringResource(categoryDefaultLabel(AutomationTalkerCategory.of(scope.scopeId)))
    } else stringResource(R.string.automation_global_settings)
    AutomationScopeType.GROUP_MEMBER -> stringResource(R.string.automation_group_global_settings)
    else -> stringResource(R.string.automation_global_settings)
}

@Composable
private fun PresetPicker(
    title: String,
    presets: List<AutomationStoredScope>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    dev.ujhhgtg.wekit.ui.content.AlertDialogContent(
        title = { androidx.compose.material3.Text(title) },
        text = {
            SegmentedColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues()) {
                presets.forEach { preset ->
                    val id = preset.scope.scopeId
                    item(key = id) {
                        val position = selected.indexOf(id)
                        BaseWidget(
                            iconPlaceholder = false,
                            title = preset.name ?: id,
                            description = if (position >= 0) stringResource(R.string.automation_preset_order, position + 1) else null,
                            onClick = { selected = if (id in selected) selected - id else selected + id },
                            trailingContent = { Checkbox(checked = id in selected, onCheckedChange = null) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            dev.ujhhgtg.wekit.ui.content.TextButton(onClick = { onConfirm(selected) }) {
                androidx.compose.material3.Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            dev.ujhhgtg.wekit.ui.content.TextButton(onDismiss) {
                androidx.compose.material3.Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

// ---- contacts / members -----------------------------------------------------------------------

@Composable
fun AutomationContactsScreen(spec: AutomationSpec, onBack: () -> Unit, onOpen: (AutomationRoute) -> Unit) {
    val picker = LocalAutomationContactPicker.current
    val snapshot = rememberAutomationSnapshot(spec)
    val configured = snapshot.ofType(AutomationScopeType.CONTACT)
    val memberGroups = snapshot.scopes.keys.filter { it.type == AutomationScopeType.GROUP_MEMBER }
        .map { it.scopeId }.filter { id -> configured.none { it.scope.scopeId == id } }.distinct()
    val title = stringResource(R.string.automation_contact_settings)
    val batchTitle = stringResource(R.string.automation_batch_edit)

    SettingsScaffold(title = title, onBack = onBack) {
        item {
            SegmentedColumn {
                item(key = "add") {
                    AutomationValueRow(
                        title = stringResource(R.string.automation_add_contact),
                        value = "",
                        trailingIcon = MaterialSymbols.Outlined.Add,
                        onClick = {
                            picker.open(
                                AutomationContactPick.Single(
                                    title = title,
                                    loadContacts = { loadAutomationContacts(spec.contactKinds) },
                                    markedKey = snapshot,
                                    isMarked = { c -> snapshot.scopes.containsKey(AutomationScope.contact(c.wxId)) },
                                    onPick = { contact -> AutomationRoute.Scope(AutomationScope.contact(contact.wxId)) },
                                )
                            )
                        },
                    )
                }
                item(key = "batch") {
                    AutomationNavigationRow(
                        title = batchTitle,
                        description = stringResource(R.string.automation_batch_edit_summary),
                        onClick = {
                            picker.open(
                                AutomationContactPick.Multiple(
                                    title = batchTitle,
                                    loadContacts = { loadAutomationContacts(spec.contactKinds) },
                                    initialSelected = emptySet(),
                                    onConfirm = { ids ->
                                        if (ids.isEmpty()) null else AutomationRoute.Batch(ids.map(AutomationScope::contact))
                                    },
                                )
                            )
                        },
                    )
                }
            }
        }
        if (configured.isNotEmpty() || memberGroups.isNotEmpty()) {
            item {
                SegmentedColumn(title = stringResource(R.string.automation_configured)) {
                    configured.forEach { stored ->
                        item(key = stored.scope.scopeId) {
                            ConfiguredScopeRow(
                                spec = spec,
                                stored = stored,
                                title = automationScopeTitle(spec, stored),
                                description = scopeSummary(spec, stored),
                                onClick = { onOpen(AutomationRoute.Scope(stored.scope)) },
                            )
                        }
                    }
                    memberGroups.forEach { groupId ->
                        item(key = "members_$groupId") {
                            AutomationNavigationRow(
                                title = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) },
                                description = stringResource(R.string.automation_group_member_settings),
                                onClick = { onOpen(AutomationRoute.Scope(AutomationScope.contact(groupId))) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutomationMembersScreen(spec: AutomationSpec, groupId: String, onBack: () -> Unit, onOpen: (AutomationRoute) -> Unit) {
    val picker = LocalAutomationContactPicker.current
    val snapshot = rememberAutomationSnapshot(spec)
    val configured = snapshot.membersOf(groupId)
    val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
    val title = stringResource(R.string.automation_group_member_settings_title, groupName)
    val batchTitle = stringResource(R.string.automation_batch_edit)

    SettingsScaffold(title = title, onBack = onBack) {
        item {
            SegmentedColumn {
                item(key = "add") {
                    AutomationValueRow(
                        title = stringResource(R.string.automation_add_member),
                        value = "",
                        trailingIcon = MaterialSymbols.Outlined.Add,
                        onClick = {
                            picker.open(
                                AutomationContactPick.Single(
                                    title = title,
                                    loadContacts = { loadGroupMembers(groupId) },
                                    markedKey = snapshot,
                                    isMarked = { m -> snapshot.scopes.containsKey(AutomationScope.member(groupId, m.wxId)) },
                                    onPick = { member -> AutomationRoute.Scope(AutomationScope.member(groupId, member.wxId)) },
                                )
                            )
                        },
                    )
                }
                item(key = "batch") {
                    AutomationNavigationRow(
                        title = batchTitle,
                        description = stringResource(R.string.automation_batch_edit_summary),
                        onClick = {
                            picker.open(
                                AutomationContactPick.Multiple(
                                    title = batchTitle,
                                    loadContacts = { loadGroupMembers(groupId) },
                                    initialSelected = emptySet(),
                                    onConfirm = { ids ->
                                        if (ids.isEmpty()) null else AutomationRoute.Batch(ids.map { AutomationScope.member(groupId, it) })
                                    },
                                )
                            )
                        },
                    )
                }
            }
        }
        if (configured.isNotEmpty()) {
            item {
                SegmentedColumn(title = stringResource(R.string.automation_configured)) {
                    configured.forEach { stored ->
                        item(key = stored.scope.memberId) {
                            ConfiguredScopeRow(
                                spec = spec,
                                stored = stored,
                                title = automationScopeTitle(spec, stored),
                                description = scopeSummary(spec, stored),
                                onClick = { onOpen(AutomationRoute.Scope(stored.scope)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- presets / items --------------------------------------------------------------------------

@Composable
fun AutomationPresetsScreen(spec: AutomationSpec, onBack: () -> Unit, onOpen: (AutomationRoute) -> Unit) {
    NamedScopeListScreen(
        spec = spec,
        type = AutomationScopeType.PRESET,
        title = stringResource(R.string.automation_presets),
        addTitle = stringResource(R.string.automation_add_preset),
        defaultName = { stringResource(R.string.automation_new_preset_name, it) },
        reorderable = false,
        onBack = onBack,
        onOpen = onOpen,
    )
}

@Composable
fun AutomationItemsScreen(spec: AutomationSpec, onBack: () -> Unit, onOpen: (AutomationRoute) -> Unit) {
    NamedScopeListScreen(
        spec = spec,
        type = AutomationScopeType.ITEM,
        title = stringResource(spec.itemsTitleRes!!),
        addTitle = stringResource(R.string.automation_add_item),
        defaultName = { stringResource(spec.newItemNameRes, it) },
        reorderable = true,
        onBack = onBack,
        onOpen = onOpen,
    )
}

@Composable
private fun NamedScopeListScreen(
    spec: AutomationSpec,
    type: AutomationScopeType,
    title: String,
    addTitle: String,
    defaultName: @Composable (Int) -> String,
    reorderable: Boolean,
    onBack: () -> Unit,
    onOpen: (AutomationRoute) -> Unit,
) {
    val activity = LocalActivity.current as ComponentActivity
    val snapshot = rememberAutomationSnapshot(spec)
    val coroutineScope = rememberCoroutineScope()
    val entries = snapshot.ofType(type).filter { stored -> spec.fixedItems.none { it.first == stored.scope.scopeId } }
    val newName = defaultName(entries.size + 1)
    val failedText = stringResource(R.string.structured_storage_save_failed)

    fun persist(block: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to update ${spec.id} $type list", e)
                showToast(activity, failedText)
            }
        }
    }

    SettingsScaffold(title = title, onBack = onBack) {
        item {
            SegmentedColumn {
                item(key = "add") {
                    AutomationValueRow(
                        title = addTitle,
                        value = "",
                        trailingIcon = MaterialSymbols.Outlined.Add,
                        onClick = {
                            val scope = AutomationScope(type, UUID.randomUUID().toString())
                            persist {
                                spec.store.save(
                                    AutomationStoredScope(
                                        scope = scope,
                                        name = newName,
                                        position = (entries.maxOfOrNull { it.position } ?: -1) + 1,
                                    )
                                )
                                onOpen(AutomationRoute.Scope(scope))
                            }
                        },
                    )
                }
            }
        }
        if (entries.isNotEmpty()) {
            item {
                SegmentedColumn {
                    entries.forEachIndexed { index, stored ->
                        item(key = stored.scope.scopeId) {
                            if (type == AutomationScopeType.PRESET && spec.scopeSwitch != null) {
                                val count = ruleCount(spec, stored)
                                ConfiguredScopeRow(
                                    spec = spec,
                                    stored = stored,
                                    title = stored.name ?: stored.scope.scopeId,
                                    description = pluralStringResource(R.plurals.automation_overrides, count, count),
                                    onClick = { onOpen(AutomationRoute.Scope(stored.scope)) },
                                )
                                return@item
                            }
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stored.name ?: stored.scope.scopeId,
                                description = if (type == AutomationScopeType.ITEM) {
                                    scopeSummaryForItem(spec, snapshot, stored.scope.scopeId)
                                } else pluralStringResource(R.plurals.automation_overrides, stored.values.size, stored.values.size),
                                onClick = { onOpen(AutomationRoute.Scope(stored.scope)) },
                                trailingContent = {
                                    if (reorderable) {
                                        Row {
                                            IconButton(enabled = index > 0, onClick = {
                                                val ids = entries.map { it.scope.scopeId }.toMutableList()
                                                ids.add(index - 1, ids.removeAt(index))
                                                persist { spec.store.reorder(type, ids) }
                                            }) { Icon(MaterialSymbols.Outlined.Arrow_upward, null) }
                                            IconButton(enabled = index < entries.lastIndex, onClick = {
                                                val ids = entries.map { it.scope.scopeId }.toMutableList()
                                                ids.add(index + 1, ids.removeAt(index))
                                                persist { spec.store.reorder(type, ids) }
                                            }) { Icon(MaterialSymbols.Outlined.Arrow_downward, null) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- batch --------------------------------------------------------------------------------------

/** Applies the rules activated here to every scope in [scopes], leaving their other rules intact. */
@Composable
fun AutomationBatchScreen(spec: AutomationSpec, scopes: List<AutomationScope>, onBack: () -> Unit) {
    val activity = LocalActivity.current as ComponentActivity
    val snapshot = rememberAutomationSnapshot(spec)
    val coroutineScope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(AutomationRuleValues()) }
    // Presets to link to every selected scope (Hchat's batch template binding); null leaves them unchanged.
    var draftPresets by remember { mutableStateOf<List<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf<AutomationTextEditRequest?>(null) }
    val pickFiles = rememberAutomationFilePicker()
    val parentRules = snapshot.parentRules(scopes.first())
    val effective = parentRules.overlay(draft)
    val validationError = spec.validate(effective, draft.keyIds)
    val savedText = stringResource(R.string.settings_saved)
    val failedText = stringResource(R.string.structured_storage_save_failed)
    val context = AutomationEditorContext(activity, scopes.first(), { editText = it }, pickFiles, LocalAutomationContactPicker.current)
    val currentLabel = stringResource(R.string.automation_batch_current)

    SettingsScaffold(
        title = pluralStringResource(R.plurals.automation_batch_title, scopes.size, scopes.size),
        onBack = onBack,
        actions = {
            IconButton(enabled = !saving && validationError == null && (!draft.isEmpty() || draftPresets != null), onClick = {
                saving = true
                coroutineScope.launch {
                    try {
                        val current = spec.store.snapshot()
                        spec.store.saveAll(scopes.map { scope ->
                            val stored = current[scope]
                            stored.copy(
                                values = AutomationRuleValues(stored.values.values + draft.values),
                                presets = draftPresets ?: stored.presets,
                            )
                        })
                        showToast(activity, savedText)
                        onBack()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "failed to batch save ${spec.id}", e)
                        showToast(activity, failedText)
                    } finally {
                        saving = false
                    }
                }
            }) { Icon(MaterialSymbols.Outlined.Check, stringResource(R.string.action_save)) }
        },
    ) {
        if (AutomationScopeType.PRESET in spec.scopeTypes) {
            item {
                SegmentedColumn {
                    item(key = "presets") {
                        val presets = snapshot.ofType(AutomationScopeType.PRESET)
                        val title = stringResource(R.string.automation_apply_presets)
                        AutomationNavigationRow(
                            title = title,
                            description = draftPresets?.let { ids ->
                                if (ids.isEmpty()) stringResource(R.string.automation_none_selected)
                                else ids.joinToString(", ") { id -> presets.firstOrNull { it.scope.scopeId == id }?.name ?: id }
                            } ?: stringResource(R.string.automation_batch_presets_unchanged),
                            enabled = presets.isNotEmpty(),
                            onClick = {
                                showComposeDialog(activity) {
                                    PresetPicker(title, presets, draftPresets.orEmpty(), onDismiss) {
                                        draftPresets = it
                                        onDismiss()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.automation_batch_hint)) {
                automationRuleItems(
                    editors = spec.editors,
                    rules = effective,
                    overridden = draft.keyIds,
                    parentLabel = currentLabel,
                    context = context,
                    onActivate = { key -> draft = activate(key, parentRules, draft) },
                    onReset = { key -> draft = draft.without(key) },
                    onChange = { key, value -> draft = withValue(key, value, draft) },
                )
                if (validationError != null) {
                    item(key = "validation_error") { AutomationErrorRow(validationError) }
                }
            }
        }
    }
    editText?.let { AutomationTextEditDialog(it, onClose = { editText = null }) }
}
