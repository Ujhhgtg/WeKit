package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Upload
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.items.AtomicJsonConfigStore
import dev.ujhhgtg.wekit.features.items.AutomationContactSettingsSelector
import dev.ujhhgtg.wekit.features.items.AutomationKeywordControls
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationRuleHeader
import dev.ujhhgtg.wekit.features.items.AutomationScrollableColumn
import dev.ujhhgtg.wekit.features.items.AutomationSettingsError
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeControls
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.automationKeywordSummary
import dev.ujhhgtg.wekit.features.items.formatAutomationMinute
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
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
import java.nio.file.Files
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
                title = { Text("聊天自动回复") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { showGlobalDialog(context) },
                            content = { Text("全局设置") },
                            supportingContent = { Text("配置默认自动回复任务与操作") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { showContactSelector(context) },
                            content = { Text("分联系人设置") },
                            supportingContent = { Text("为联系人、群聊或群成员覆盖全局设置") },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            )
        }
    }

    private fun showGlobalDialog(context: Context) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(globalRules()) }
            val validationError = validate(draft)

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("全局设置") },
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
                            showToast("全局设置已保存")
                            onDismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    private fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            AutomationContactSettingsSelector(
                title = "分联系人设置",
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val count = contactOverrides(contact.wxId).overriddenCount()
                    when {
                        contact.wxId.isGroupChatWxId && count > 0 -> "群聊设置 - 已覆盖 $count 项"
                        contact.wxId.isGroupChatWxId -> "群聊设置"
                        count > 0 -> "已覆盖 $count 项"
                        else -> "跟随全局设置"
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
                            parentLabel = "全局设置",
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

            AlertDialogContent(
                title = { Text(groupName) },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                showOverrideDialog(
                                    context = context,
                                    title = "群聊全局设置",
                                    parentLabel = "全局设置",
                                    parent = globalRules(),
                                    initial = contactOverrides(groupId),
                                    onSave = {
                                        setContactOverrides(groupId, it)
                                        revision++
                                        onUpdated()
                                    },
                                )
                            },
                            content = { Text("群聊全局设置") },
                            supportingContent = {
                                Text(if (groupOverrideCount == 0) "跟随全局设置" else "已覆盖 $groupOverrideCount 项")
                            },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                showGroupMemberSelector(context, groupId) {
                                    revision++
                                    onUpdated()
                                }
                            },
                            content = { Text("群聊分群成员设置") },
                            supportingContent = {
                                Text(if (memberCount == 0) "所有成员跟随群聊全局设置" else "已配置 $memberCount 个成员")
                            },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } },
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

            AutomationContactSettingsSelector(
                title = "$groupName - 分群成员设置",
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = groupMemberOverrides(groupId, member.wxId).overriddenCount()
                    if (count == 0) "跟随群聊全局设置" else "已覆盖 $count 项"
                },
                isConfigured = { member ->
                    groupMemberOverrides(groupId, member.wxId).overriddenCount() > 0
                },
                onDismiss = onDismiss,
                onOpen = { member ->
                    showOverrideDialog(
                        context = context,
                        title = member.displayName.ifBlank { member.wxId },
                        parentLabel = "群聊全局设置",
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
                            showToast("设置已保存")
                            onDismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
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
                title = { Text(initial.name.ifBlank { "任务设置" }) },
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
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
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
        val isGlobalEditor = overriddenKeys == null

        AutomationScrollableColumn {
            RuleHeader(
                title = "自动回复",
                summary = if (rules.enabled.enabled) {
                    "按下方任务规则匹配收到的文字消息并自动回复"
                } else {
                    "不自动回复"
                },
                enabled = rules.enabled.enabled,
                key = RuleKey.ENABLED,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(RuleKey.ENABLED, rules.copy(enabled = rules.enabled.copy(enabled = it)))
                },
            )

            val timeEditable = overriddenKeys == null || RuleKey.TIME_RANGE in overriddenKeys
            RuleHeader(
                title = "时间段自动回复",
                summary = if (rules.timeRange.enabled) {
                    "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                } else {
                    "不限制回复时间"
                },
                enabled = rules.timeRange.enabled,
                key = RuleKey.TIME_RANGE,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(
                        RuleKey.TIME_RANGE,
                        rules.copy(timeRange = rules.timeRange.copy(enabled = it)),
                    )
                },
            )
            if (rules.timeRange.enabled) {
                AutomationTimeRangeControls(
                    rule = rules.timeRange,
                    editable = timeEditable,
                    onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) },
                )
            }

            ListItem(
                content = { Text("任务 (按顺序匹配)") },
                supportingContent = {
                    Text(
                        if (rules.tasks.isEmpty()) {
                            "尚未添加任务"
                        } else {
                            "长按拖动手柄调整顺序 · ${rules.tasks.size} 个任务"
                        }
                    )
                },
            )
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
                                contentDescription = "拖动任务",
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
                                text = task.name.ifBlank { "任务 ${index + 1}" },
                                maxLines = 1,
                            )
                            Text(
                                text = automationKeywordSummary(task.keyword, "不限关键词"),
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
                                contentDescription = "删除任务",
                            )
                        }
                    }
                }
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onAddTask),
                content = { Text("添加任务") },
                supportingContent = { Text("设置关键词、回复内容与延迟") },
            )

            AutomationSettingsError(validationError)
        }
    }

    @Composable
    private fun TaskEditor(
        task: AutoReplyTask,
        onChange: (AutoReplyTask) -> Unit,
        validationError: String?,
    ) {
        AutomationScrollableColumn {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                value = task.name,
                onValueChange = { onChange(task.copy(name = it)) },
                label = { Text("任务名称") },
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用任务", modifier = Modifier.weight(1f))
                Switch(
                    checked = task.enabled,
                    onCheckedChange = { onChange(task.copy(enabled = it)) },
                )
            }
            AutomationKeywordControls(
                rule = task.keyword,
                editable = true,
                onChange = { onChange(task.copy(keyword = it)) },
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                AutoReplyType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = task.reply.type == type,
                        onClick = { onChange(task.copy(reply = task.reply.copy(type = type))) },
                        shape = SegmentedButtonDefaults.itemShape(index, AutoReplyType.entries.size),
                    ) {
                        Text(
                            when (type) {
                                AutoReplyType.TEXT -> "文本"
                                AutoReplyType.IMAGE -> "图片"
                                AutoReplyType.VIDEO -> "视频"
                                AutoReplyType.VOICE -> "语音"
                            }
                        )
                    }
                }
            }
            when (task.reply.type) {
                AutoReplyType.TEXT -> OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    value = task.reply.text,
                    onValueChange = { onChange(task.copy(reply = task.reply.copy(text = it))) },
                    label = { Text("回复内容") },
                    singleLine = true,
                )

                AutoReplyType.IMAGE -> AssetPathField(
                    type = AutoReplyType.IMAGE,
                    path = task.reply.path,
                    onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                )

                AutoReplyType.VIDEO -> AssetPathField(
                    type = AutoReplyType.VIDEO,
                    path = task.reply.path,
                    onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                )

                AutoReplyType.VOICE -> {
                    AssetPathField(
                        type = AutoReplyType.VOICE,
                        path = task.reply.path,
                        onChange = { onChange(task.copy(reply = task.reply.copy(path = it))) },
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        value = task.reply.voiceDurationMs,
                        onValueChange = {
                            onChange(
                                task.copy(
                                    reply = task.reply.copy(
                                        voiceDurationMs = it.filter(Char::isDigit).take(5),
                                    ),
                                ),
                            )
                        },
                        label = { Text("语音时长 (毫秒)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                value = task.delayMs,
                onValueChange = { onChange(task.copy(delayMs = it.filter(Char::isDigit).take(5))) },
                label = { Text("回复延迟 (毫秒, 0-60000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                value = task.cooldownMs,
                onValueChange = { onChange(task.copy(cooldownMs = it.filter(Char::isDigit).take(7))) },
                label = { Text("冷却时间 (毫秒)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("命中后停止匹配后续任务", modifier = Modifier.weight(1f))
                Switch(
                    checked = task.stopAfterMatch,
                    onCheckedChange = { onChange(task.copy(stopAfterMatch = it)) },
                )
            }

            AutomationSettingsError(validationError)
        }
    }

    @Composable
    private fun AssetPathField(
        type: AutoReplyType,
        path: String,
        onChange: (String) -> Unit,
    ) {
        val context = LocalContext.current
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            value = path,
            onValueChange = onChange,
            label = {
                Text(
                    when (type) {
                        AutoReplyType.IMAGE -> "图片路径"
                        AutoReplyType.VIDEO -> "视频路径"
                        AutoReplyType.VOICE -> "语音文件路径 (amr/silk)"
                        AutoReplyType.TEXT -> ""
                    }
                )
            },
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
                    Icon(MaterialSymbols.Outlined.Upload, contentDescription = "导入")
                }
            },
            singleLine = true,
        )
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
                            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
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

    @Composable
    private fun RuleHeader(
        title: String,
        summary: String,
        enabled: Boolean,
        key: RuleKey,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onEnabledChange: (Boolean) -> Unit,
    ) {
        AutomationRuleHeader(
            title = title,
            summary = summary,
            enabled = enabled,
            isOverridden = overriddenKeys?.let { key in it },
            parentLabel = parentLabel,
            onActivate = { onActivate(key) },
            onReset = { onReset(key) },
            onEnabledChange = onEnabledChange,
        )
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

    private fun validate(rules: AutoReplyRuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys

        if (validates(RuleKey.TASKS)) {
            rules.tasks.forEachIndexed { index, task ->
                if (!task.enabled) return@forEachIndexed
                validateTask(task)?.let {
                    return "任务「${task.name.ifBlank { "任务 ${index + 1}" }}」: $it"
                }
            }
        }
        return null
    }

    private fun validateTask(task: AutoReplyTask): String? {
        if (!task.enabled) return null
        task.keyword.validationError("关键词")?.let { return it }
        when (task.reply.type) {
            AutoReplyType.TEXT -> if (task.reply.text.isBlank()) return "文本回复内容不能为空"
            AutoReplyType.IMAGE, AutoReplyType.VIDEO, AutoReplyType.VOICE -> if (task.reply.path.isBlank()) {
                return "回复文件路径不能为空"
            }
        }
        if (task.reply.type == AutoReplyType.VOICE) {
            val duration = task.reply.voiceDurationMs.toIntOrNull()
            if (duration == null || duration < 1 || duration > 60000) {
                return "语音时长必须在 1-60000 毫秒之间"
            }
        }
        val delay = task.delayMs.toLongOrNull()
        if (delay == null || delay < 0 || delay > 60000) return "延迟必须在 0-60000 毫秒之间"
        val cooldown = task.cooldownMs.toLongOrNull()
        if (cooldown == null || cooldown < 0) return "冷却时间不能为负数"
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
