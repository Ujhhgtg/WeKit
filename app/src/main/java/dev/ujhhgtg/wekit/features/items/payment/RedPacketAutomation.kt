package dev.ujhhgtg.wekit.features.items.payment

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationDelayRule
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyExecutor
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSection
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.ui.automation.choiceEditor
import dev.ujhhgtg.wekit.ui.automation.delayEditor
import dev.ujhhgtg.wekit.ui.automation.keywordEditor
import dev.ujhhgtg.wekit.ui.automation.replyEditor
import dev.ujhhgtg.wekit.ui.automation.timeRangeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor

/** Hierarchical rules of [AutoOpenRedPackets]. */
object RedPacketAutomation : AutomationSpec("RED_PACKET") {
    enum class ReceiveMode { NETWORK, CLICK }

    private val KEYWORD_MODES = listOf(AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.REGEX)

    val grab = AutomationRuleKey("grab", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val grabSelf = AutomationRuleKey("grabSelf", AutomationCodecs.toggle, AutomationToggleRule())
    val receiveMode = AutomationRuleKey("receiveMode", AutomationCodecs.enumValue<ReceiveMode>(), ReceiveMode.NETWORK)
    val timeRange = AutomationRuleKey("timeRange", AutomationCodecs.timeRange, AutomationTimeRangeRule())
    val keyword = AutomationRuleKey("keyword", AutomationCodecs.keyword, AutomationKeywordRule())
    val skipKeyword = AutomationRuleKey("skipKeyword", AutomationCodecs.keyword, AutomationKeywordRule())
    val delay = AutomationRuleKey("delay", AutomationCodecs.delay, AutomationDelayRule())
    val notification = AutomationRuleKey("notification", AutomationCodecs.toggle, AutomationToggleRule())
    val reply = AutomationRuleKey("reply", AutomationCodecs.reply, AutomationReplyRule())

    override val keys = listOf(grab, grabSelf, receiveMode, timeRange, keyword, skipKeyword, delay, notification, reply)

    override val scopeTypes = setOf(
        AutomationScopeType.GLOBAL, AutomationScopeType.CATEGORY, AutomationScopeType.CONTACT,
        AutomationScopeType.GROUP_MEMBER, AutomationScopeType.PRESET,
    )
    override val categories = listOf(AutomationTalkerCategory.PRIVATE, AutomationTalkerCategory.GROUP)

    override val titleRes = R.string.feature_auto_open_red_packets_name
    override val globalSummaryRes = R.string.payment_red_packet_global_summary

    val placeholders = AutomationReplyExecutor.BASE_PLACEHOLDERS + $$"$amount"

    override val editors = listOf(
        toggleEditor(
            grab, R.string.payment_red_packet_grab, R.string.payment_red_packet_grab_scope, R.string.payment_red_packet_skip_scope,
            globalTitleRes = R.string.payment_red_packet_default_grab,
        ),
        toggleEditor(
            grabSelf, R.string.payment_red_packet_grab_self,
            R.string.payment_red_packet_grab_self_enabled, R.string.payment_red_packet_grab_self_disabled,
        ),
        choiceEditor(
            receiveMode, R.string.payment_red_packet_receive_mode,
            listOf(ReceiveMode.NETWORK to R.string.payment_red_packet_network, ReceiveMode.CLICK to R.string.payment_red_packet_manual_click),
        ),
        timeRangeEditor(timeRange, R.string.payment_red_packet_time_range, R.string.payment_red_packet_time_unrestricted),
        keywordEditor(
            keyword, R.string.payment_red_packet_keywords, R.string.payment_red_packet_keywords_unrestricted, KEYWORD_MODES,
            hintRes = R.string.payment_red_packet_keywords_hint,
        ),
        keywordEditor(
            skipKeyword, R.string.payment_red_packet_skip_keywords, R.string.payment_red_packet_skip_keywords_unrestricted, KEYWORD_MODES,
            hintRes = R.string.payment_red_packet_skip_keywords_hint,
        ),
        delayEditor(delay, R.string.payment_red_packet_delay, R.string.payment_red_packet_immediate),
        toggleEditor(
            notification, R.string.payment_red_packet_notification,
            R.string.payment_red_packet_notification_enabled, R.string.automation_notification_disabled,
        ),
        replyEditor(reply, R.string.payment_red_packet_auto_reply, placeholders),
    )

    override val sections = listOf(
        AutomationSection(R.string.payment_red_packet_section_conditions, listOf(grab, grabSelf, timeRange, keyword, skipKeyword)),
        AutomationSection(R.string.payment_red_packet_section_method, listOf(receiveMode, delay)),
        AutomationSection(R.string.payment_red_packet_section_after, listOf(notification, reply)),
    )

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? {
        if (validates(keyIds, delay)) rules[delay].validationError()?.let { return it }
        if (validates(keyIds, keyword)) {
            rules[keyword].validationError(localizedAutomationString(R.string.payment_red_packet_keyword_label))?.let { return it }
        }
        if (validates(keyIds, skipKeyword)) {
            rules[skipKeyword].validationError(localizedAutomationString(R.string.payment_red_packet_skip_keyword_label))
                ?.let { return it }
        }
        if (validates(keyIds, reply)) rules[reply].validationError()?.let { return it }
        return null
    }
}
