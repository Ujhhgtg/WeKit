package dev.ujhhgtg.wekit.features.items.notifications

import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationFieldReader
import dev.ujhhgtg.wekit.features.automation.AutomationFieldWriter
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.AutomationValueCodec
import dev.ujhhgtg.wekit.ui.automation.AutomationEditorDetails
import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import dev.ujhhgtg.wekit.ui.automation.AutomationValueRow
import dev.ujhhgtg.wekit.ui.automation.choiceEditor
import dev.ujhhgtg.wekit.ui.automation.isGroupScope
import dev.ujhhgtg.wekit.ui.automation.textDetails
import dev.ujhhgtg.wekit.ui.automation.timeRangeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import java.io.File

enum class NotifyMode { NOTIFY, DND }

/** Notification sound; [ringtone] is a file path in module storage, empty for the system default. */
data class NotifySoundRule(val enabled: Boolean = true, val ringtone: String = "")

data class QuickReplyRule(val enabled: Boolean = false, val asQuote: Boolean = false)

/** Suppresses group notifications that mention everyone and/or me. */
data class MentionBlockRule(val blockAtAll: Boolean = false, val blockAtMe: Boolean = false) {
    fun blocks(atAll: Boolean, atMe: Boolean) = (blockAtAll && atAll) || (blockAtMe && atMe)
}

/**
 * Group member filter. A token matches when the sender's wxid or display name contains it (or is
 * contained in it), or when the message text starts with `token:` (Hchat's member rule syntax).
 */
data class SenderFilterRule(val only: List<String> = emptyList(), val block: List<String> = emptyList()) {
    fun allows(senderId: String, senderName: String, content: String): Boolean {
        if (only.isNotEmpty() && only.none { matches(it, senderId, senderName, content) }) return false
        return block.none { matches(it, senderId, senderName, content) }
    }

    private fun matches(token: String, senderId: String, senderName: String, content: String): Boolean {
        val key = token.trim().lowercase()
        if (key.isEmpty()) return false
        fun overlaps(value: String) = value.isNotEmpty() && (value.contains(key) || key.contains(value))
        val text = content.lowercase()
        return overlaps(senderId.lowercase()) || overlaps(senderName.lowercase()) ||
                text.startsWith("$key:") || text.startsWith("$key：")
    }
}

/** Rules of [CustomNotifications] on the scope hierarchy (GLOBAL → chat kind → chat). */
object CustomNotificationsAutomation : AutomationSpec("CUSTOM_NOTIFICATIONS") {
    private val soundCodec = object : AutomationValueCodec<NotifySoundRule> {
        override fun write(value: NotifySoundRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("ringtone", value.ringtone)
        }

        override fun read(input: AutomationFieldReader) = NotifySoundRule(input.boolean("enabled"), input.string("ringtone"))
    }

    private val quickReplyCodec = object : AutomationValueCodec<QuickReplyRule> {
        override fun write(value: QuickReplyRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("asQuote", value.asQuote)
        }

        override fun read(input: AutomationFieldReader) = QuickReplyRule(input.boolean("enabled"), input.boolean("asQuote"))
    }

    private val mentionCodec = object : AutomationValueCodec<MentionBlockRule> {
        override fun write(value: MentionBlockRule, out: AutomationFieldWriter) {
            out.put("blockAtAll", value.blockAtAll)
            out.put("blockAtMe", value.blockAtMe)
        }

        override fun read(input: AutomationFieldReader) = MentionBlockRule(input.boolean("blockAtAll"), input.boolean("blockAtMe"))
    }

    private val senderCodec = object : AutomationValueCodec<SenderFilterRule> {
        override fun write(value: SenderFilterRule, out: AutomationFieldWriter) {
            out.put("present", true)
            out.putList("only", value.only)
            out.putList("block", value.block)
        }

        override fun read(input: AutomationFieldReader) = SenderFilterRule(input.list("only"), input.list("block"))
    }

    val mode = AutomationRuleKey("mode", AutomationCodecs.enumValue<NotifyMode>(), NotifyMode.NOTIFY)

    /** Replace WeChat's notification with WeKit's (sound, vibration, actions, merging, detail). */
    val takeOver = AutomationRuleKey("takeOver", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val sound = AutomationRuleKey("sound", soundCodec, NotifySoundRule())
    val vibrate = AutomationRuleKey("vibrate", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val markRead = AutomationRuleKey("markRead", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val quickReply = AutomationRuleKey("quickReply", quickReplyCodec, QuickReplyRule())
    val merge = AutomationRuleKey("merge", AutomationCodecs.toggle, AutomationToggleRule())
    val showDetail = AutomationRuleKey("showDetail", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val ignoreWechatDnd = AutomationRuleKey("ignoreWechatDnd", AutomationCodecs.toggle, AutomationToggleRule())
    val muteWindow = AutomationRuleKey(
        "muteWindow", AutomationCodecs.timeRange, AutomationTimeRangeRule(startMinute = 23 * 60, endMinute = 7 * 60),
    )
    val mentions = AutomationRuleKey("mentions", mentionCodec, MentionBlockRule())
    val senders = AutomationRuleKey("senders", senderCodec, SenderFilterRule())

    override val keys = listOf(mode, takeOver, sound, vibrate, markRead, quickReply, merge, showDetail, ignoreWechatDnd, muteWindow, mentions, senders)
    override val scopeTypes = setOf(
        AutomationScopeType.GLOBAL, AutomationScopeType.CATEGORY, AutomationScopeType.CONTACT, AutomationScopeType.PRESET,
    )
    override val contactKinds = AutomationTalkerCategory.entries.toSet()
    override val titleRes = R.string.feature_custom_notifications_name
    override val globalSummaryRes = R.string.custom_notify_global_summary

    private val groupOnly = { scope: dev.ujhhgtg.wekit.features.automation.AutomationScope -> scope.isGroupScope() }

    override val editors = listOf(
        choiceEditor(
            mode, R.string.custom_notify_mode,
            listOf(NotifyMode.NOTIFY to R.string.custom_notify_mode_notify, NotifyMode.DND to R.string.custom_notify_mode_dnd),
        ),
        toggleEditor(takeOver, R.string.custom_notify_take_over, R.string.custom_notify_take_over_on, R.string.custom_notify_take_over_off),
        AutomationRuleEditor(
            key = sound,
            titleRes = R.string.custom_notify_sound,
            summary = { rule ->
                when {
                    !rule.enabled -> stringResource(R.string.custom_notify_sound_off)
                    rule.ringtone.isEmpty() -> stringResource(R.string.custom_notify_ringtone_default)
                    else -> File(rule.ringtone).name.substringAfter('_')
                }
            },
            isOn = { it.enabled },
            setOn = { rule, on -> rule.copy(enabled = on) },
            details = ringtoneDetails(),
        ),
        toggleEditor(vibrate, R.string.custom_notify_vibrate, R.string.custom_notify_vibrate_on, R.string.custom_notify_vibrate_off),
        toggleEditor(markRead, R.string.custom_notify_mark_read, R.string.custom_notify_mark_read_on, R.string.custom_notify_action_off),
        AutomationRuleEditor(
            key = quickReply,
            titleRes = R.string.custom_notify_quick_reply,
            summary = { rule ->
                stringResource(
                    when {
                        !rule.enabled -> R.string.custom_notify_action_off
                        rule.asQuote -> R.string.custom_notify_quick_reply_quote
                        else -> R.string.custom_notify_quick_reply_on
                    }
                )
            },
            isOn = { it.enabled },
            setOn = { rule, on -> rule.copy(enabled = on) },
            details = switchDetails("quick_reply_quote", R.string.automation_reply_as_quote, { it.asQuote }, { v, b -> v.copy(asQuote = b) }),
        ),
        toggleEditor(merge, R.string.custom_notify_merge, R.string.custom_notify_merge_on, R.string.custom_notify_merge_off),
        toggleEditor(showDetail, R.string.custom_notify_show_detail, R.string.custom_notify_show_detail_on, R.string.custom_notify_show_detail_off),
        toggleEditor(
            ignoreWechatDnd, R.string.custom_notify_ignore_dnd, R.string.custom_notify_ignore_dnd_on, R.string.custom_notify_ignore_dnd_off,
        ),
        timeRangeEditor(muteWindow, R.string.custom_notify_mute_window, R.string.custom_notify_mute_window_off),
        AutomationRuleEditor(
            key = mentions,
            titleRes = R.string.custom_notify_mentions,
            summary = { rule ->
                when {
                    rule.blockAtAll && rule.blockAtMe -> stringResource(R.string.custom_notify_mentions_both)
                    rule.blockAtAll -> stringResource(R.string.custom_notify_mentions_all)
                    rule.blockAtMe -> stringResource(R.string.custom_notify_mentions_me)
                    else -> stringResource(R.string.custom_notify_mentions_off)
                }
            },
            details = { ctx, value, editable, onChange ->
                switchDetails<MentionBlockRule>("mentions_all", R.string.custom_notify_block_at_all, { it.blockAtAll }, { v, b -> v.copy(blockAtAll = b) })(
                    ctx, value, editable, onChange,
                )
                switchDetails<MentionBlockRule>("mentions_me", R.string.custom_notify_block_at_me, { it.blockAtMe }, { v, b -> v.copy(blockAtMe = b) })(
                    ctx, value, editable, onChange,
                )
            },
            visibleIn = groupOnly,
        ),
        AutomationRuleEditor(
            key = senders,
            titleRes = R.string.custom_notify_senders,
            summary = { rule ->
                if (rule.only.isEmpty() && rule.block.isEmpty()) stringResource(R.string.custom_notify_senders_off)
                else pluralStringResource(R.plurals.custom_notify_senders_count, rule.only.size + rule.block.size, rule.only.size + rule.block.size)
            },
            details = { ctx, value, editable, onChange ->
                textDetails<SenderFilterRule>(
                    "senders_only", R.string.custom_notify_senders_only,
                    get = { it.only.joinToString("\n") }, set = { v, s -> v.copy(only = s.tokens()) }, singleLine = false,
                )(ctx, value, editable, onChange)
                textDetails<SenderFilterRule>(
                    "senders_block", R.string.custom_notify_senders_block,
                    get = { it.block.joinToString("\n") }, set = { v, s -> v.copy(block = s.tokens()) }, singleLine = false,
                )(ctx, value, editable, onChange)
            },
            visibleIn = groupOnly,
        ),
    )

    private fun String.tokens() = split('\n', ',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty).distinct()

    private fun <T : Any> switchDetails(
        itemKey: String,
        @StringRes titleRes: Int,
        get: (T) -> Boolean,
        set: (T, Boolean) -> T,
    ): AutomationEditorDetails<T> = { _, value, editable, onChange ->
        item(key = itemKey) {
            SwitchWidget(
                iconPlaceholder = false,
                title = stringResource(titleRes),
                checked = get(value),
                enabled = editable,
                onCheckedChange = { onChange(set(value, it)) },
            )
        }
    }

    private fun ringtoneDetails(): AutomationEditorDetails<NotifySoundRule> = { ctx, value, editable, onChange ->
        item(key = "ringtone") {
            AutomationValueRow(
                title = stringResource(R.string.custom_notify_ringtone),
                value = value.ringtone.takeIf(String::isNotEmpty)?.let { File(it).name.substringAfter('_') }
                    ?: stringResource(R.string.custom_notify_ringtone_default),
                enabled = editable,
                onClick = {
                    ctx.pickFiles(arrayOf("audio/*")) { paths ->
                        paths.firstOrNull()?.let { onChange(value.copy(ringtone = it)) }
                    }
                },
            )
        }
        item(key = "ringtone_reset") {
            AutomationValueRow(
                title = stringResource(R.string.custom_notify_ringtone_use_default),
                value = "",
                enabled = editable && value.ringtone.isNotEmpty(),
                onClick = { onChange(value.copy(ringtone = "")) },
            )
        }
    }
}
