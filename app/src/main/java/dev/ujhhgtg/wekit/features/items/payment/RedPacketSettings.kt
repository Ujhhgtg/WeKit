package dev.ujhhgtg.wekit.features.items.payment

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.items.AtomicJsonConfigStore
import dev.ujhhgtg.wekit.features.items.AutomationContactSettingsSelector
import dev.ujhhgtg.wekit.features.items.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.automationKeywordSummary
import dev.ujhhgtg.wekit.features.items.formatAutomationMinute
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
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
object RedPacketSettings {
    private const val TAG = "RedPacketSettings"
    private const val CONFIG_VERSION = 1
    private val RED_PACKET_KEYWORD_MODES =
        listOf(AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.REGEX)

    private val configFile by lazy { KnownPaths.moduleData / "red_packet_settings.json" }
    private val legacyGroupMemberFile by lazy { KnownPaths.moduleData / "red_packet_group_members.json" }

    @Serializable
    enum class ReceiveMode { NETWORK, CLICK }

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
        val receiveMode: ReceiveMode = ReceiveMode.NETWORK,
        val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
        val keyword: AutomationKeywordRule = AutomationKeywordRule(),
        val skipKeyword: AutomationKeywordRule = AutomationKeywordRule(),
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
        val receiveMode: ReceiveMode? = null,
        val timeRange: AutomationTimeRangeRule? = null,
        val keyword: AutomationKeywordRule? = null,
        val skipKeyword: AutomationKeywordRule? = null,
        val delay: DelayRule? = null,
        val notification: AutomationToggleRule? = null,
        val autoReply: ReplyRule? = null
    ) {
        fun isEmpty(): Boolean = overriddenCount() == 0

        fun overriddenCount(): Int = listOf(
            grab,
            grabSelf,
            receiveMode,
            timeRange,
            keyword,
            skipKeyword,
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
        RECEIVE_MODE,
        TIME_RANGE,
        KEYWORD,
        SKIP_KEYWORD,
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
                title = { Text(stringResource(R.string.feature_auto_open_red_packets_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_global_settings),
                                description = stringResource(R.string.payment_red_packet_global_summary),
                                onClick = { showGlobalDialog(context) },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_contact_settings),
                                description = stringResource(R.string.automation_contact_settings_summary),
                                onClick = { showContactSelector(context) },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    private fun showGlobalDialog(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            var draft by remember { mutableStateOf(globalRules()) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val validationError = validate(localizedContext, draft)

            val editMode = editText
            if (editMode != null) {
                PaymentTextEditDialog(editMode, onClose = { editText = null })
                return@showComposeDialog
            }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(stringResource(R.string.automation_global_settings)) },
                text = {
                    RuleSetEditor(
                        rules = draft,
                        overriddenKeys = null,
                        parentLabel = "",
                        onActivate = {},
                        onReset = {},
                        onChange = { _, updated -> draft = updated },
                        validationError = validationError,
                        onEditText = { editText = it },
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            updateConfig { it.copy(global = draft) }
                            showToast(localizedContext.getString(R.string.automation_global_settings_saved))
                            onDismiss()
                        }
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    private fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            AutomationContactSettingsSelector(
                title = stringResource(R.string.automation_contact_settings),
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val count = contactOverrides(contact.wxId).overriddenCount()
                    when {
                        contact.wxId.isGroupChatWxId && count > 0 ->
                            localizedContext.resources.getQuantityString(
                                R.plurals.automation_group_overrides,
                                count,
                                count,
                            )
                        contact.wxId.isGroupChatWxId -> localizedContext.getString(R.string.automation_group_settings)
                        count > 0 -> localizedContext.resources.getQuantityString(
                            R.plurals.automation_overrides,
                            count,
                            count,
                        )
                        else -> localizedContext.getString(R.string.automation_follow_global)
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
                            title = PaymentUiText.Raw(contact.displayName.ifBlank { contact.wxId }),
                            parentLabelRes = R.string.automation_global_settings,
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
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_group_global_settings),
                                description = if (groupOverrideCount == 0) {
                                    stringResource(R.string.automation_follow_global)
                                } else {
                                    pluralStringResource(
                                        R.plurals.automation_overrides,
                                        groupOverrideCount,
                                        groupOverrideCount,
                                    )
                                },
                                onClick = {
                                    showOverrideDialog(
                                        context = context,
                                        title = PaymentUiText.Resource(R.string.automation_group_global_settings),
                                        parentLabelRes = R.string.automation_global_settings,
                                        parent = globalRules(),
                                        initial = contactOverrides(groupId),
                                        onSave = {
                                            setContactOverrides(groupId, it)
                                            revision++
                                            onUpdated()
                                        }
                                    )
                                },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_group_member_settings),
                                description = if (memberCount == 0) {
                                    stringResource(R.string.automation_all_members_follow_group)
                                } else {
                                    pluralStringResource(
                                        R.plurals.automation_configured_members,
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
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    private fun showGroupMemberSelector(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val members = remember(groupId) {
                runCatching { WeDatabaseApi.getGroupMembers(groupId) }
                    .onFailure { WeLogger.e(TAG, "failed to load members of $groupId", it) }
                    .getOrDefault(emptyList())
            }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }

            AutomationContactSettingsSelector(
                title = stringResource(R.string.automation_group_member_settings_title, groupName),
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = groupMemberOverrides(groupId, member.wxId).overriddenCount()
                    if (count == 0) {
                        localizedContext.getString(R.string.automation_follow_group_global)
                    } else {
                        localizedContext.resources.getQuantityString(
                            R.plurals.automation_overrides,
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
                        title = PaymentUiText.Raw(member.displayName.ifBlank { member.wxId }),
                        parentLabelRes = R.string.automation_group_global_settings,
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
        title: PaymentUiText,
        @androidx.annotation.StringRes parentLabelRes: Int,
        parent: RuleSet,
        initial: RuleOverrides,
        onSave: (RuleOverrides) -> Unit
    ) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
            var draft by remember { mutableStateOf(initial) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val effective = parent.apply(draft)
            val validationError = validate(localizedContext, effective, draft.keys())

            val editMode = editText
            if (editMode != null) {
                PaymentTextEditDialog(editMode, onClose = { editText = null })
                return@showComposeDialog
            }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(title.resolve()) },
                text = {
                    RuleSetEditor(
                        rules = effective,
                        overriddenKeys = draft.keys(),
                        parentLabel = stringResource(parentLabelRes),
                        onActivate = { key -> draft = draft.withRule(key, effective) },
                        onReset = { key -> draft = draft.withoutRule(key) },
                        onChange = { key, updated -> draft = draft.withRule(key, updated) },
                        validationError = validationError,
                        onEditText = { editText = it },
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast(localizedContext.getString(R.string.settings_saved))
                            onDismiss()
                        }
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
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
        validationError: String?,
        onEditText: (PaymentTextEditMode) -> Unit
    ) {
        val isGlobalEditor = overriddenKeys == null

        fun overridden(key: RuleKey): Boolean? = overriddenKeys?.let { key in it }
        fun editable(key: RuleKey): Boolean = overriddenKeys == null || key in overriddenKeys

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                item(key = "grab") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_default_grab),
                        summary = if (isGlobalEditor && rules.grab.enabled) {
                            stringResource(R.string.payment_red_packet_default_all)
                        } else if (isGlobalEditor) {
                            stringResource(R.string.payment_red_packet_default_none)
                        } else if (rules.grab.enabled) {
                            stringResource(R.string.payment_red_packet_grab_scope)
                        } else {
                            stringResource(R.string.payment_red_packet_skip_scope)
                        },
                        checked = rules.grab.enabled,
                        overridden = overridden(RuleKey.GRAB),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.GRAB) },
                        onReset = { onReset(RuleKey.GRAB) },
                        onCheckedChange = {
                            onChange(RuleKey.GRAB, rules.copy(grab = rules.grab.copy(enabled = it)))
                        },
                    )
                }

                item(key = "grab_self") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_grab_self),
                        summary = stringResource(
                            if (rules.grabSelf.enabled) {
                                R.string.payment_red_packet_grab_self_enabled
                            } else {
                                R.string.payment_red_packet_grab_self_disabled
                            }
                        ),
                        checked = rules.grabSelf.enabled,
                        overridden = overridden(RuleKey.GRAB_SELF),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.GRAB_SELF) },
                        onReset = { onReset(RuleKey.GRAB_SELF) },
                        onCheckedChange = {
                            onChange(RuleKey.GRAB_SELF, rules.copy(grabSelf = rules.grabSelf.copy(enabled = it)))
                        },
                    )
                }

                item(key = "receive_mode") {
                    PaymentModeRuleRow(
                        title = stringResource(R.string.payment_red_packet_receive_mode),
                        summary = if (rules.receiveMode == ReceiveMode.NETWORK) {
                            stringResource(R.string.payment_red_packet_network_default)
                        } else {
                            stringResource(R.string.payment_red_packet_manual_click)
                        },
                        overridden = overridden(RuleKey.RECEIVE_MODE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.RECEIVE_MODE) },
                        onReset = { onReset(RuleKey.RECEIVE_MODE) },
                    )
                }
                ReceiveMode.entries.forEach { mode ->
                    item(key = "receive_mode_${mode.name}") {
                        RadioButtonWidget(
                            iconPlaceholder = false,
                            title = stringResource(
                                if (mode == ReceiveMode.NETWORK) {
                                    R.string.payment_red_packet_network
                                } else {
                                    R.string.payment_red_packet_manual_click
                                }
                            ),
                            selected = rules.receiveMode == mode,
                            enabled = editable(RuleKey.RECEIVE_MODE),
                            onClick = { onChange(RuleKey.RECEIVE_MODE, rules.copy(receiveMode = mode)) },
                        )
                    }
                }

                item(key = "time_range") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_time_range),
                        summary = if (rules.timeRange.enabled) {
                            "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                        } else {
                            stringResource(R.string.payment_red_packet_time_unrestricted)
                        },
                        checked = rules.timeRange.enabled,
                        overridden = overridden(RuleKey.TIME_RANGE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.TIME_RANGE) },
                        onReset = { onReset(RuleKey.TIME_RANGE) },
                        onCheckedChange = {
                            onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = rules.timeRange.copy(enabled = it)))
                        },
                    )
                }
                timeRangeItems(
                    rule = rules.timeRange,
                    editable = editable(RuleKey.TIME_RANGE),
                    visible = rules.timeRange.enabled,
                    onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) },
                )

                item(key = "keyword") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_keywords),
                        summary = automationKeywordSummary(
                            rules.keyword,
                            stringResource(R.string.payment_red_packet_keywords_unrestricted),
                        ),
                        checked = rules.keyword.enabled,
                        overridden = overridden(RuleKey.KEYWORD),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.KEYWORD) },
                        onReset = { onReset(RuleKey.KEYWORD) },
                        onCheckedChange = {
                            onChange(RuleKey.KEYWORD, rules.copy(keyword = rules.keyword.copy(enabled = it)))
                        },
                    )
                }
                keywordItems(
                    keyPrefix = "keyword",
                    rule = rules.keyword,
                    editable = editable(RuleKey.KEYWORD),
                    visible = rules.keyword.enabled,
                    modes = RED_PACKET_KEYWORD_MODES,
                    onChange = { onChange(RuleKey.KEYWORD, rules.copy(keyword = it)) },
                    onEditText = onEditText,
                )

                item(key = "skip_keyword") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_skip_keywords),
                        summary = automationKeywordSummary(
                            rules.skipKeyword,
                            stringResource(R.string.payment_red_packet_skip_keywords_unrestricted),
                        ),
                        checked = rules.skipKeyword.enabled,
                        overridden = overridden(RuleKey.SKIP_KEYWORD),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.SKIP_KEYWORD) },
                        onReset = { onReset(RuleKey.SKIP_KEYWORD) },
                        onCheckedChange = {
                            onChange(
                                RuleKey.SKIP_KEYWORD,
                                rules.copy(skipKeyword = rules.skipKeyword.copy(enabled = it)),
                            )
                        },
                    )
                }
                keywordItems(
                    keyPrefix = "skip_keyword",
                    rule = rules.skipKeyword,
                    editable = editable(RuleKey.SKIP_KEYWORD),
                    visible = rules.skipKeyword.enabled,
                    modes = RED_PACKET_KEYWORD_MODES,
                    onChange = { onChange(RuleKey.SKIP_KEYWORD, rules.copy(skipKeyword = it)) },
                    onEditText = onEditText,
                )

                item(key = "delay") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_delay),
                        summary = if (rules.delay.enabled) {
                            stringResource(
                                R.string.automation_delay_summary,
                                rules.delay.baseMs.ifBlank { "0" },
                                rules.delay.randomRangeMs.ifBlank { "0" },
                            )
                        } else {
                            stringResource(R.string.payment_red_packet_immediate)
                        },
                        checked = rules.delay.enabled,
                        overridden = overridden(RuleKey.DELAY),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.DELAY) },
                        onReset = { onReset(RuleKey.DELAY) },
                        onCheckedChange = {
                            onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(enabled = it)))
                        },
                    )
                }
                delayItems(
                    baseMs = rules.delay.baseMs,
                    randomRangeMs = rules.delay.randomRangeMs,
                    editable = editable(RuleKey.DELAY),
                    visible = rules.delay.enabled,
                    maxDigits = MAX_DELAY_DIGITS,
                    onBaseChange = {
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(baseMs = it)))
                    },
                    onRandomRangeChange = {
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(randomRangeMs = it)))
                    },
                    onEditText = onEditText,
                )

                item(key = "notification") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_notification),
                        summary = stringResource(
                            if (rules.notification.enabled) {
                                R.string.payment_red_packet_notification_enabled
                            } else {
                                R.string.automation_notification_disabled
                            }
                        ),
                        checked = rules.notification.enabled,
                        overridden = overridden(RuleKey.NOTIFICATION),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.NOTIFICATION) },
                        onReset = { onReset(RuleKey.NOTIFICATION) },
                        onCheckedChange = {
                            onChange(
                                RuleKey.NOTIFICATION,
                                rules.copy(notification = rules.notification.copy(enabled = it)),
                            )
                        },
                    )
                }

                item(key = "auto_reply") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_auto_reply),
                        summary = stringResource(
                            if (rules.autoReply.enabled) {
                                R.string.automation_auto_reply_enabled
                            } else {
                                R.string.automation_auto_reply_disabled
                            }
                        ),
                        checked = rules.autoReply.enabled,
                        overridden = overridden(RuleKey.AUTO_REPLY),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.AUTO_REPLY) },
                        onReset = { onReset(RuleKey.AUTO_REPLY) },
                        onCheckedChange = {
                            onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(enabled = it)))
                        },
                    )
                }
                item(key = "auto_reply_text", animatedVisibility = rules.autoReply.enabled) {
                    val replyTitle = stringResource(R.string.automation_reply_content)
                    val amountPlaceholder = stringResource(R.string.payment_red_packet_amount_placeholder)
                    PaymentValueRow(
                        title = replyTitle,
                        value = rules.autoReply.text,
                        enabled = editable(RuleKey.AUTO_REPLY),
                        valueHint = amountPlaceholder,
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = replyTitle,
                                    initial = rules.autoReply.text,
                                    supportingText = amountPlaceholder,
                                    onCommit = {
                                        onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(text = it)))
                                    },
                                )
                            )
                        },
                    )
                }

                if (validationError != null) {
                    item(key = "validation_error") { PaymentErrorRow(validationError) }
                }
            }
        }
    }

    private fun RuleSet.apply(overrides: RuleOverrides?): RuleSet {
        if (overrides == null) return this
        return copy(
            grab = overrides.grab ?: grab,
            grabSelf = overrides.grabSelf ?: grabSelf,
            receiveMode = overrides.receiveMode ?: receiveMode,
            timeRange = overrides.timeRange ?: timeRange,
            keyword = overrides.keyword ?: keyword,
            skipKeyword = overrides.skipKeyword ?: skipKeyword,
            delay = overrides.delay ?: delay,
            notification = overrides.notification ?: notification,
            autoReply = overrides.autoReply ?: autoReply
        )
    }

    private fun RuleOverrides.keys(): Set<RuleKey> = buildSet {
        if (grab != null) add(RuleKey.GRAB)
        if (grabSelf != null) add(RuleKey.GRAB_SELF)
        if (receiveMode != null) add(RuleKey.RECEIVE_MODE)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (keyword != null) add(RuleKey.KEYWORD)
        if (skipKeyword != null) add(RuleKey.SKIP_KEYWORD)
        if (delay != null) add(RuleKey.DELAY)
        if (notification != null) add(RuleKey.NOTIFICATION)
        if (autoReply != null) add(RuleKey.AUTO_REPLY)
    }

    private fun RuleOverrides.withRule(key: RuleKey, rules: RuleSet): RuleOverrides = when (key) {
        RuleKey.GRAB -> copy(grab = rules.grab)
        RuleKey.GRAB_SELF -> copy(grabSelf = rules.grabSelf)
        RuleKey.RECEIVE_MODE -> copy(receiveMode = rules.receiveMode)
        RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
        RuleKey.KEYWORD -> copy(keyword = rules.keyword)
        RuleKey.SKIP_KEYWORD -> copy(skipKeyword = rules.skipKeyword)
        RuleKey.DELAY -> copy(delay = rules.delay)
        RuleKey.NOTIFICATION -> copy(notification = rules.notification)
        RuleKey.AUTO_REPLY -> copy(autoReply = rules.autoReply)
    }

    private fun RuleOverrides.withoutRule(key: RuleKey): RuleOverrides = when (key) {
        RuleKey.GRAB -> copy(grab = null)
        RuleKey.GRAB_SELF -> copy(grabSelf = null)
        RuleKey.RECEIVE_MODE -> copy(receiveMode = null)
        RuleKey.TIME_RANGE -> copy(timeRange = null)
        RuleKey.KEYWORD -> copy(keyword = null)
        RuleKey.SKIP_KEYWORD -> copy(skipKeyword = null)
        RuleKey.DELAY -> copy(delay = null)
        RuleKey.NOTIFICATION -> copy(notification = null)
        RuleKey.AUTO_REPLY -> copy(autoReply = null)
    }

    private fun validate(context: Context, rules: RuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys

        if (validates(RuleKey.DELAY) && rules.delay.enabled) {
            if (rules.delay.baseMs.toLongOrNull() == null) {
                return context.getString(R.string.automation_invalid_base_delay)
            }
            if (rules.delay.randomRangeMs.toLongOrNull() == null) {
                return context.getString(R.string.automation_invalid_random_delay)
            }
        }
        if (validates(RuleKey.KEYWORD)) {
            rules.keyword.validationError(context.getString(R.string.payment_red_packet_keyword_label))?.let { return it }
        }
        if (validates(RuleKey.SKIP_KEYWORD)) {
            rules.skipKeyword.validationError(context.getString(R.string.payment_red_packet_skip_keyword_label))?.let { return it }
        }
        if (validates(RuleKey.AUTO_REPLY) && rules.autoReply.enabled && rules.autoReply.text.isBlank()) {
            return context.getString(R.string.automation_reply_required)
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
