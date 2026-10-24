package dev.ujhhgtg.wekit.features.items.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Group
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationFieldReader
import dev.ujhhgtg.wekit.features.automation.AutomationFieldWriter
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSection
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.AutomationValueCodec
import dev.ujhhgtg.wekit.ui.automation.AutomationEditorContext
import dev.ujhhgtg.wekit.ui.automation.AutomationFieldLabel
import dev.ujhhgtg.wekit.ui.automation.AutomationInlinePadding
import dev.ujhhgtg.wekit.ui.automation.AutomationMiniChips
import dev.ujhhgtg.wekit.ui.automation.AutomationPick
import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import dev.ujhhgtg.wekit.ui.automation.AutomationSheetNote
import dev.ujhhgtg.wekit.ui.automation.choiceEditor
import dev.ujhhgtg.wekit.ui.automation.isGroupScope
import dev.ujhhgtg.wekit.ui.automation.loadGroupMembers
import dev.ujhhgtg.wekit.ui.automation.switchInline
import dev.ujhhgtg.wekit.ui.automation.timeRangeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor
import dev.ujhhgtg.wekit.ui.content.IconButton
import java.io.File

enum class NotifyMode {
    NOTIFY,
    DND,
}

/**
 * Notification sound; [ringtone] is a file path in module storage, empty for the system default.
 */
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
data class SenderFilterRule(
    val only: List<String> = emptyList(),
    val block: List<String> = emptyList(),
) {
    fun allows(senderId: String, senderName: String, content: String): Boolean {
        if (only.isNotEmpty() && only.none { matches(it, senderId, senderName, content) }) {
            return false
        }
        return block.none { matches(it, senderId, senderName, content) }
    }

    private fun matches(
        token: String,
        senderId: String,
        senderName: String,
        content: String,
    ): Boolean {
        val key = token.trim().lowercase()
        if (key.isEmpty()) return false

        fun overlaps(value: String) =
            value.isNotEmpty() && (value.contains(key) || key.contains(value))
        val text = content.lowercase()
        return overlaps(senderId.lowercase()) ||
            overlaps(senderName.lowercase()) ||
            text.startsWith("$key:") ||
            text.startsWith("$key：")
    }
}

/**
 * A voice message's content ends in `…:duration:0` while unplayed; returns it with `:1`, or null.
 */
fun playedVoiceContent(content: String): String? {
    val lineEnding =
        when {
            content.endsWith("\r\n") -> "\r\n"
            content.endsWith("\n") -> "\n"
            else -> ""
        }
    val body = content.dropLast(lineEnding.length)
    val markerEnd = if (body.endsWith(":")) body.length - 1 else body.length
    val markerStart = body.lastIndexOf(':', markerEnd - 1) + 1
    if (markerStart <= 0 || body.substring(markerStart, markerEnd) != "0") return null
    val durationEnd = markerStart - 1
    val durationStart = body.lastIndexOf(':', durationEnd - 1) + 1
    if (
        durationStart >= durationEnd ||
            body.substring(durationStart, durationEnd).toLongOrNull() == null
    ) {
        return null
    }
    return body.substring(0, markerStart) + "1" + body.substring(markerEnd) + lineEnding
}

/** Rules of [CustomNotifications] on the scope hierarchy (GLOBAL → chat kind → chat). */
object CustomNotificationsAutomation : AutomationSpec("CUSTOM_NOTIFICATIONS") {
    private val soundCodec =
        object : AutomationValueCodec<NotifySoundRule> {
            override fun write(value: NotifySoundRule, out: AutomationFieldWriter) {
                out.put("enabled", value.enabled)
                out.put("ringtone", value.ringtone)
            }

            override fun read(input: AutomationFieldReader) =
                NotifySoundRule(input.boolean("enabled"), input.string("ringtone"))
        }

    private val quickReplyCodec =
        object : AutomationValueCodec<QuickReplyRule> {
            override fun write(value: QuickReplyRule, out: AutomationFieldWriter) {
                out.put("enabled", value.enabled)
                out.put("asQuote", value.asQuote)
            }

            override fun read(input: AutomationFieldReader) =
                QuickReplyRule(input.boolean("enabled"), input.boolean("asQuote"))
        }

    private val mentionCodec =
        object : AutomationValueCodec<MentionBlockRule> {
            override fun write(value: MentionBlockRule, out: AutomationFieldWriter) {
                out.put("blockAtAll", value.blockAtAll)
                out.put("blockAtMe", value.blockAtMe)
            }

            override fun read(input: AutomationFieldReader) =
                MentionBlockRule(input.boolean("blockAtAll"), input.boolean("blockAtMe"))
        }

    private val senderCodec =
        object : AutomationValueCodec<SenderFilterRule> {
            override fun write(value: SenderFilterRule, out: AutomationFieldWriter) {
                out.put("present", true)
                out.putList("only", value.only)
                out.putList("block", value.block)
            }

            override fun read(input: AutomationFieldReader) =
                SenderFilterRule(input.list("only"), input.list("block"))
        }

    /** Rules only act where enabled; like Hchat's default rules, nothing is enabled by default. */
    val enabled = AutomationRuleKey("enabled", AutomationCodecs.toggle, AutomationToggleRule())
    val mode =
        AutomationRuleKey("mode", AutomationCodecs.enumValue<NotifyMode>(), NotifyMode.NOTIFY)

    /** Replace WeChat's notification with WeKit's (sound, vibration, actions, merging, detail). */
    val takeOver =
        AutomationRuleKey("takeOver", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val sound = AutomationRuleKey("sound", soundCodec, NotifySoundRule())
    val vibrate =
        AutomationRuleKey("vibrate", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val markRead =
        AutomationRuleKey("markRead", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val quickReply = AutomationRuleKey("quickReply", quickReplyCodec, QuickReplyRule())
    val merge = AutomationRuleKey("merge", AutomationCodecs.toggle, AutomationToggleRule())
    val showDetail =
        AutomationRuleKey(
            "showDetail",
            AutomationCodecs.toggle,
            AutomationToggleRule(enabled = true),
        )
    val ignoreWechatDnd =
        AutomationRuleKey("ignoreWechatDnd", AutomationCodecs.toggle, AutomationToggleRule())
    val muteWindow =
        AutomationRuleKey(
            "muteWindow",
            AutomationCodecs.timeRange,
            AutomationTimeRangeRule(startSecond = 23 * 3600, endSecond = 7 * 3600),
        )
    val mentions = AutomationRuleKey("mentions", mentionCodec, MentionBlockRule())
    val senders = AutomationRuleKey("senders", senderCodec, SenderFilterRule())

    override val keys =
        listOf(
            enabled,
            mode,
            takeOver,
            sound,
            vibrate,
            markRead,
            quickReply,
            merge,
            showDetail,
            ignoreWechatDnd,
            muteWindow,
            mentions,
            senders,
        )
    override val scopeTypes =
        setOf(
            AutomationScopeType.GLOBAL,
            AutomationScopeType.CATEGORY,
            AutomationScopeType.CONTACT,
            AutomationScopeType.PRESET,
        )
    override val contactKinds = AutomationTalkerCategory.entries.toSet()
    override val titleRes = R.string.feature_custom_notifications_name
    override val globalSummaryRes = R.string.custom_notify_global_summary

    private val groupOnly = { scope: AutomationScope -> scope.isGroupScope() }

    override val editors =
        listOf(
            toggleEditor(
                enabled,
                R.string.custom_notify_enabled,
                R.string.custom_notify_enabled_on,
                R.string.custom_notify_enabled_off,
            ),
            choiceEditor(
                mode,
                R.string.custom_notify_mode,
                listOf(
                    NotifyMode.NOTIFY to R.string.custom_notify_mode_notify,
                    NotifyMode.DND to R.string.custom_notify_mode_dnd,
                ),
            ),
            toggleEditor(
                takeOver,
                R.string.custom_notify_take_over,
                R.string.custom_notify_take_over_on,
                R.string.custom_notify_take_over_off,
            ),
            AutomationRuleEditor(
                key = sound,
                titleRes = R.string.custom_notify_sound,
                summary = { rule ->
                    when {
                        !rule.enabled -> {
                            stringResource(R.string.custom_notify_sound_off)
                        }

                        rule.ringtone.isEmpty() -> {
                            stringResource(R.string.custom_notify_ringtone_default)
                        }

                        else -> {
                            ringtoneName(rule.ringtone)
                        }
                    }
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                sheet = { ctx, value, onChange -> RingtoneSheet(ctx, value, onChange) },
            ),
            toggleEditor(
                vibrate,
                R.string.custom_notify_vibrate,
                R.string.custom_notify_vibrate_on,
                R.string.custom_notify_vibrate_off,
            ),
            toggleEditor(
                markRead,
                R.string.custom_notify_mark_read,
                R.string.custom_notify_mark_read_on,
                R.string.custom_notify_action_off,
            ),
            AutomationRuleEditor(
                key = quickReply,
                titleRes = R.string.custom_notify_quick_reply,
                summary = { rule ->
                    stringResource(
                        when {
                            !rule.enabled -> R.string.custom_notify_action_off
                            rule.asQuote -> R.string.custom_notify_quick_reply_quote
                            else -> R.string.custom_notify_quick_reply_on
                        },
                    )
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                inline =
                    switchInline(
                        R.string.automation_reply_as_quote,
                        { it.asQuote },
                        { v, b -> v.copy(asQuote = b) },
                    ),
            ),
            toggleEditor(
                merge,
                R.string.custom_notify_merge,
                R.string.custom_notify_merge_on,
                R.string.custom_notify_merge_off,
            ),
            toggleEditor(
                showDetail,
                R.string.custom_notify_show_detail,
                R.string.custom_notify_show_detail_on,
                R.string.custom_notify_show_detail_off,
            ),
            toggleEditor(
                ignoreWechatDnd,
                R.string.custom_notify_ignore_dnd,
                R.string.custom_notify_ignore_dnd_on,
                R.string.custom_notify_ignore_dnd_off,
            ),
            timeRangeEditor(
                muteWindow,
                R.string.custom_notify_mute_window,
                R.string.custom_notify_mute_window_off,
            ),
            AutomationRuleEditor(
                key = mentions,
                titleRes = R.string.custom_notify_mentions,
                summary = { rule ->
                    when {
                        rule.blockAtAll && rule.blockAtMe -> {
                            stringResource(R.string.custom_notify_mentions_both)
                        }

                        rule.blockAtAll -> {
                            stringResource(R.string.custom_notify_mentions_all)
                        }

                        rule.blockAtMe -> {
                            stringResource(R.string.custom_notify_mentions_me)
                        }

                        else -> {
                            stringResource(R.string.custom_notify_mentions_off)
                        }
                    }
                },
                inline = { _, value, onChange -> MentionChips(value, onChange) },
                visibleIn = groupOnly,
            ),
            AutomationRuleEditor(
                key = senders,
                titleRes = R.string.custom_notify_senders,
                summary = { rule ->
                    if (rule.only.isEmpty() && rule.block.isEmpty()) {
                        stringResource(R.string.custom_notify_senders_off)
                    } else {
                        pluralStringResource(
                            R.plurals.custom_notify_senders_count,
                            rule.only.size + rule.block.size,
                            rule.only.size + rule.block.size,
                        )
                    }
                },
                summaryExtra = { rule -> AutomationMiniChips(rule.only + rule.block) },
                sheet = { ctx, value, onChange ->
                    SenderTokens(ctx, R.string.custom_notify_senders_only, value.only) {
                        onChange(value.copy(only = it))
                    }
                    SenderTokens(ctx, R.string.custom_notify_senders_block, value.block) {
                        onChange(value.copy(block = it))
                    }
                    AutomationSheetNote(stringResource(R.string.custom_notify_senders_hint))
                },
                visibleIn = groupOnly,
            ),
        )

    override val sections =
        listOf(
            AutomationSection(R.string.custom_notify_section_mode, listOf(enabled, mode, takeOver)),
            AutomationSection(
                R.string.custom_notify_section_alerts,
                listOf(sound, vibrate, ignoreWechatDnd, muteWindow),
            ),
            AutomationSection(
                R.string.custom_notify_section_content,
                listOf(showDetail, merge, markRead, quickReply),
            ),
            AutomationSection(R.string.custom_notify_section_groups, listOf(mentions, senders)),
        )

    private fun ringtoneName(path: String) = File(path).name.substringAfter('_')

    @Composable
    private fun MentionChips(value: MentionBlockRule, onChange: (MentionBlockRule) -> Unit) {
        FlowRow(
            Modifier.padding(AutomationInlinePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                    Triple(R.string.custom_notify_block_at_all, value.blockAtAll) { on: Boolean ->
                        value.copy(blockAtAll = on)
                    },
                    Triple(R.string.custom_notify_block_at_me, value.blockAtMe) { on: Boolean ->
                        value.copy(blockAtMe = on)
                    },
                )
                .forEach { (labelRes, on, set) ->
                    FilterChip(
                        selected = on,
                        onClick = { onChange(set(!on)) },
                        label = { Text(stringResource(labelRes)) },
                        leadingIcon =
                            if (on) {
                                {
                                    Icon(
                                        MaterialSymbols.Outlined.Check,
                                        null,
                                        Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
        }
    }

    @Composable
    private fun RingtoneSheet(
        ctx: AutomationEditorContext,
        value: NotifySoundRule,
        onChange: (NotifySoundRule) -> Unit,
    ) {
        Column {
            listOf(true, false).forEach { system ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = (value.ringtone.isEmpty()) == system,
                        onClick = {
                            if (system) {
                                onChange(value.copy(ringtone = ""))
                            } else {
                                ctx.pickFiles(arrayOf("audio/*")) { paths ->
                                    paths.firstOrNull()?.let { onChange(value.copy(ringtone = it)) }
                                }
                            }
                        },
                    )
                    Column {
                        Text(
                            stringResource(
                                if (system) {
                                    R.string.custom_notify_ringtone_default
                                } else {
                                    R.string.custom_notify_ringtone_custom
                                },
                            ),
                        )
                        if (!system && value.ringtone.isNotEmpty()) {
                            Text(
                                ringtoneName(value.ringtone),
                                style =
                                    androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        if (value.ringtone.isNotEmpty()) {
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    ctx.pickFiles(arrayOf("audio/*")) { paths ->
                        paths.firstOrNull()?.let { onChange(value.copy(ringtone = it)) }
                    }
                },
            ) {
                Text(stringResource(R.string.custom_notify_ringtone_change))
            }
        }
        AutomationSheetNote(stringResource(R.string.custom_notify_ringtone_copied))
    }

    /** Token chips with a text field; for a group chat, members can also be picked. */
    @Composable
    private fun SenderTokens(
        ctx: AutomationEditorContext,
        labelRes: Int,
        tokens: List<String>,
        onChange: (List<String>) -> Unit,
    ) {
        var pending by remember { mutableStateOf("") }
        val add = {
            val token = pending.trim()
            if (token.isNotEmpty() && token !in tokens) onChange(tokens + token)
            pending = ""
        }
        val scope = ctx.scope
        val groupId =
            scope.scopeId.takeIf {
                scope.type == AutomationScopeType.CONTACT &&
                    AutomationTalkerCategory.of(it) == AutomationTalkerCategory.GROUP
            }
        val pickTitle = stringResource(labelRes)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomationFieldLabel(stringResource(labelRes))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tokens.forEach { token ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(tokens - token) },
                        label = { Text(token) },
                        trailingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Close,
                                stringResource(R.string.action_delete),
                                Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
                if (groupId != null) {
                    AssistChip(
                        onClick = {
                            val members = loadGroupMembers(groupId)
                            val memberIds = members.map { it.wxId }.toSet()
                            ctx.picker.open(
                                AutomationPick.Contacts(
                                    title = pickTitle,
                                    loadContacts = { members },
                                    initialSelected = tokens.filter { it in memberIds }.toSet(),
                                    onConfirm = { picked ->
                                        onChange(tokens.filterNot { it in memberIds } + picked)
                                    },
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.custom_notify_pick_members)) },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Group,
                                null,
                                Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                value = pending,
                onValueChange = { pending = it },
                label = { Text(stringResource(R.string.custom_notify_sender_input)) },
                trailingIcon = {
                    IconButton(enabled = pending.isNotBlank(), onClick = add) {
                        Icon(
                            MaterialSymbols.Outlined.Add,
                            stringResource(R.string.automation_add_item),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
