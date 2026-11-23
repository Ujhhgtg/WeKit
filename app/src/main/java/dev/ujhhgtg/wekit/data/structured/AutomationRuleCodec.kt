package dev.ujhhgtg.wekit.data.structured

import dev.ujhhgtg.wekit.data.entity.*
import dev.ujhhgtg.wekit.features.items.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.items.AutomationToggleRule
import dev.ujhhgtg.wekit.features.items.payment.RedPacketSettings
import dev.ujhhgtg.wekit.features.items.payment.TransferSettings
import dev.ujhhgtg.wekit.features.items.moments.*
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

/** Decode NULL as inheritance, never as a default-valued override. */
private fun <T> ruleGroup(enabled: Boolean?, vararg fields: Any?, decode: (Boolean) -> T): T? {
    if (enabled == null) {
        require(fields.all { it == null }) { "Inherited rule has stored scalar values" }
        return null
    }
    require(fields.all { it != null }) { "Stored rule group is incomplete" }
    return decode(enabled)
}

private fun timeRule(enabled: Boolean?, start: Int?, end: Int?): AutomationTimeRangeRule? =
    ruleGroup(enabled, start, end) { AutomationTimeRangeRule(it, start!!, end!!) }

private fun keywordRule(enabled: Boolean?, mode: String?, regex: String?, ignoreCase: Boolean?, strings: List<String>): AutomationKeywordRule? {
    require(enabled != null || strings.isEmpty()) { "Inherited keyword rule has stored keywords" }
    return ruleGroup(enabled, mode, regex, ignoreCase) {
        AutomationKeywordRule(it, AutomationKeywordMode.valueOf(mode!!), strings, regex!!, ignoreCase!!)
    }
}

fun validatePaymentScope(scopeType: String, talkerId: String, memberId: String) {
    require(when (scopeType) {
        "GLOBAL" -> talkerId.isEmpty() && memberId.isEmpty()
        "CONTACT" -> talkerId.isNotEmpty() && memberId.isEmpty()
        "GROUP_MEMBER" -> talkerId.isGroupChatWxId && memberId.isNotEmpty()
        else -> false
    }) { "Invalid payment rule scope" }
}

fun validateMomentScope(featureKind: String, scopeType: String, ownerId: String) {
    require(featureKind == "LIKE" || featureKind == "REPOST") { "Unknown moment automation feature" }
    require(when (scopeType) {
        "GLOBAL" -> ownerId.isEmpty()
        "CONTACT" -> ownerId.isNotEmpty()
        else -> false
    }) { "Invalid moment rule scope" }
}

fun RedPacketSettings.RuleSet.asOverrides() = RedPacketSettings.RuleOverrides(
    grab = grab,
    grabSelf = grabSelf,
    receiveMode = receiveMode,
    timeRange = timeRange,
    keyword = keyword,
    skipKeyword = skipKeyword,
    delay = delay,
    notification = notification,
    autoReply = autoReply,
)

fun RedPacketSettings.RuleOverrides.toEntity(scopeType: String, talkerId: String, memberId: String): RedPacketRuleEntity {
    validatePaymentScope(scopeType, talkerId, memberId)
    return RedPacketRuleEntity(
        scopeType = scopeType,
        talkerId = talkerId,
        memberId = memberId,
        grabEnabled = grab?.enabled,
        grabSelfEnabled = grabSelf?.enabled,
        receiveMode = receiveMode?.name,
        timeEnabled = timeRange?.enabled,
        timeStartMinute = timeRange?.startMinute,
        timeEndMinute = timeRange?.endMinute,
        keywordEnabled = keyword?.enabled,
        keywordMode = keyword?.mode?.name,
        keywordRegex = keyword?.regex,
        keywordIgnoreCase = keyword?.ignoreCase,
        skipKeywordEnabled = skipKeyword?.enabled,
        skipKeywordMode = skipKeyword?.mode?.name,
        skipKeywordRegex = skipKeyword?.regex,
        skipKeywordIgnoreCase = skipKeyword?.ignoreCase,
        delayEnabled = delay?.enabled,
        delayBaseMs = delay?.baseMs,
        delayRandomRangeMs = delay?.randomRangeMs,
        notificationEnabled = notification?.enabled,
        replyEnabled = autoReply?.enabled,
        replyText = autoReply?.text,
    )
}

fun RedPacketRuleEntity.toOverrides(keywords: List<RedPacketKeywordEntity>): RedPacketSettings.RuleOverrides {
    validatePaymentScope(scopeType, talkerId, memberId)
    require(keywords.all { it.ruleKind == "KEYWORD" || it.ruleKind == "SKIP_KEYWORD" }) { "Unknown red packet keyword rule" }
    return RedPacketSettings.RuleOverrides(
        grab = grabEnabled?.let(::AutomationToggleRule),
        grabSelf = grabSelfEnabled?.let(::AutomationToggleRule),
        receiveMode = receiveMode?.let(RedPacketSettings.ReceiveMode::valueOf),
        timeRange = timeRule(timeEnabled, timeStartMinute, timeEndMinute),
        keyword = keywordRule(keywordEnabled, keywordMode, keywordRegex, keywordIgnoreCase, keywords.filter { it.ruleKind == "KEYWORD" }.sortedBy { it.position }.map { it.text }),
        skipKeyword = keywordRule(skipKeywordEnabled, skipKeywordMode, skipKeywordRegex, skipKeywordIgnoreCase, keywords.filter { it.ruleKind == "SKIP_KEYWORD" }.sortedBy { it.position }.map { it.text }),
        delay = ruleGroup(delayEnabled, delayBaseMs, delayRandomRangeMs) { RedPacketSettings.DelayRule(it, delayBaseMs!!, delayRandomRangeMs!!) },
        notification = notificationEnabled?.let(::AutomationToggleRule),
        autoReply = ruleGroup(replyEnabled, replyText) { RedPacketSettings.ReplyRule(it, replyText!!) },
    )
}

fun RedPacketSettings.RuleOverrides.asGlobalRules() = RedPacketSettings.RuleSet(
    grab = requireNotNull(grab) { "Global grab rule is missing" },
    grabSelf = requireNotNull(grabSelf) { "Global grabSelf rule is missing" },
    receiveMode = requireNotNull(receiveMode) { "Global receiveMode rule is missing" },
    timeRange = requireNotNull(timeRange) { "Global timeRange rule is missing" },
    keyword = requireNotNull(keyword) { "Global keyword rule is missing" },
    skipKeyword = requireNotNull(skipKeyword) { "Global skipKeyword rule is missing" },
    delay = requireNotNull(delay) { "Global delay rule is missing" },
    notification = requireNotNull(notification) { "Global notification rule is missing" },
    autoReply = requireNotNull(autoReply) { "Global autoReply rule is missing" },
)

fun TransferSettings.RuleSet.asOverrides() = TransferSettings.RuleOverrides(
    accept = accept,
    timeRange = timeRange,
    amountRange = amountRange,
    memoKeyword = memoKeyword,
    delay = delay,
    notification = notification,
    autoReply = autoReply,
)

fun TransferSettings.RuleOverrides.toEntity(scopeType: String, talkerId: String, memberId: String): TransferRuleEntity {
    validatePaymentScope(scopeType, talkerId, memberId)
    return TransferRuleEntity(
        scopeType = scopeType,
        talkerId = talkerId,
        memberId = memberId,
        acceptEnabled = accept?.enabled,
        timeEnabled = timeRange?.enabled,
        timeStartMinute = timeRange?.startMinute,
        timeEndMinute = timeRange?.endMinute,
        amountEnabled = amountRange?.enabled,
        amountMinimumYuan = amountRange?.minimumYuan,
        amountMaximumYuan = amountRange?.maximumYuan,
        memoKeywordEnabled = memoKeyword?.enabled,
        memoKeywordMode = memoKeyword?.mode?.name,
        memoKeywordRegex = memoKeyword?.regex,
        memoKeywordIgnoreCase = memoKeyword?.ignoreCase,
        delayEnabled = delay?.enabled,
        delayBaseMs = delay?.baseMs,
        delayRandomRangeMs = delay?.randomRangeMs,
        notificationEnabled = notification?.enabled,
        replyEnabled = autoReply?.enabled,
        replyText = autoReply?.text,
    )
}

fun TransferRuleEntity.toOverrides(keywords: List<TransferKeywordEntity>): TransferSettings.RuleOverrides {
    validatePaymentScope(scopeType, talkerId, memberId)
    return TransferSettings.RuleOverrides(
        accept = acceptEnabled?.let(::AutomationToggleRule),
        timeRange = timeRule(timeEnabled, timeStartMinute, timeEndMinute),
        amountRange = ruleGroup(amountEnabled, amountMinimumYuan, amountMaximumYuan) { TransferSettings.AmountRangeRule(it, amountMinimumYuan!!, amountMaximumYuan!!) },
        memoKeyword = keywordRule(memoKeywordEnabled, memoKeywordMode, memoKeywordRegex, memoKeywordIgnoreCase, keywords.sortedBy { it.position }.map { it.text }),
        delay = ruleGroup(delayEnabled, delayBaseMs, delayRandomRangeMs) { TransferSettings.DelayRule(it, delayBaseMs!!, delayRandomRangeMs!!) },
        notification = notificationEnabled?.let(::AutomationToggleRule),
        autoReply = ruleGroup(replyEnabled, replyText) { TransferSettings.ReplyRule(it, replyText!!) },
    )
}

fun TransferSettings.RuleOverrides.asGlobalRules() = TransferSettings.RuleSet(
    accept = requireNotNull(accept) { "Global accept rule is missing" },
    timeRange = requireNotNull(timeRange) { "Global timeRange rule is missing" },
    amountRange = requireNotNull(amountRange) { "Global amountRange rule is missing" },
    memoKeyword = requireNotNull(memoKeyword) { "Global memoKeyword rule is missing" },
    delay = requireNotNull(delay) { "Global delay rule is missing" },
    notification = requireNotNull(notification) { "Global notification rule is missing" },
    autoReply = requireNotNull(autoReply) { "Global autoReply rule is missing" },
)

fun MomentAutomationRuleSet.asOverrides() = MomentAutomationOverrides(
    process = process,
    action = action,
    mode = mode,
    interval = interval,
    timeRange = timeRange,
    keyword = keyword,
    contentType = contentType,
    maximumAge = maximumAge,
)

fun MomentAutomationOverrides.toEntity(featureKind: String, scopeType: String, ownerId: String): MomentAutomationRuleEntity {
    validateMomentScope(featureKind, scopeType, ownerId)
    return MomentAutomationRuleEntity(
        featureKind = featureKind,
        scopeType = scopeType,
        ownerId = ownerId,
        processEnabled = process?.enabled,
        actionEnabled = action?.enabled,
        actionValue = action?.action?.name,
        modeEnabled = mode?.enabled,
        modeValue = mode?.mode?.name,
        intervalEnabled = interval?.enabled,
        intervalMilliseconds = interval?.milliseconds,
        timeEnabled = timeRange?.enabled,
        timeStartMinute = timeRange?.startMinute,
        timeEndMinute = timeRange?.endMinute,
        keywordEnabled = keyword?.enabled,
        keywordMode = keyword?.mode?.name,
        keywordRegex = keyword?.regex,
        keywordIgnoreCase = keyword?.ignoreCase,
        contentTypeEnabled = contentType?.enabled,
        maximumAgeEnabled = maximumAge?.enabled,
        maximumAgeHours = maximumAge?.maximumHours,
    )
}

fun MomentAutomationRuleEntity.toOverrides(keywords: List<MomentAutomationKeywordEntity>, contentTypes: List<MomentAutomationContentTypeEntity>): MomentAutomationOverrides {
    validateMomentScope(featureKind, scopeType, ownerId)
    require(contentTypeEnabled != null || contentTypes.isEmpty()) { "Inherited content-type rule has stored types" }
    return MomentAutomationOverrides(
        process = processEnabled?.let(::AutomationToggleRule),
        action = ruleGroup(actionEnabled, actionValue) { MomentActionRule(it, MomentAutomationAction.valueOf(actionValue!!)) },
        mode = ruleGroup(modeEnabled, modeValue) { MomentModeRule(it, MomentAutomationMode.valueOf(modeValue!!)) },
        interval = ruleGroup(intervalEnabled, intervalMilliseconds) { MomentIntervalRule(it, intervalMilliseconds!!) },
        timeRange = timeRule(timeEnabled, timeStartMinute, timeEndMinute),
        keyword = keywordRule(keywordEnabled, keywordMode, keywordRegex, keywordIgnoreCase, keywords.sortedBy { it.position }.map { it.text }),
        contentType = contentTypeEnabled?.let { MomentTypeRule(it, contentTypes.map { type -> type.typeId }.toSet()) },
        maximumAge = ruleGroup(maximumAgeEnabled, maximumAgeHours) { MomentAgeRule(it, maximumAgeHours!!) },
    )
}

fun MomentAutomationOverrides.asGlobalRules() = MomentAutomationRuleSet(
    process = requireNotNull(process) { "Global process rule is missing" },
    action = requireNotNull(action) { "Global action rule is missing" },
    mode = requireNotNull(mode) { "Global mode rule is missing" },
    interval = requireNotNull(interval) { "Global interval rule is missing" },
    timeRange = requireNotNull(timeRange) { "Global timeRange rule is missing" },
    keyword = requireNotNull(keyword) { "Global keyword rule is missing" },
    contentType = requireNotNull(contentType) { "Global contentType rule is missing" },
    maximumAge = requireNotNull(maximumAge) { "Global maximumAge rule is missing" },
)
