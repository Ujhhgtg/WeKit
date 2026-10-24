package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
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
import dev.ujhhgtg.wekit.features.automation.AutomationSection
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.AutomationValueCodec
import dev.ujhhgtg.wekit.features.automation.IncomingMessageEvents
import dev.ujhhgtg.wekit.features.automation.NewFriendEvents
import dev.ujhhgtg.wekit.features.automation.NewFriendRequest
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.automation.AutomationFieldLabel
import dev.ujhhgtg.wekit.ui.automation.AutomationMiniChips
import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import dev.ujhhgtg.wekit.ui.automation.AutomationSheetNote
import dev.ujhhgtg.wekit.ui.automation.limitEditor
import dev.ujhhgtg.wekit.ui.automation.replyEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Labels applied to a new friend; entries are templates (`$nickname`, `$time`). Missing labels are
 * created.
 */
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

    private val labelCodec =
        object : AutomationValueCodec<FriendLabelRule> {
            override fun write(value: FriendLabelRule, out: AutomationFieldWriter) {
                out.put("enabled", value.enabled)
                out.put("timeFormat", value.timeFormat)
                out.putList("labels", value.labels)
            }

            override fun read(input: AutomationFieldReader) =
                FriendLabelRule(
                    input.boolean("enabled"),
                    input.list("labels"),
                    input.string("timeFormat"),
                )
        }

    private val remarkCodec =
        object : AutomationValueCodec<FriendRemarkRule> {
            override fun write(value: FriendRemarkRule, out: AutomationFieldWriter) {
                out.put("enabled", value.enabled)
                out.put("template", value.template)
                out.put("timeFormat", value.timeFormat)
            }

            override fun read(input: AutomationFieldReader) =
                FriendRemarkRule(
                    input.boolean("enabled"),
                    input.string("template"),
                    input.string("timeFormat"),
                )
        }

    val enabled = AutomationRuleKey("enabled", AutomationCodecs.toggle, AutomationToggleRule())
    val delay =
        AutomationRuleKey(
            "delay",
            AutomationCodecs.limit,
            AutomationLimitRule(enabled = true, value = 2000L),
        )
    val labels = AutomationRuleKey("labels", labelCodec, FriendLabelRule())
    val remark = AutomationRuleKey("remark", remarkCodec, FriendRemarkRule())
    val reply = AutomationRuleKey("reply", AutomationCodecs.reply, AutomationReplyRule())

    override val keys = listOf(enabled, delay, labels, remark, reply)
    override val scopeTypes = setOf(AutomationScopeType.ITEM)
    override val titleRes = R.string.feature_auto_accept_friend_requests_name
    override val fixedItems =
        listOf(
            ACCEPT to R.string.friend_automation_accept,
            ACCEPTED to R.string.friend_automation_accepted,
        )

    override val editors =
        listOf(
            toggleEditor(
                enabled,
                R.string.friend_automation_enabled,
                R.string.automation_item_enabled,
                R.string.automation_item_disabled,
            ),
            limitEditor(
                delay,
                R.string.friend_automation_delay,
                R.string.friend_automation_no_delay,
                R.string.friend_automation_delay_value,
                R.string.friend_automation_delay_ms,
                range = 0..10_000,
                step = 100,
                unitRes = R.string.automation_unit_ms,
            ),
            AutomationRuleEditor(
                key = labels,
                titleRes = R.string.friend_automation_labels,
                summary = {
                    if (!it.enabled || it.labels.isEmpty()) {
                        stringResource(R.string.friend_automation_labels_off)
                    } else {
                        stringResource(R.string.friend_automation_labels_count, it.labels.size)
                    }
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                summaryExtra = { if (it.enabled) AutomationMiniChips(it.labels) },
                sheet = { _, value, onChange -> LabelsSheet(value, onChange) },
            ),
            AutomationRuleEditor(
                key = remark,
                titleRes = R.string.friend_automation_remark,
                summary = {
                    if (it.enabled) {
                        it.template
                    } else {
                        stringResource(R.string.friend_automation_remark_off)
                    }
                },
                isOn = { it.enabled },
                setOn = { rule, on -> rule.copy(enabled = on) },
                sheet = { _, value, onChange -> RemarkSheet(value, onChange) },
            ),
            replyEditor(
                reply,
                R.string.friend_automation_reply,
                AutomationReplyExecutor.BASE_PLACEHOLDERS,
            ),
        )

    override val sections =
        listOf(
            AutomationSection(R.string.friend_automation_section_timing, listOf(delay)),
            AutomationSection(R.string.friend_automation_section_profile, listOf(labels, remark)),
            AutomationSection(R.string.friend_automation_section_messages, listOf(reply)),
        )

    override val itemSwitch = enabled
    override val itemSummaryKeys = listOf(labels, remark, reply)

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? {
        if (validates(keyIds, labels) && rules[labels].let { it.enabled && it.labels.isEmpty() }) {
            return localizedAutomationString(R.string.friend_automation_labels_required)
        }
        if (
            validates(keyIds, remark) && rules[remark].let { it.enabled && it.template.isBlank() }
        ) {
            return localizedAutomationString(R.string.friend_automation_remark_required)
        }
        if (validates(keyIds, reply)) return rules[reply].validationError()
        return null
    }

    fun rules(item: String): AutomationRules =
        store.snapshot().effectiveRules(AutomationScope.item(item))

    fun formatTemplate(template: String, nickname: String, timeFormat: String): String =
        template
            .replace($$"$nickname", nickname)
            .replace($$"$time", formatEpoch(System.currentTimeMillis(), timeFormat))
}

private val TEMPLATE_PLACEHOLDERS = listOf($$"$nickname", $$"$time")

/** A template field with tappable placeholders. */
@Composable
private fun TemplateField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                onChange(it.text)
            },
            label = { Text(label) },
            trailingIcon = trailing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        PlaceholderChips(
            TEMPLATE_PLACEHOLDERS,
            field,
            focused,
            {
                field = it
                onChange(it.text)
            },
        )
    }
}

@Composable
private fun TimeFormatField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.friend_automation_time_format)) },
        supportingText = {
            Text(
                stringResource(
                    R.string.friend_automation_time_example,
                    formatEpoch(System.currentTimeMillis(), value),
                ),
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LabelsSheet(value: FriendLabelRule, onChange: (FriendLabelRule) -> Unit) {
    val existing by
        produceState(emptyList()) {
            this.value =
                withContext(Dispatchers.IO) {
                    runCatching { WeContactLabelApi.getAllLabels().map { it.labelName } }
                        .onFailure { WeLogger.e("FriendAutomation", "failed to load labels", it) }
                        .getOrDefault(emptyList())
                }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(stringResource(R.string.friend_automation_existing_labels))
        if (existing.isEmpty()) {
            AutomationSheetNote(stringResource(R.string.friend_automation_no_labels))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            existing.forEach { label ->
                val on = label in value.labels
                FilterChip(
                    selected = on,
                    onClick = {
                        onChange(
                            value.copy(
                                labels = if (on) value.labels - label else value.labels + label,
                            ),
                        )
                    },
                    label = { Text(label) },
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(stringResource(R.string.friend_automation_new_labels))
        val custom = value.labels.filter { it !in existing }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            custom.forEach { label ->
                InputChip(
                    selected = false,
                    onClick = { onChange(value.copy(labels = value.labels - label)) },
                    label = { Text(label) },
                    trailingIcon = {
                        Icon(
                            MaterialSymbols.Outlined.Close,
                            stringResource(R.string.action_delete),
                            Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        var pending by remember { mutableStateOf("") }
        val add = {
            val label = pending.trim()
            if (label.isNotEmpty() && label !in value.labels) {
                onChange(value.copy(labels = value.labels + label))
            }
            pending = ""
        }
        TemplateField(
            label = stringResource(R.string.friend_automation_label_template),
            value = pending,
            onChange = { pending = it },
            trailing = {
                IconButton(enabled = pending.isNotBlank(), onClick = add) {
                    Icon(MaterialSymbols.Outlined.Add, stringResource(R.string.automation_add_item))
                }
            },
        )
    }
    TimeFormatField(value.timeFormat) { onChange(value.copy(timeFormat = it)) }
}

@Composable
private fun RemarkSheet(value: FriendRemarkRule, onChange: (FriendRemarkRule) -> Unit) {
    TemplateField(
        stringResource(R.string.friend_automation_remark_template),
        value.template,
        { onChange(value.copy(template = it)) },
    )
    TimeFormatField(value.timeFormat) { onChange(value.copy(timeFormat = it)) }
    AutomationSheetNote(
        stringResource(
            R.string.friend_automation_remark_preview,
            FriendAutomation.formatTemplate(
                value.template,
                stringResource(R.string.friend_automation_sample_nickname),
                value.timeFormat,
            ),
        ),
    )
}

object AutoAcceptFriendRequests : ClickableFeature() {
    override val technicalId = "自动通过好友申请"
    override val nameRes = R.string.feature_auto_accept_friend_requests_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_accept_friend_requests_description

    private const val TAG = "AutoAcceptFriendRequests"

    /** The system text a friend's chat starts with after they accept my request. */
    private val ACCEPTED_TEXTS =
        setOf(
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
        if (
            !message.isSelfSender &&
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
        val verifyUsername =
            NewFriendEvents.resolveVerifyUsername(request.verifyUsername, request.ticket)
        WeLogger.i(TAG, "accepting friend request from ${request.wxId} (scene=${request.scene})")
        WeContactApi.verifyUser(verifyUsername, request.ticket, request.scene)
        runPipeline(
            FriendAutomation.rules(FriendAutomation.ACCEPT),
            request.wxId,
            request.nickname,
            "",
        )
    }

    /** Labels, remark, then (after the configured delay) the reply steps. Runs on [executor]. */
    private fun runPipeline(
        rules: AutomationRules,
        wxId: String,
        fallbackNickname: String,
        content: String,
    ) {
        val nickname =
            WeDatabaseApi.getDisplayName(wxId).takeIf { it.isNotBlank() && it != wxId }
                ?: fallbackNickname.ifBlank { wxId }

        val labelRule = rules[FriendAutomation.labels]
        if (labelRule.enabled && labelRule.labels.isNotEmpty()) {
            val labels =
                labelRule.labels
                    .map {
                        FriendAutomation.formatTemplate(it, nickname, labelRule.timeFormat).trim()
                    }
                    .filter(String::isNotEmpty)
            labels.forEach { WeContactLabelApi.createLabel(it) }
            val current = WeContactLabelApi.getLabelNamesForContact(wxId)
            WeContactLabelApi.modifyLabel(wxId, (current + labels).distinct())
        }

        val remarkRule = rules[FriendAutomation.remark]
        if (remarkRule.enabled && remarkRule.template.isNotBlank()) {
            val remark =
                FriendAutomation.formatTemplate(
                        remarkRule.template,
                        nickname,
                        remarkRule.timeFormat,
                    )
                    .trim()
            if (!runBlocking { WeContactApi.setRemark(wxId, remark) }) {
                WeLogger.w(TAG, "failed to set remark of $wxId")
            }
        }

        val delay = rules[FriendAutomation.delay].let { if (it.enabled) it.value else 0L }
        val reply = rules[FriendAutomation.reply]
        if (reply.enabled) {
            AutomationReplyExecutor.run(
                reply.steps,
                AutomationReplyContext(talker = wxId, content = content),
                reply.asQuote,
                delay,
            )
        }
    }
}
