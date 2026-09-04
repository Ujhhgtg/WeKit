package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.fs.copyFrom
import kotlin.io.path.outputStream
import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Drag_handle
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.items.AtomicJsonConfigStore
import dev.ujhhgtg.wekit.features.items.AutomationContactSettingsSelector
import dev.ujhhgtg.wekit.features.items.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.formatAutomationMinute
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.features.items.payment.PaymentErrorRow
import dev.ujhhgtg.wekit.features.items.payment.PaymentNavigationRow
import dev.ujhhgtg.wekit.features.items.payment.PaymentRuleRow
import dev.ujhhgtg.wekit.features.items.payment.keywordItems
import dev.ujhhgtg.wekit.features.items.payment.timeRangeItems
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.ReorderableList
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.StandardCopyOption
import kotlin.io.path.div

private const val CONFIG_VERSION = 1

@Serializable
internal enum class AutoReplyType { TEXT, IMAGE, VIDEO, VOICE }

@Serializable
internal data class AutoReplyRule(
    val type: AutoReplyType = AutoReplyType.TEXT,
    val text: String = "",
    val path: String = "",
    val voiceDurationMs: String = "1000",
)

@Serializable
internal data class AutoReplyTask(
    val name: String = "",
    val enabled: Boolean = true,
    val keyword: AutomationKeywordRule = AutomationKeywordRule(ignoreCase = true),
    val reply: AutoReplyRule = AutoReplyRule(),
    val delayMs: String = "0",
    val cooldownMs: String = "0",
    val stopAfterMatch: Boolean = true,
)

@Serializable
internal data class AutoReplyRuleSet(
    val enabled: AutomationToggleRule = AutomationToggleRule(),
    val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
    val tasks: List<AutoReplyTask> = emptyList(),
)

@Serializable
internal data class AutoReplyRuleOverrides(
    val enabled: AutomationToggleRule? = null,
    val timeRange: AutomationTimeRangeRule? = null,
    val tasks: List<AutoReplyTask>? = null,
) {
    fun isEmpty(): Boolean = enabled == null && timeRange == null && tasks == null
}

@Serializable
private data class StoredConfig(
    val version: Int = CONFIG_VERSION,
    val global: AutoReplyRuleSet = AutoReplyRuleSet(),
    val contacts: Map<String, AutoReplyRuleOverrides> = emptyMap(),
    val groupMembers: Map<String, Map<String, AutoReplyRuleOverrides>> = emptyMap(),
)

/** 聊天自动回复分层配置（全局 → 联系人 → 群成员），模式与 RedPacketSettings 一致。 */
internal object AutoReplySettings {
    private const val TAG = "AutoReplySettings"

    private val configFile by lazy { KnownPaths.moduleData / "auto_reply_settings.json" }

    private enum class RuleKey { ENABLED, TIME_RANGE, TASKS }

    private val store by lazy {
        AtomicJsonConfigStore(
            file = configFile,
            serializer = StoredConfig.serializer(),
            tag = TAG,
            initialValue = { StoredConfig() },
        )
    }

    fun resolve(talker: String, sender: String?): AutoReplyRuleSet {
        val config = loadConfig()
        var rules = config.global.apply(config.contacts[talker])
        if (talker.isGroupChatWxId && !sender.isNullOrBlank()) {
            rules = rules.apply(config.groupMembers[talker]?.get(sender))
        }
        return rules
    }

    fun showMainDialog(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_auto_reply_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_global_settings),
                                description = stringResource(R.string.chat_auto_reply_global_settings_summary),
                                onClick = { showGlobalDialog(context) },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_contact_settings),
                                description = stringResource(R.string.chat_auto_reply_contact_settings_summary),
                                onClick = { showContactSelector(context) },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun showGlobalDialog(context: Context) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(globalRules()) }
            val validationError = validate(draft)
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(stringResource(R.string.chat_auto_reply_global_settings)) },
                text = {
                    RuleSetEditor(
                        rules = draft,
                        overriddenKeys = null,
                        parentLabel = "",
                        onActivate = {},
                        onReset = {},
                        onChange = { _, updated -> draft = updated },
                        onEditTask = { index ->
                            showTaskDialog(context, draft.tasks[index]) { updated ->
                                val tasks = draft.tasks.toMutableList().apply { this[index] = updated }
                                draft = draft.copy(tasks = tasks)
                            }
                        },
                        onAddTask = {
                            showTaskDialog(context, AutoReplyTask()) { updated ->
                                draft = draft.copy(tasks = draft.tasks + updated)
                            }
                        },
                        validationError = validationError,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            updateConfig { it.copy(global = draft) }
                            showToast(localizedContext.getString(R.string.chat_auto_reply_global_saved))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            val contactSettingsTitle = stringResource(R.string.chat_auto_reply_contact_settings)
            val groupSettings = stringResource(R.string.chat_auto_reply_group_settings)
            val followsGlobal = stringResource(R.string.chat_auto_reply_follows_global)
            val globalSettings = stringResource(R.string.chat_auto_reply_global_settings)
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            AutomationContactSettingsSelector(
                title = contactSettingsTitle,
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val count = contactOverrides(contact.wxId).overriddenCount()
                    when {
                        contact.wxId.isGroupChatWxId && count > 0 ->
                            localizedContext.resources.getQuantityString(
                                R.plurals.chat_auto_reply_group_overridden_count,
                                count,
                                count,
                            )
                        contact.wxId.isGroupChatWxId -> groupSettings
                        count > 0 -> localizedContext.resources.getQuantityString(
                            R.plurals.chat_auto_reply_overridden_count,
                            count,
                            count,
                        )
                        else -> followsGlobal
                    }
                },
                isConfigured = { contact ->
                    contactOverrides(contact.wxId).overriddenCount() > 0 ||
                        memberOverridesCount(contact.wxId) > 0
                },
                onDismiss = onDismiss,
                onOpen = { contact ->
                    if (contact.wxId.isGroupChatWxId) {
                        showGroupSettingsDialog(context, contact.wxId) { revision++ }
                    } else {
                        showOverrideDialog(
                            context = context,
                            title = contact.displayName.ifBlank { contact.wxId },
                            parentLabel = globalSettings,
                            parent = globalRules(),
                            initial = contactOverrides(contact.wxId),
                            onSave = {
                                setContactOverrides(contact.wxId, it)
                                revision++
                            },
                        )
                    }
                },
            )
        }
    }

    private fun showGroupSettingsDialog(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val groupOverrideCount = remember(revision) {
                contactOverrides(groupId).overriddenCount()
            }
            val memberCount = remember(revision) { memberOverridesCount(groupId) }
            val globalSettings = stringResource(R.string.chat_auto_reply_global_settings)
            val groupGlobalSettings = stringResource(R.string.chat_auto_reply_group_global_settings)

            AlertDialogContent(
                title = { Text(groupName) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_group_global_settings),
                                description = if (groupOverrideCount == 0) {
                                    stringResource(R.string.chat_auto_reply_follows_global)
                                } else {
                                    pluralStringResource(
                                        R.plurals.chat_auto_reply_overridden_count,
                                        groupOverrideCount,
                                        groupOverrideCount,
                                    )
                                },
                                onClick = {
                                    showOverrideDialog(
                                        context = context,
                                        title = groupGlobalSettings,
                                        parentLabel = globalSettings,
                                        parent = globalRules(),
                                        initial = contactOverrides(groupId),
                                        onSave = {
                                            setContactOverrides(groupId, it)
                                            revision++
                                            onUpdated()
                                        },
                                    )
                                },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.chat_auto_reply_group_member_settings),
                                description = if (memberCount == 0) {
                                    stringResource(R.string.chat_auto_reply_all_members_follow_group)
                                } else {
                                    pluralStringResource(
                                        R.plurals.chat_auto_reply_configured_member_count,
                                        memberCount,
                                        memberCount,
                                    )
                                },
                                onClick = {
                                    showGroupMemberSelector(context, groupId) {
                                        revision++
                                        onUpdated()
                                    }
                                },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun showGroupMemberSelector(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val members = remember(groupId) {
                runCatching { WeDatabaseApi.getGroupMembers(groupId) }
                    .onFailure { WeLogger.e(TAG, "failed to load members of $groupId", it) }
                    .getOrDefault(emptyList())
            }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            val groupGlobalSettings = stringResource(R.string.chat_auto_reply_group_global_settings)

            AutomationContactSettingsSelector(
                title = stringResource(R.string.chat_auto_reply_group_member_settings_title, groupName),
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = groupMemberOverrides(groupId, member.wxId).overriddenCount()
                    if (count == 0) {
                        localizedContext.getString(R.string.chat_auto_reply_follows_group)
                    } else {
                        localizedContext.resources.getQuantityString(
                            R.plurals.chat_auto_reply_overridden_count,
                            count,
                            count,
                        )
                    }
                },
                isConfigured = { member ->
                    groupMemberOverrides(groupId, member.wxId).overriddenCount() > 0
                },
                onDismiss = onDismiss,
                onOpen = { member ->
                    showOverrideDialog(
                        context = context,
                        title = member.displayName.ifBlank { member.wxId },
                        parentLabel = groupGlobalSettings,
                        parent = globalRules().apply(contactOverrides(groupId)),
                        initial = groupMemberOverrides(groupId, member.wxId),
                        onSave = {
                            setGroupMemberOverrides(groupId, member.wxId, it)
                            revision++
                            onUpdated()
                        },
                    )
                },
            )
        }
    }

    private fun showOverrideDialog(
        context: Context,
        title: String,
        parentLabel: String,
        parent: AutoReplyRuleSet,
        initial: AutoReplyRuleOverrides,
        onSave: (AutoReplyRuleOverrides) -> Unit,
    ) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(initial) }
            val effective = parent.apply(draft)
            val validationError = validate(effective, draft.keys())
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(title) },
                text = {
                    RuleSetEditor(
                        rules = effective,
                        overriddenKeys = draft.keys(),
                        parentLabel = parentLabel,
                        onActivate = { key -> draft = draft.withRule(key, effective) },
                        onReset = { key -> draft = draft.withoutRule(key) },
                        onChange = { key, updated -> draft = draft.withRule(key, updated) },
                        onEditTask = { index ->
                            val base = draft.tasks ?: effective.tasks
                            showTaskDialog(context, base[index]) { updated ->
                                val tasks = base.toMutableList().apply { this[index] = updated }
                                draft = draft.copy(tasks = tasks)
                            }
                        },
                        onAddTask = {
                            val base = draft.tasks ?: effective.tasks
                            showTaskDialog(context, AutoReplyTask()) { updated ->
                                draft = draft.copy(tasks = base + updated)
                            }
                        },
                        validationError = validationError,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast(localizedContext.getString(R.string.chat_auto_reply_settings_saved))
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showTaskDialog(
        context: Context,
        initial: AutoReplyTask,
        onSave: (AutoReplyTask) -> Unit,
    ) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(initial) }
            val validationError = validateTask(draft)

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = {
                    Text(initial.name.ifBlank { stringResource(R.string.chat_auto_reply_task_settings) })
                },
                text = {
                    TaskEditor(
                        task = draft,
                        onChange = { draft = it },
                        validationError = validationError,
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @Composable
    private fun RuleSetEditor(
        rules: AutoReplyRuleSet,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onChange: (RuleKey, AutoReplyRuleSet) -> Unit,
        onEditTask: (Int) -> Unit,
        onAddTask: () -> Unit,
        validationError: String?,
    ) {
        fun overridden(key: RuleKey): Boolean? = overriddenKeys?.let { key in it }
        fun editable(key: RuleKey): Boolean = overriddenKeys == null || key in overriddenKeys

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item(key = "enabled") { PaymentRuleRow(
                title = stringResource(R.string.chat_auto_reply_enabled_title),
                summary = if (rules.enabled.enabled) {
                    stringResource(R.string.chat_auto_reply_enabled_summary)
                } else {
                    stringResource(R.string.chat_auto_reply_disabled_summary)
                },
                checked = rules.enabled.enabled,
                overridden = overridden(RuleKey.ENABLED),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.ENABLED) },
                onReset = { onReset(RuleKey.ENABLED) },
                onCheckedChange = {
                    onChange(RuleKey.ENABLED, rules.copy(enabled = rules.enabled.copy(enabled = it)))
                },
            ) }

            item(key = "time_range") { PaymentRuleRow(
                title = stringResource(R.string.chat_auto_reply_time_range_title),
                summary = if (rules.timeRange.enabled) {
                    "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                } else {
                    stringResource(R.string.chat_auto_reply_time_unrestricted)
                },
                checked = rules.timeRange.enabled,
                overridden = overridden(RuleKey.TIME_RANGE),
                parentLabel = parentLabel,
                onActivate = { onActivate(RuleKey.TIME_RANGE) },
                onReset = { onReset(RuleKey.TIME_RANGE) },
                onCheckedChange = {
                    onChange(
                        RuleKey.TIME_RANGE,
                        rules.copy(timeRange = rules.timeRange.copy(enabled = it)),
                    )
                },
            ) }
            timeRangeItems(
                rule = rules.timeRange,
                editable = editable(RuleKey.TIME_RANGE),
                visible = rules.timeRange.enabled,
                onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) },
            )

            item(key = "tasks_header") {
                BaseWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_tasks_title),
                    description = if (rules.tasks.isEmpty()) {
                        stringResource(R.string.chat_auto_reply_no_tasks)
                    } else {
                        pluralStringResource(
                            R.plurals.chat_auto_reply_task_count_summary,
                            rules.tasks.size,
                            rules.tasks.size,
                        )
                    },
                )
            }
            }
            if (rules.tasks.isNotEmpty()) {
                ReorderableList(
                    items = rules.tasks,
                    itemKey = { System.identityHashCode(it) },
                    onMove = { from, to ->
                        val tasks = rules.tasks.toMutableList()
                        tasks.add(to, tasks.removeAt(from))
                        onChange(RuleKey.TASKS, rules.copy(tasks = tasks))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) { task, dragHandleModifier ->
                    val index = rules.tasks.indexOfFirst { it === task }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .then(dragHandleModifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Drag_handle,
                                contentDescription = stringResource(R.string.chat_auto_reply_drag_task),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEditTask(index) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = task.name.ifBlank {
                                    stringResource(R.string.chat_auto_reply_task_number, index + 1)
                                },
                                maxLines = 1,
                            )
                            Text(
                                text = autoReplyKeywordSummary(task.keyword),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(
                            onClick = {
                                onChange(RuleKey.TASKS, rules.copy(tasks = rules.tasks - task))
                            },
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Delete,
                                contentDescription = stringResource(R.string.chat_auto_reply_delete_task),
                            )
                        }
                    }
                }
            }
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                item(key = "add_task") {
                    PaymentNavigationRow(
                        title = stringResource(R.string.chat_auto_reply_add_task),
                        description = stringResource(R.string.chat_auto_reply_add_task_summary),
                        onClick = onAddTask,
                    )
                }
                validationError?.let { error ->
                    item(key = "validation_error") { PaymentErrorRow(error) }
                }
            }
        }
    }

    @Composable
    private fun TaskEditor(
        task: AutoReplyTask,
        onChange: (AutoReplyTask) -> Unit,
        validationError: String?,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
            item(key = "name") {
                BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_task_name)) {
                    InlineTaskTextField(value = task.name, onValueChange = { onChange(task.copy(name = it)) })
                }
            }
            item(key = "enabled") {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_enable_task),
                    checked = task.enabled,
                    onCheckedChange = { onChange(task.copy(enabled = it)) },
                )
            }
            keywordItems(
                keyPrefix = "task_keyword",
                rule = task.keyword,
                editable = true,
                visible = true,
                modes = AutomationKeywordMode.entries,
                onChange = { onChange(task.copy(keyword = it)) },
                onEditText = {},
                inlineTextFields = true,
            )
            item(key = "reply_type") {
                DropDownMenuWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_task_settings),
                    description = null,
                    value = task.reply.type,
                    options = AutoReplyType.entries.map { type ->
                        DropdownOption(type, stringResource(type.labelRes))
                    },
                    onValueChange = { onChange(task.copy(reply = task.reply.copy(type = it))) },
                )
            }
            when (task.reply.type) {
                AutoReplyType.TEXT -> item(key = "reply_text") {
                    BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_reply_content)) {
                        InlineTaskTextField(
                            value = task.reply.text,
                            onValueChange = { onChange(task.copy(reply = task.reply.copy(text = it))) },
                        )
                    }
                }

                AutoReplyType.IMAGE -> item(key = "image_path") { AssetPathField(
                    type = AutoReplyType.IMAGE,
                    path = task.reply.path,
                    onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                ) }

                AutoReplyType.VIDEO -> item(key = "video_path") { AssetPathField(
                    type = AutoReplyType.VIDEO,
                    path = task.reply.path,
                    onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                ) }

                AutoReplyType.VOICE -> {
                    item(key = "voice_path") { AssetPathField(
                        type = AutoReplyType.VOICE,
                        path = task.reply.path,
                        onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                    ) }
                    item(key = "voice_duration") {
                        BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_voice_duration_ms)) {
                            InlineTaskTextField(
                                value = task.reply.voiceDurationMs,
                                keyboardType = KeyboardType.Number,
                                onValueChange = {
                                    onChange(task.copy(reply = task.reply.copy(voiceDurationMs = it.filter(Char::isDigit).take(5))))
                                },
                            )
                        }
                    }
                }
            }
            item(key = "delay") {
                BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_delay_ms)) {
                    InlineTaskTextField(
                        value = task.delayMs,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { onChange(task.copy(delayMs = it.filter(Char::isDigit).take(5))) },
                    )
                }
            }
            item(key = "cooldown") {
                BaseSupportingWidget(title = stringResource(R.string.chat_auto_reply_cooldown_ms)) {
                    InlineTaskTextField(
                        value = task.cooldownMs,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { onChange(task.copy(cooldownMs = it.filter(Char::isDigit).take(7))) },
                    )
                }
            }
            item(key = "stop_after_match") {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.chat_auto_reply_stop_after_match),
                    checked = task.stopAfterMatch,
                    onCheckedChange = { onChange(task.copy(stopAfterMatch = it)) },
                )
            }

            validationError?.let { error ->
                item(key = "validation_error") { PaymentErrorRow(error) }
            }
            }
        }
    }

    @Composable
    private fun InlineTaskTextField(
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        onValueChange: (String) -> Unit,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
        )
    }

    private val AutoReplyType.labelRes: Int
        get() = when (this) {
            AutoReplyType.TEXT -> R.string.chat_auto_reply_type_text
            AutoReplyType.IMAGE -> R.string.chat_auto_reply_type_image
            AutoReplyType.VIDEO -> R.string.chat_auto_reply_type_video
            AutoReplyType.VOICE -> R.string.chat_auto_reply_type_voice
        }

    @Composable
    private fun AssetPathField(
        type: AutoReplyType,
        path: String,
        onChange: (String) -> Unit,
    ) {
        val context = LocalContext.current
        val title = stringResource(
            when (type) {
                AutoReplyType.IMAGE -> R.string.chat_auto_reply_image_path
                AutoReplyType.VIDEO -> R.string.chat_auto_reply_video_path
                AutoReplyType.VOICE -> R.string.chat_auto_reply_voice_path
                AutoReplyType.TEXT -> R.string.chat_auto_reply_reply_content
            }
        )
        BaseSupportingWidget(title = title) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                value = path,
                onValueChange = onChange,
                label = { Text(title) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            importAsset(
                                context = context,
                                mimeTypes = when (type) {
                                    AutoReplyType.IMAGE -> arrayOf("image/*")
                                    AutoReplyType.VIDEO -> arrayOf("video/*")
                                    AutoReplyType.VOICE -> arrayOf("audio/*")
                                    AutoReplyType.TEXT -> return@IconButton
                                },
                                typePrefix = when (type) {
                                    AutoReplyType.IMAGE -> "image"
                                    AutoReplyType.VIDEO -> "video"
                                    AutoReplyType.VOICE -> "voice"
                                    AutoReplyType.TEXT -> ""
                                },
                                onImported = onChange,
                            )
                        },
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Download,
                            contentDescription = stringResource(R.string.chat_auto_reply_import),
                        )
                    }
                },
                singleLine = true,
            )
        }
    }

    /**
     * 用 TransparentActivity 拉起系统文件选择器，把所选文件拷贝到
     * `KnownPaths.userAssets`（文件名 `<type>_<timestamp>.<ext>`），成功后回填路径。
     */
    private fun importAsset(
        context: Context,
        mimeTypes: Array<String>,
        typePrefix: String,
        onImported: (String) -> Unit,
    ) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val extension = queryDisplayName(contentResolver, uri)?.substringAfterLast('.', "")
                            ?.lowercase()?.takeIf(String::isNotBlank)
                            ?: fallbackExtension(contentResolver.getType(uri))
                        val target = KnownPaths.userAssets /
                            "${typePrefix}_${System.currentTimeMillis()}.$extension"
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.copyFrom(input)
                        } ?: error("cannot open picked file")
                        withContext(Dispatchers.Main) {
                            onImported(target.toString())
                            finish()
                        }
                    }.onFailure {
                        WeLogger.e(TAG, "import asset failed", it)
                        withContext(Dispatchers.Main) { finish() }
                    }
                }
            }
            launcher.launch(mimeTypes)
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun fallbackExtension(mimeType: String?): String = when {
        mimeType?.startsWith("image/") == true -> "jpg"
        mimeType?.startsWith("video/") == true -> "mp4"
        mimeType?.startsWith("audio/") == true -> "m4a"
        else -> "bin"
    }

    private fun AutoReplyRuleSet.apply(overrides: AutoReplyRuleOverrides?): AutoReplyRuleSet {
        if (overrides == null) return this
        return copy(
            enabled = overrides.enabled ?: enabled,
            timeRange = overrides.timeRange ?: timeRange,
            tasks = overrides.tasks ?: tasks,
        )
    }

    private fun AutoReplyRuleOverrides.keys(): Set<RuleKey> = buildSet {
        if (enabled != null) add(RuleKey.ENABLED)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (tasks != null) add(RuleKey.TASKS)
    }

    private fun AutoReplyRuleOverrides.withRule(key: RuleKey, rules: AutoReplyRuleSet): AutoReplyRuleOverrides =
        when (key) {
            RuleKey.ENABLED -> copy(enabled = rules.enabled)
            RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
            RuleKey.TASKS -> copy(tasks = rules.tasks)
        }

    private fun AutoReplyRuleOverrides.withoutRule(key: RuleKey): AutoReplyRuleOverrides =
        when (key) {
            RuleKey.ENABLED -> copy(enabled = null)
            RuleKey.TIME_RANGE -> copy(timeRange = null)
            RuleKey.TASKS -> copy(tasks = null)
        }

    private fun AutoReplyRuleOverrides.overriddenCount(): Int =
        listOf(enabled, timeRange, tasks).count { it != null }

    @Composable
    private fun autoReplyKeywordSummary(rule: AutomationKeywordRule): String {
        if (!rule.enabled) return stringResource(R.string.chat_auto_reply_keyword_unrestricted)
        return when (rule.mode) {
            AutomationKeywordMode.STRING_LIST -> pluralStringResource(
                R.plurals.chat_auto_reply_keyword_list_summary,
                rule.strings.size,
                rule.strings.size,
            )
            AutomationKeywordMode.EXACT -> pluralStringResource(
                R.plurals.chat_auto_reply_keyword_exact_summary,
                rule.strings.size,
                rule.strings.size,
            )
            AutomationKeywordMode.REGEX -> if (rule.regex.isBlank()) {
                stringResource(R.string.chat_auto_reply_keyword_regex_empty)
            } else {
                stringResource(R.string.chat_auto_reply_keyword_regex_summary)
            }
        }
    }

    @Composable
    private fun validate(rules: AutoReplyRuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys

        if (validates(RuleKey.TASKS)) {
            for ((index, task) in rules.tasks.withIndex()) {
                if (!task.enabled) continue
                val error = validateTask(task)
                if (error != null) {
                    val name = task.name.ifBlank {
                        stringResource(R.string.chat_auto_reply_task_number, index + 1)
                    }
                    return stringResource(R.string.chat_auto_reply_task_error, name, error)
                }
            }
        }
        return null
    }

    @Composable
    private fun validateTask(task: AutoReplyTask): String? {
        if (!task.enabled) return null
        if (task.keyword.enabled) {
            when (task.keyword.mode) {
                AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.EXACT ->
                    if (task.keyword.strings.none(String::isNotBlank)) {
                        return stringResource(R.string.chat_auto_reply_error_keyword_list_empty)
                    }
                AutomationKeywordMode.REGEX -> when {
                    task.keyword.regex.isBlank() ->
                        return stringResource(R.string.chat_auto_reply_error_keyword_regex_empty)
                    runCatching { Regex(task.keyword.regex) }.isFailure ->
                        return stringResource(R.string.chat_auto_reply_error_keyword_regex_invalid)
                }
            }
        }
        when (task.reply.type) {
            AutoReplyType.TEXT -> if (task.reply.text.isBlank()) {
                return stringResource(R.string.chat_auto_reply_error_text_empty)
            }
            AutoReplyType.IMAGE, AutoReplyType.VIDEO, AutoReplyType.VOICE -> if (task.reply.path.isBlank()) {
                return stringResource(R.string.chat_auto_reply_error_path_empty)
            }
        }
        if (task.reply.type == AutoReplyType.VOICE) {
            val duration = task.reply.voiceDurationMs.toIntOrNull()
            if (duration == null || duration < 1 || duration > 60000) {
                return stringResource(R.string.chat_auto_reply_error_voice_duration)
            }
        }
        val delay = task.delayMs.toLongOrNull()
        if (delay == null || delay < 0 || delay > 60000) {
            return stringResource(R.string.chat_auto_reply_error_delay)
        }
        val cooldown = task.cooldownMs.toLongOrNull()
        if (cooldown == null || cooldown < 0) {
            return stringResource(R.string.chat_auto_reply_error_cooldown)
        }
        return null
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups())
            .distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(TAG, "failed to load contacts", it)
    }.getOrDefault(emptyList())

    private fun globalRules(): AutoReplyRuleSet = loadConfig().global

    private fun contactOverrides(wxId: String): AutoReplyRuleOverrides =
        loadConfig().contacts[wxId] ?: AutoReplyRuleOverrides()

    private fun groupMemberOverrides(groupId: String, memberId: String): AutoReplyRuleOverrides =
        loadConfig().groupMembers[groupId]?.get(memberId) ?: AutoReplyRuleOverrides()

    private fun memberOverridesCount(groupId: String): Int =
        loadConfig().groupMembers[groupId]?.count { !it.value.isEmpty() } ?: 0

    private fun setContactOverrides(wxId: String, overrides: AutoReplyRuleOverrides) {
        updateConfig { config ->
            val contacts = config.contacts.toMutableMap()
            if (overrides.isEmpty()) contacts.remove(wxId) else contacts[wxId] = overrides
            config.copy(contacts = contacts)
        }
    }

    private fun setGroupMemberOverrides(groupId: String, memberId: String, overrides: AutoReplyRuleOverrides) {
        updateConfig { config ->
            val groups = config.groupMembers.toMutableMap()
            val members = groups[groupId].orEmpty().toMutableMap()
            if (overrides.isEmpty()) members.remove(memberId) else members[memberId] = overrides
            if (members.isEmpty()) groups.remove(groupId) else groups[groupId] = members
            config.copy(groupMembers = groups)
        }
    }

    private fun loadConfig(): StoredConfig = store.get()

    private fun updateConfig(transform: (StoredConfig) -> StoredConfig) {
        store.update { transform(it).copy(version = CONFIG_VERSION) }
    }
}
