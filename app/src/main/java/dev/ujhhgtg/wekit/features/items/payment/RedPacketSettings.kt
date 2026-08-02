package dev.ujhhgtg.wekit.features.items.payment

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.items.AtomicJsonConfigStore
import dev.ujhhgtg.wekit.features.items.AutomationKeywordControls
import dev.ujhhgtg.wekit.features.items.AutomationContactSettingsSelector
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationRuleHeader
import dev.ujhhgtg.wekit.features.items.AutomationScrollableColumn
import dev.ujhhgtg.wekit.features.items.AutomationSettingsError
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeControls
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.automationKeywordSummary
import dev.ujhhgtg.wekit.features.items.formatAutomationMinute
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.serialization.Serializable
import java.util.Calendar
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.random.Random

/** Hierarchical settings used by [AutoOpenRedPackets]. */
internal object RedPacketSettings {
    private const val TAG = "RedPacketSettings"
    private const val CONFIG_VERSION = 1

    private val configFile by lazy { KnownPaths.moduleData / "red_packet_settings.json" }
    private val legacyGroupMemberFile by lazy { KnownPaths.moduleData / "red_packet_group_members.json" }

    @Serializable
    data class DelayRule(
        val enabled: Boolean = true,
        val baseMs: String = "500",
        val randomRangeMs: String = "300"
    )

    @Serializable
    data class ReplyRule(
        val enabled: Boolean = false,
        val text: String = ""
    )

    @Serializable
    data class RuleSet(
        val grab: AutomationToggleRule = AutomationToggleRule(enabled = true),
        val grabSelf: AutomationToggleRule = AutomationToggleRule(),
        val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
        val keyword: AutomationKeywordRule = AutomationKeywordRule(),
        val delay: DelayRule = DelayRule(),
        val notification: AutomationToggleRule = AutomationToggleRule(),
        val autoReply: ReplyRule = ReplyRule()
    ) {
        fun delayMillis(): Long {
            if (!delay.enabled) return 0L
            val base = (delay.baseMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val range = (delay.randomRangeMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            if (range == 0L) return base
            val safeRange = range.coerceAtMost(Long.MAX_VALUE - 1)
            val offset = Random.nextLong(-safeRange, safeRange + 1)
            return (base + offset).coerceAtLeast(0L)
        }

        fun isInActiveTime(now: Calendar = Calendar.getInstance()): Boolean = timeRange.matches(now)

        fun matchesKeyword(text: String): Boolean = keyword.matches(text)
    }

    @Serializable
    data class RuleOverrides(
        val grab: AutomationToggleRule? = null,
        val grabSelf: AutomationToggleRule? = null,
        val timeRange: AutomationTimeRangeRule? = null,
        val keyword: AutomationKeywordRule? = null,
        val delay: DelayRule? = null,
        val notification: AutomationToggleRule? = null,
        val autoReply: ReplyRule? = null
    ) {
        fun isEmpty(): Boolean = overriddenCount() == 0

        fun overriddenCount(): Int = listOf(
            grab,
            grabSelf,
            timeRange,
            keyword,
            delay,
            notification,
            autoReply
        ).count { it != null }
    }

    @Serializable
    private data class StoredConfig(
        val version: Int = CONFIG_VERSION,
        val global: RuleSet = RuleSet(),
        val contacts: Map<String, RuleOverrides> = emptyMap(),
        val groupMembers: Map<String, Map<String, RuleOverrides>> = emptyMap()
    )

    @Serializable
    private data class LegacyGroupMemberRule(
        val groupId: String = "",
        val useWhitelist: Boolean = false,
        val members: List<String> = emptyList()
    )

    private enum class RuleKey {
        GRAB,
        GRAB_SELF,
        TIME_RANGE,
        KEYWORD,
        DELAY,
        NOTIFICATION,
        AUTO_REPLY
    }

    private val store by lazy {
        AtomicJsonConfigStore(
            file = configFile,
            serializer = StoredConfig.serializer(),
            tag = TAG,
            initialValue = ::migrateLegacyConfig
        )
    }

    fun resolve(talker: String, sender: String?): RuleSet {
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
                title = { Text("自动抢红包") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { showGlobalDialog(context) },
                            content = { Text("全局设置") },
                            supportingContent = { Text("配置默认抢红包条件与抢到后的操作") }
                        )
                        ListItem(
                            modifier = Modifier.clickable { showContactSelector(context) },
                            content = { Text("分联系人设置") },
                            supportingContent = { Text("为联系人、群聊或群成员覆盖全局设置") }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } }
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
                        validationError = validationError
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            updateConfig { it.copy(global = draft) }
                            showToast("全局设置已保存")
                            onDismiss()
                        }
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
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
                            }
                        )
                    }
                }
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
                                    }
                                )
                            },
                            content = { Text("群聊全局设置") },
                            supportingContent = {
                                Text(if (groupOverrideCount == 0) "跟随全局设置" else "已覆盖 $groupOverrideCount 项")
                            }
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
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } }
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
                        }
                    )
                }
            )
        }
    }

    private fun showOverrideDialog(
        context: Context,
        title: String,
        parentLabel: String,
        parent: RuleSet,
        initial: RuleOverrides,
        onSave: (RuleOverrides) -> Unit
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
                        validationError = validationError
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast("设置已保存")
                            onDismiss()
                        }
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    @Composable
    private fun RuleSetEditor(
        rules: RuleSet,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onChange: (RuleKey, RuleSet) -> Unit,
        validationError: String?
    ) {
        val isGlobalEditor = overriddenKeys == null

        AutomationScrollableColumn {
            RuleHeader(
                title = "默认抢红包",
                summary = if (isGlobalEditor && rules.grab.enabled) {
                    "默认抢所有联系人, 分联系人设置可单独关闭"
                } else if (isGlobalEditor) {
                    "默认不抢任何联系人, 分联系人设置可单独开启"
                } else if (rules.grab.enabled) {
                    "在当前范围内抢红包"
                } else {
                    "在当前范围内跳过红包"
                },
                enabled = rules.grab.enabled,
                key = RuleKey.GRAB,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = { onChange(RuleKey.GRAB, rules.copy(grab = rules.grab.copy(enabled = it))) }
            )

            RuleHeader(
                title = "抢自己的红包",
                summary = if (rules.grabSelf.enabled) "允许抢自己发出的红包" else "跳过自己发出的红包",
                enabled = rules.grabSelf.enabled,
                key = RuleKey.GRAB_SELF,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(RuleKey.GRAB_SELF, rules.copy(grabSelf = rules.grabSelf.copy(enabled = it)))
                }
            )

            val timeEditable = overriddenKeys == null || RuleKey.TIME_RANGE in overriddenKeys
            RuleHeader(
                title = "时间段抢红包",
                summary = if (rules.timeRange.enabled) {
                    "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                } else {
                    "不限制抢红包时间"
                },
                enabled = rules.timeRange.enabled,
                key = RuleKey.TIME_RANGE,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = rules.timeRange.copy(enabled = it)))
                }
            )
            if (rules.timeRange.enabled) {
                AutomationTimeRangeControls(
                    rule = rules.timeRange,
                    editable = timeEditable,
                    onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) }
                )
            }

            val keywordEditable = overriddenKeys == null || RuleKey.KEYWORD in overriddenKeys
            RuleHeader(
                title = "关键词抢红包",
                summary = automationKeywordSummary(rules.keyword, "不限制红包关键词"),
                enabled = rules.keyword.enabled,
                key = RuleKey.KEYWORD,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(RuleKey.KEYWORD, rules.copy(keyword = rules.keyword.copy(enabled = it)))
                }
            )
            if (rules.keyword.enabled) {
                AutomationKeywordControls(
                    rule = rules.keyword,
                    editable = keywordEditable,
                    onChange = { onChange(RuleKey.KEYWORD, rules.copy(keyword = it)) }
                )
            }

            val delayEditable = overriddenKeys == null || RuleKey.DELAY in overriddenKeys
            RuleHeader(
                title = "延迟抢红包",
                summary = if (rules.delay.enabled) {
                    "基础 ${rules.delay.baseMs.ifBlank { "0" }} ms, 随机偏移 ±${rules.delay.randomRangeMs.ifBlank { "0" }} ms"
                } else {
                    "收到后立即抢红包"
                },
                enabled = rules.delay.enabled,
                key = RuleKey.DELAY,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(enabled = it)))
                }
            )
            if (rules.delay.enabled) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    value = rules.delay.baseMs,
                    enabled = delayEditable,
                    onValueChange = {
                        val value = it.filter(Char::isDigit).take(MAX_DELAY_DIGITS)
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(baseMs = value)))
                    },
                    label = { Text("基础延迟 (毫秒)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    value = rules.delay.randomRangeMs,
                    enabled = delayEditable,
                    onValueChange = {
                        val value = it.filter(Char::isDigit).take(MAX_DELAY_DIGITS)
                        onChange(
                            RuleKey.DELAY,
                            rules.copy(delay = rules.delay.copy(randomRangeMs = value))
                        )
                    },
                    label = { Text("随机偏移范围 (±毫秒)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            RuleHeader(
                title = "抢到后通知",
                summary = if (rules.notification.enabled) "显示抢到的金额与来源" else "不显示通知",
                enabled = rules.notification.enabled,
                key = RuleKey.NOTIFICATION,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(
                        RuleKey.NOTIFICATION,
                        rules.copy(notification = rules.notification.copy(enabled = it))
                    )
                }
            )

            val replyEditable = overriddenKeys == null || RuleKey.AUTO_REPLY in overriddenKeys
            RuleHeader(
                title = "抢到后自动回复",
                summary = if (rules.autoReply.enabled) "成功后向来源会话发送消息" else "不自动回复",
                enabled = rules.autoReply.enabled,
                key = RuleKey.AUTO_REPLY,
                overriddenKeys = overriddenKeys,
                parentLabel = parentLabel,
                onActivate = onActivate,
                onReset = onReset,
                onEnabledChange = {
                    onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(enabled = it)))
                }
            )
            if (rules.autoReply.enabled) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    value = rules.autoReply.text,
                    enabled = replyEditable,
                    onValueChange = {
                        onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(text = it)))
                    },
                    label = { Text("回复内容") },
                    supportingText = { Text($$"使用 $amount 表示抢到的金额") },
                    singleLine = true
                )
            }

            AutomationSettingsError(validationError)
        }
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
        onEnabledChange: (Boolean) -> Unit
    ) {
        AutomationRuleHeader(
            title = title,
            summary = summary,
            enabled = enabled,
            isOverridden = overriddenKeys?.let { key in it },
            parentLabel = parentLabel,
            onActivate = { onActivate(key) },
            onReset = { onReset(key) },
            onEnabledChange = onEnabledChange
        )
    }

    private fun RuleSet.apply(overrides: RuleOverrides?): RuleSet {
        if (overrides == null) return this
        return copy(
            grab = overrides.grab ?: grab,
            grabSelf = overrides.grabSelf ?: grabSelf,
            timeRange = overrides.timeRange ?: timeRange,
            keyword = overrides.keyword ?: keyword,
            delay = overrides.delay ?: delay,
            notification = overrides.notification ?: notification,
            autoReply = overrides.autoReply ?: autoReply
        )
    }

    private fun RuleOverrides.keys(): Set<RuleKey> = buildSet {
        if (grab != null) add(RuleKey.GRAB)
        if (grabSelf != null) add(RuleKey.GRAB_SELF)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (keyword != null) add(RuleKey.KEYWORD)
        if (delay != null) add(RuleKey.DELAY)
        if (notification != null) add(RuleKey.NOTIFICATION)
        if (autoReply != null) add(RuleKey.AUTO_REPLY)
    }

    private fun RuleOverrides.withRule(key: RuleKey, rules: RuleSet): RuleOverrides = when (key) {
        RuleKey.GRAB -> copy(grab = rules.grab)
        RuleKey.GRAB_SELF -> copy(grabSelf = rules.grabSelf)
        RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
        RuleKey.KEYWORD -> copy(keyword = rules.keyword)
        RuleKey.DELAY -> copy(delay = rules.delay)
        RuleKey.NOTIFICATION -> copy(notification = rules.notification)
        RuleKey.AUTO_REPLY -> copy(autoReply = rules.autoReply)
    }

    private fun RuleOverrides.withoutRule(key: RuleKey): RuleOverrides = when (key) {
        RuleKey.GRAB -> copy(grab = null)
        RuleKey.GRAB_SELF -> copy(grabSelf = null)
        RuleKey.TIME_RANGE -> copy(timeRange = null)
        RuleKey.KEYWORD -> copy(keyword = null)
        RuleKey.DELAY -> copy(delay = null)
        RuleKey.NOTIFICATION -> copy(notification = null)
        RuleKey.AUTO_REPLY -> copy(autoReply = null)
    }

    private fun validate(rules: RuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys

        if (validates(RuleKey.DELAY) && rules.delay.enabled) {
            if (rules.delay.baseMs.toLongOrNull() == null) return "请输入有效的基础延迟"
            if (rules.delay.randomRangeMs.toLongOrNull() == null) return "请输入有效的随机偏移范围"
        }
        if (validates(RuleKey.KEYWORD)) {
            rules.keyword.validationError("关键词")?.let { return it }
        }
        if (validates(RuleKey.AUTO_REPLY) && rules.autoReply.enabled && rules.autoReply.text.isBlank()) {
            return "自动回复内容不能为空"
        }
        return null
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups())
            .distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(TAG, "failed to load contacts", it)
    }.getOrDefault(emptyList())

    private fun globalRules(): RuleSet = loadConfig().global

    private fun contactOverrides(wxId: String): RuleOverrides =
        loadConfig().contacts[wxId] ?: RuleOverrides()

    private fun groupMemberOverrides(groupId: String, memberId: String): RuleOverrides =
        loadConfig().groupMembers[groupId]?.get(memberId) ?: RuleOverrides()

    private fun memberOverridesCount(groupId: String): Int =
        loadConfig().groupMembers[groupId]?.count { !it.value.isEmpty() } ?: 0

    private fun setContactOverrides(wxId: String, overrides: RuleOverrides) {
        updateConfig { config ->
            val contacts = config.contacts.toMutableMap()
            if (overrides.isEmpty()) contacts.remove(wxId) else contacts[wxId] = overrides
            config.copy(contacts = contacts)
        }
    }

    private fun setGroupMemberOverrides(groupId: String, memberId: String, overrides: RuleOverrides) {
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

    private fun migrateLegacyConfig(): StoredConfig {
        val hasLegacyPrefs = LEGACY_PREF_KEYS.any(WePrefs::containsKey)
        val legacyUseWhitelist = hasLegacyPrefs &&
                WePrefs.getBoolOrDef("red_packet_use_whitelist", false)
        val legacySelectedContacts = if (!hasLegacyPrefs) {
            emptySet()
        } else if (legacyUseWhitelist) {
            WePrefs.getStringSetOrDef("red_packet_whitelist", emptySet())
        } else {
            WePrefs.getStringSetOrDef("red_packet_blacklist", emptySet())
        }
        val legacyDelayRange = WePrefs.getStringOrDef("red_packet_delay_random_range", "300")
        val legacyDelayBase = if (WePrefs.containsKey("red_packet_delay_custom")) {
            WePrefs.getStringOrDef("red_packet_delay_custom", "0")
        } else {
            "500"
        }
        val migratedDelayBase = if (
            legacyDelayRange.toLongOrNull() ?: 0L > 0L &&
            legacyDelayBase.toLongOrNull() ?: 0L <= 0L
        ) {
            "1000"
        } else {
            legacyDelayBase
        }
        val global = if (hasLegacyPrefs) {
            RuleSet(
                grab = AutomationToggleRule(enabled = !legacyUseWhitelist),
                grabSelf = AutomationToggleRule(WePrefs.getBoolOrDef("red_packet_self", false)),
                delay = DelayRule(
                    enabled = true,
                    baseMs = migratedDelayBase,
                    randomRangeMs = legacyDelayRange
                ),
                notification = AutomationToggleRule(WePrefs.getBoolOrDef("red_packet_notification", false)),
                autoReply = WePrefs.getStringOrDef("red_packet_auto_reply", "").let {
                    ReplyRule(enabled = it.isNotBlank(), text = it)
                }
            )
        } else {
            RuleSet()
        }

        val contacts = mutableMapOf<String, RuleOverrides>()
        if (hasLegacyPrefs) {
            legacySelectedContacts.forEach { wxId ->
                contacts[wxId] = RuleOverrides(grab = AutomationToggleRule(enabled = legacyUseWhitelist))
            }
        }

        val groupMembers = mutableMapOf<String, MutableMap<String, RuleOverrides>>()
        val legacyGroupRules = runCatching {
            if (!legacyGroupMemberFile.exists()) emptyList() else {
                DefaultJson.decodeFromString<List<LegacyGroupMemberRule>>(legacyGroupMemberFile.readText())
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to migrate $legacyGroupMemberFile", it)
        }.getOrDefault(emptyList())

        legacyGroupRules.forEach { rule ->
            if (!rule.groupId.isGroupChatWxId) return@forEach
            val conversationWasAllowed = if (legacyUseWhitelist) {
                rule.groupId in legacySelectedContacts
            } else {
                rule.groupId !in legacySelectedContacts
            }
            if (!conversationWasAllowed) return@forEach
            if (rule.useWhitelist) {
                val existing = contacts[rule.groupId] ?: RuleOverrides()
                contacts[rule.groupId] = existing.copy(grab = AutomationToggleRule(enabled = false))
            }
            val memberRules = groupMembers.getOrPut(rule.groupId) { mutableMapOf() }
            rule.members.filter(String::isNotBlank).forEach { memberId ->
                memberRules[memberId] = RuleOverrides(
                    grab = AutomationToggleRule(enabled = rule.useWhitelist)
                )
            }
        }

        if (hasLegacyPrefs || legacyGroupRules.isNotEmpty()) {
            WeLogger.i(TAG, "migrated legacy red packet settings")
        }
        return StoredConfig(
            global = global,
            contacts = contacts,
            groupMembers = groupMembers
        )
    }

    private const val MAX_DELAY_DIGITS = 7
    private val LEGACY_PREF_KEYS = listOf(
        "red_packet_notification",
        "red_packet_self",
        "red_packet_use_whitelist",
        "red_packet_whitelist",
        "red_packet_blacklist",
        "red_packet_delay_custom",
        "red_packet_delay_random_range",
        "red_packet_auto_reply"
    )
}
