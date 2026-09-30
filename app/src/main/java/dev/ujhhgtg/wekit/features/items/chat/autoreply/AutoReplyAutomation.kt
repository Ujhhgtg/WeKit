package dev.ujhhgtg.wekit.features.items.chat.autoreply

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationLimitRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyExecutor
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationSection
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationTargetRule
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.ui.automation.choiceEditor
import dev.ujhhgtg.wekit.ui.automation.keywordEditor
import dev.ujhhgtg.wekit.ui.automation.limitEditor
import dev.ujhhgtg.wekit.ui.automation.replyEditor
import dev.ujhhgtg.wekit.ui.automation.targetEditor
import dev.ujhhgtg.wekit.ui.automation.timeRangeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor

enum class AutoReplyAtTrigger { NONE, AT_ME, AT_ALL }

/** Settings of [AutoReply]: a GLOBAL chat filter, plus an ordered list of ITEM rules. */
object AutoReplyAutomation : AutomationSpec("AUTO_REPLY") {
    // GLOBAL
    val chats = AutomationRuleKey("chats", AutomationCodecs.target, AutomationTargetRule())

    // ITEM (one per rule)
    val enabled = AutomationRuleKey("enabled", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val keyword = AutomationRuleKey("keyword", AutomationCodecs.keyword, AutomationKeywordRule())
    val excludeKeyword = AutomationRuleKey("excludeKeyword", AutomationCodecs.keyword, AutomationKeywordRule())
    val target = AutomationRuleKey("target", AutomationCodecs.target, AutomationTargetRule())
    val atTrigger = AutomationRuleKey("atTrigger", AutomationCodecs.enumValue<AutoReplyAtTrigger>(), AutoReplyAtTrigger.NONE)
    val patTrigger = AutomationRuleKey("patTrigger", AutomationCodecs.toggle, AutomationToggleRule())
    val timeRange = AutomationRuleKey("timeRange", AutomationCodecs.timeRange, AutomationTimeRangeRule())
    val maxCount = AutomationRuleKey("maxCount", AutomationCodecs.limit, AutomationLimitRule())
    val cooldown = AutomationRuleKey("cooldown", AutomationCodecs.limit, AutomationLimitRule())
    val reply = AutomationRuleKey("reply", AutomationCodecs.reply, AutomationReplyRule(enabled = true))

    override val keys = listOf(chats, enabled, keyword, excludeKeyword, target, atTrigger, patTrigger, timeRange, maxCount, cooldown, reply)
    override val scopeTypes = setOf(AutomationScopeType.GLOBAL, AutomationScopeType.ITEM)
    override val titleRes = R.string.feature_auto_reply_name
    override val globalSummaryRes = R.string.auto_reply_global_summary
    override val itemsTitleRes = R.string.auto_reply_rules

    private val onlyGlobal = { s: dev.ujhhgtg.wekit.features.automation.AutomationScope -> s.type == AutomationScopeType.GLOBAL }
    private val onlyItem = { s: dev.ujhhgtg.wekit.features.automation.AutomationScope -> s.type == AutomationScopeType.ITEM }

    override val editors = listOf(
        targetEditor(chats, R.string.auto_reply_chats, visibleIn = onlyGlobal),
        toggleEditor(enabled, R.string.auto_reply_rule_enabled, R.string.automation_item_enabled, R.string.automation_item_disabled, visibleIn = onlyItem),
        keywordEditor(keyword, R.string.auto_reply_keyword, R.string.auto_reply_keyword_any, visibleIn = onlyItem),
        keywordEditor(excludeKeyword, R.string.auto_reply_exclude_keyword, R.string.auto_reply_exclude_keyword_off, visibleIn = onlyItem),
        targetEditor(target, R.string.auto_reply_target, visibleIn = onlyItem),
        choiceEditor(
            atTrigger, R.string.auto_reply_at_trigger,
            listOf(
                AutoReplyAtTrigger.NONE to R.string.auto_reply_at_none,
                AutoReplyAtTrigger.AT_ME to R.string.auto_reply_at_me,
                AutoReplyAtTrigger.AT_ALL to R.string.auto_reply_at_all,
            ),
            visibleIn = onlyItem,
        ),
        toggleEditor(patTrigger, R.string.auto_reply_pat_trigger, R.string.auto_reply_pat_trigger_on, R.string.auto_reply_pat_trigger_off, visibleIn = onlyItem),
        timeRangeEditor(timeRange, R.string.auto_reply_time_range, R.string.auto_reply_time_unrestricted, visibleIn = onlyItem),
        limitEditor(
            maxCount, R.string.auto_reply_max_count, R.string.auto_reply_max_count_off,
            R.string.auto_reply_max_count_value, R.string.auto_reply_max_count_field,
            range = 1..50, step = 1, unitRes = R.string.automation_unit_times, visibleIn = onlyItem,
        ),
        limitEditor(
            cooldown, R.string.auto_reply_cooldown, R.string.auto_reply_cooldown_off,
            R.string.auto_reply_cooldown_value, R.string.auto_reply_cooldown_field,
            range = 0..3600, step = 10, unitRes = R.string.automation_unit_seconds, visibleIn = onlyItem,
        ),
        replyEditor(reply, R.string.auto_reply_steps, AutomationReplyExecutor.BASE_PLACEHOLDERS, visibleIn = onlyItem, switchable = false),
    )

    override val sections = listOf(
        AutomationSection(null, listOf(chats)),
        AutomationSection(R.string.auto_reply_section_triggers, listOf(keyword, excludeKeyword, atTrigger, patTrigger)),
        AutomationSection(R.string.auto_reply_section_scope, listOf(target, timeRange)),
        AutomationSection(R.string.auto_reply_section_limits, listOf(maxCount, cooldown)),
        AutomationSection(R.string.auto_reply_section_reply, listOf(reply)),
    )

    override val itemSwitch = enabled
    override val itemSummaryKeys = listOf(keyword, atTrigger, patTrigger, timeRange, target, maxCount, cooldown)

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? {
        if (validates(keyIds, keyword)) {
            rules[keyword].validationError(localizedAutomationString(R.string.auto_reply_keyword))?.let { return it }
        }
        if (validates(keyIds, excludeKeyword)) {
            rules[excludeKeyword].validationError(localizedAutomationString(R.string.auto_reply_exclude_keyword))?.let { return it }
        }
        return null
    }
}

/** What an incoming message offers to the rule matcher. */
data class AutoReplyMessage(
    val talker: String,
    val sender: String,
    val content: String,
    val isText: Boolean,
    val isGroup: Boolean,
    val atMe: Boolean = false,
    val atAll: Boolean = false,
    val isPat: Boolean = false,
    val patMe: Boolean = false,
)

/** Rule matching of [AutoReply], ported from Hchat's trigger / keyword semantics. */
object AutoReplyMatcher {
    fun matches(rules: AutomationRules, message: AutoReplyMessage): Boolean {
        val spec = AutoReplyAutomation
        if (!rules[spec.enabled].enabled || rules[spec.reply].steps.isEmpty()) return false
        if (!rules[spec.timeRange].matches()) return false
        if (!rules[spec.target].matches(message.talker, message.sender)) return false
        return triggerMatches(rules, message) && keywordMatches(rules, message)
    }

    private fun triggerMatches(rules: AutomationRules, message: AutoReplyMessage): Boolean {
        val at = rules[AutoReplyAutomation.atTrigger]
        if (message.isGroup) {
            if (at == AutoReplyAtTrigger.AT_ME && !message.atMe) return false
            if (at == AutoReplyAtTrigger.AT_ALL && !message.atAll) return false
        } else if (at != AutoReplyAtTrigger.NONE) {
            return false
        }
        val pat = rules[AutoReplyAutomation.patTrigger].enabled
        return !pat || message.patMe
    }

    /** A disabled keyword rule means "any message"; keywords and exclusions only apply to text. */
    private fun keywordMatches(rules: AutomationRules, message: AutoReplyMessage): Boolean {
        if (message.isPat && rules[AutoReplyAutomation.patTrigger].enabled) return true
        val keyword = rules[AutoReplyAutomation.keyword]
        if (message.isText && rules[AutoReplyAutomation.excludeKeyword].excludes(message.content)) return false
        if (!keyword.enabled) return true
        return message.isText && keyword.matchesEnabled(message.content)
    }
}
