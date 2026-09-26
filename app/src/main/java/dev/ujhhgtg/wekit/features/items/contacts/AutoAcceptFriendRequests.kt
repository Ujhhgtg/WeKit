package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.automation.AutomationSettingsActivity
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationFieldReader
import dev.ujhhgtg.wekit.features.automation.AutomationFieldWriter
import dev.ujhhgtg.wekit.features.automation.AutomationLimitRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyContext
import dev.ujhhgtg.wekit.features.automation.AutomationReplyExecutor
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.AutomationValueCodec
import dev.ujhhgtg.wekit.features.automation.IncomingMessageEvents
import dev.ujhhgtg.wekit.features.automation.NewFriendEvents
import dev.ujhhgtg.wekit.features.automation.NewFriendRequest
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.automation.AutomationNavigationRow
import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import dev.ujhhgtg.wekit.ui.automation.limitEditor
import dev.ujhhgtg.wekit.ui.automation.replyEditor
import dev.ujhhgtg.wekit.ui.automation.textDetails
import dev.ujhhgtg.wekit.ui.automation.toggleEditor
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** Labels applied to a new friend; entries are templates (`$nickname`, `$time`). Missing labels are created. */
data class FriendLabelRule(
    val enabled: Boolean = false,
    val labels: List<String> = emptyList(),
    val timeFormat: String = "yyyy-MM-dd",
)

/** Remark template (`$nickname`, `$time`) set on a new friend. */
data class FriendRemarkRule(
    val enabled: Boolean = false,
    val template: String = $$"$nickname",
    val timeFormat: String = "yyMMdd",
)

/**
 * Two fixed pipelines sharing one shape: [ACCEPT] answers incoming requests, [ACCEPTED] runs when
 * someone accepts a request I sent.
 */
object FriendAutomation : AutomationSpec("FRIEND_AUTOMATION") {
    const val ACCEPT = "ACCEPT"
    const val ACCEPTED = "ACCEPTED"

    private val labelCodec = object : AutomationValueCodec<FriendLabelRule> {
        override fun write(value: FriendLabelRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("timeFormat", value.timeFormat)
            out.putList("labels", value.labels)
        }

        override fun read(input: AutomationFieldReader) =
            FriendLabelRule(input.boolean("enabled"), input.list("labels"), input.string("timeFormat"))
    }

    private val remarkCodec = object : AutomationValueCodec<FriendRemarkRule> {
        override fun write(value: FriendRemarkRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("template", value.template)
            out.put("timeFormat", value.timeFormat)
        }

        override fun read(input: AutomationFieldReader) =
            FriendRemarkRule(input.boolean("enabled"), input.string("template"), input.string("timeFormat"))
    }

    val enabled = AutomationRuleKey("enabled", AutomationCodecs.toggle, AutomationToggleRule())
    val delay = AutomationRuleKey("delay", AutomationCodecs.limit, AutomationLimitRule(enabled = true, value = 2000L))
    val labels = AutomationRuleKey("labels", labelCodec, FriendLabelRule())
    val remark = AutomationRuleKey("remark", remarkCodec, FriendRemarkRule())
    val reply = AutomationRuleKey("reply", AutomationCodecs.reply, AutomationReplyRule())

    override val keys = listOf(enabled, delay, labels, remark, reply)
    override val scopeTypes = setOf(AutomationScopeType.ITEM)
    override val titleRes = R.string.feature_auto_accept_friend_requests_name
    override val fixedItems = listOf(
        ACCEPT to R.string.friend_automation_accept,
        ACCEPTED to R.string.friend_automation_accepted,
    )

    private val TEMPLATE_PLACEHOLDERS = listOf($$"$nickname", $$"$time")

    override val editors = listOf(
        toggleEditor(enabled, R.string.friend_automation_enabled, R.string.automation_item_enabled, R.string.automation_item_disabled),
        limitEditor(
            delay, R.string.friend_automation_delay, R.string.friend_automation_no_delay,
            R.string.friend_automation_delay_value, R.string.friend_automation_delay_ms,
        ),
        AutomationRuleEditor(
            key = labels,
            titleRes = R.string.friend_automation_labels,
            summary = {
                if (!it.enabled || it.labels.isEmpty()) stringResource(R.string.friend_automation_labels_off)
                else it.labels.joinToString(", ")
            },
            isOn = { it.enabled },
            setOn = { rule, on -> rule.copy(enabled = on) },
            details = { ctx, value, editable, onChange ->
                textDetails<FriendLabelRule>(
                    "labels", R.string.friend_automation_label_templates,
                    get = { it.labels.joinToString("\n") },
                    set = { v, s -> v.copy(labels = s.lines().map(String::trim).filter(String::isNotEmpty).distinct()) },
                    placeholders = TEMPLATE_PLACEHOLDERS,
                    singleLine = false,
                )(ctx, value, editable, onChange)
                item(key = "labels_existing") {
                    val title = stringResource(R.string.friend_automation_pick_labels)
                    AutomationNavigationRow(
                        title = title,
                        description = stringResource(R.string.friend_automation_pick_labels_summary),
                        enabled = editable,
                        onClick = {
                            val existing = runCatching { WeContactLabelApi.getAllLabels().map { it.labelName } }
                                .onFailure { WeLogger.e(TAG, "failed to load labels", it) }.getOrDefault(emptyList())
                            showComposeDialog(ctx.activity) {
                                LabelPicker(title, existing, value.labels.toSet(), onDismiss) { picked ->
                                    onChange(value.copy(labels = (value.labels.filter { it !in existing } + picked).distinct()))
                                    onDismiss()
                                }
                            }
                        },
                    )
                }
                textDetails<FriendLabelRule>(
                    "labels_time", R.string.friend_automation_time_format,
                    get = { it.timeFormat }, set = { v, s -> v.copy(timeFormat = s) },
                )(ctx, value, editable, onChange)
            },
        ),
        AutomationRuleEditor(
            key = remark,
            titleRes = R.string.friend_automation_remark,
            summary = { if (it.enabled) it.template else stringResource(R.string.friend_automation_remark_off) },
            isOn = { it.enabled },
            setOn = { rule, on -> rule.copy(enabled = on) },
            details = { ctx, value, editable, onChange ->
                textDetails<FriendRemarkRule>(
                    "remark", R.string.friend_automation_remark_template,
                    get = { it.template }, set = { v, s -> v.copy(template = s) },
                    placeholders = TEMPLATE_PLACEHOLDERS,
                )(ctx, value, editable, onChange)
                textDetails<FriendRemarkRule>(
                    "remark_time", R.string.friend_automation_time_format,
                    get = { it.timeFormat }, set = { v, s -> v.copy(timeFormat = s) },
                )(ctx, value, editable, onChange)
            },
        ),
        replyEditor(reply, R.string.friend_automation_reply, AutomationReplyExecutor.BASE_PLACEHOLDERS),
    )

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? =
        rules[reply].validationError()

    private const val TAG = "FriendAutomation"

    fun rules(item: String): AutomationRules = store.snapshot().effectiveRules(AutomationScope.item(item))

    fun formatTemplate(template: String, nickname: String, timeFormat: String): String =
        template.replace($$"$nickname", nickname).replace($$"$time", formatEpoch(System.currentTimeMillis(), timeFormat))
}

@androidx.compose.runtime.Composable
private fun LabelPicker(
    title: String,
    labels: List<String>,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial.filter { it in labels }) }
    AlertDialogContent(
        title = { Text(title) },
        text = {
            if (labels.isEmpty()) {
                Text(stringResource(R.string.friend_automation_no_labels))
            } else {
                SegmentedColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues()) {
                    labels.forEach { label ->
                        item(key = label) {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = label,
                                onClick = { selected = if (label in selected) selected - label else selected + label },
                                trailingContent = { Checkbox(checked = label in selected, onCheckedChange = null) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.dialog_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

object AutoAcceptFriendRequests : ClickableFeature() {
    override val technicalId = "自动通过好友申请"
    override val nameRes = R.string.feature_auto_accept_friend_requests_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_accept_friend_requests_description

    private const val TAG = "AutoAcceptFriendRequests"

    /** The system text a friend's chat starts with after they accept my request. */
    private val ACCEPTED_TEXTS = setOf(
        "我通过了你的朋友验证请求，现在我们可以开始聊天了",
        "I've accepted your friend request. Now let's chat!",
    )

    private val executor = Executors.newSingleThreadExecutor { Thread(it, TAG) }

    private val onRequest: (NewFriendRequest) -> Unit = { request ->
        if (FriendAutomation.rules(FriendAutomation.ACCEPT)[FriendAutomation.enabled].enabled) {
            executor.execute { accept(request) }
        }
    }

    private val onMessage: (MessageInfo) -> Unit = { message ->
        if (!message.isSelfSender &&
            AutomationTalkerCategory.of(message.talker) == AutomationTalkerCategory.PRIVATE &&
            message.content.trim() in ACCEPTED_TEXTS &&
            FriendAutomation.rules(FriendAutomation.ACCEPTED)[FriendAutomation.enabled].enabled
        ) {
            val talker = message.talker
            val content = message.content.trim()
            executor.execute {
                WeLogger.i(TAG, "friend request accepted by $talker")
                runPipeline(FriendAutomation.rules(FriendAutomation.ACCEPTED), talker, "", content)
            }
        }
    }

    override fun onEnable() {
        FriendAutomation.store.snapshot()
        NewFriendEvents.subscribe(onRequest)
        IncomingMessageEvents.subscribe(onMessage)
    }

    override fun onDisable() {
        NewFriendEvents.unsubscribe(onRequest)
        IncomingMessageEvents.unsubscribe(onMessage)
    }

    override fun onClick(context: ComponentActivity) {
        AutomationSettingsActivity.start(context, FriendAutomation)
    }

    private fun accept(request: NewFriendRequest) {
        val verifyUsername = NewFriendEvents.resolveVerifyUsername(request.verifyUsername, request.ticket)
        WeLogger.i(TAG, "accepting friend request from ${request.wxId} (scene=${request.scene})")
        WeContactApi.verifyUser(verifyUsername, request.ticket, request.scene)
        runPipeline(FriendAutomation.rules(FriendAutomation.ACCEPT), request.wxId, request.nickname, "")
    }

    /** Labels, remark, then (after the configured delay) the reply steps. Runs on [executor]. */
    private fun runPipeline(rules: AutomationRules, wxId: String, fallbackNickname: String, content: String) {
        val nickname = WeDatabaseApi.getDisplayName(wxId).takeIf { it.isNotBlank() && it != wxId }
            ?: fallbackNickname.ifBlank { wxId }

        val labelRule = rules[FriendAutomation.labels]
        if (labelRule.enabled && labelRule.labels.isNotEmpty()) {
            val labels = labelRule.labels
                .map { FriendAutomation.formatTemplate(it, nickname, labelRule.timeFormat).trim() }
                .filter(String::isNotEmpty)
            labels.forEach { WeContactLabelApi.createLabel(it) }
            val current = WeContactLabelApi.getLabelNamesForContact(wxId)
            WeContactLabelApi.modifyLabel(wxId, (current + labels).distinct())
        }

        val remarkRule = rules[FriendAutomation.remark]
        if (remarkRule.enabled && remarkRule.template.isNotBlank()) {
            val remark = FriendAutomation.formatTemplate(remarkRule.template, nickname, remarkRule.timeFormat).trim()
            if (!runBlocking { WeContactApi.setRemark(wxId, remark) }) WeLogger.w(TAG, "failed to set remark of $wxId")
        }

        val delay = rules[FriendAutomation.delay].let { if (it.enabled) it.value else 0L }
        val reply = rules[FriendAutomation.reply]
        if (reply.enabled) {
            AutomationReplyExecutor.run(reply.steps, AutomationReplyContext(talker = wxId, content = content), reply.asQuote, delay)
        }
    }
}
