package dev.ujhhgtg.wekit.features.items.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationCodecs
import dev.ujhhgtg.wekit.features.automation.AutomationDelayRule
import dev.ujhhgtg.wekit.features.automation.AutomationFieldReader
import dev.ujhhgtg.wekit.features.automation.AutomationFieldWriter
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
import dev.ujhhgtg.wekit.features.automation.AutomationValueCodec
import dev.ujhhgtg.wekit.features.automation.localizedAutomationString
import dev.ujhhgtg.wekit.ui.automation.AutomationRuleEditor
import dev.ujhhgtg.wekit.ui.automation.AutomationSheetNote
import dev.ujhhgtg.wekit.ui.automation.delayEditor
import dev.ujhhgtg.wekit.ui.automation.keywordEditor
import dev.ujhhgtg.wekit.ui.automation.replyEditor
import dev.ujhhgtg.wekit.ui.automation.timeRangeEditor
import dev.ujhhgtg.wekit.ui.automation.toggleEditor
import java.math.BigDecimal
import java.math.RoundingMode

/** Hierarchical rules of [AutoAcceptTransfers]. */
object TransferAutomation : AutomationSpec("TRANSFER") {
    data class AmountRangeRule(
        val enabled: Boolean = false,
        val minimumYuan: String = "",
        val maximumYuan: String = "",
    ) {
        fun matches(totalFeeCents: Long): Boolean {
            if (!enabled) return true
            val minimum = minimumYuan.toCentsOrNull()
            val maximum = maximumYuan.toCentsOrNull()
            if (minimumYuan.isNotBlank() && minimum == null) return false
            if (maximumYuan.isNotBlank() && maximum == null) return false
            if (minimum != null && totalFeeCents < minimum) return false
            if (maximum != null && totalFeeCents > maximum) return false
            return true
        }
    }

    private val amountRangeCodec = object : AutomationValueCodec<AmountRangeRule> {
        override fun write(value: AmountRangeRule, out: AutomationFieldWriter) {
            out.put("enabled", value.enabled)
            out.put("minimumYuan", value.minimumYuan)
            out.put("maximumYuan", value.maximumYuan)
        }

        override fun read(input: AutomationFieldReader) = AmountRangeRule(
            input.boolean("enabled"), input.string("minimumYuan"), input.string("maximumYuan"),
        )
    }

    val accept = AutomationRuleKey("accept", AutomationCodecs.toggle, AutomationToggleRule(enabled = true))
    val timeRange = AutomationRuleKey("timeRange", AutomationCodecs.timeRange, AutomationTimeRangeRule())
    val amountRange = AutomationRuleKey("amountRange", amountRangeCodec, AmountRangeRule())
    val memoKeyword = AutomationRuleKey("memoKeyword", AutomationCodecs.keyword, AutomationKeywordRule())
    val delay = AutomationRuleKey("delay", AutomationCodecs.delay, AutomationDelayRule())
    val notification = AutomationRuleKey("notification", AutomationCodecs.toggle, AutomationToggleRule())
    val reply = AutomationRuleKey("reply", AutomationCodecs.reply, AutomationReplyRule())

    override val keys = listOf(accept, timeRange, amountRange, memoKeyword, delay, notification, reply)

    override val scopeTypes = setOf(
        AutomationScopeType.GLOBAL, AutomationScopeType.CATEGORY, AutomationScopeType.CONTACT,
        AutomationScopeType.GROUP_MEMBER, AutomationScopeType.PRESET,
    )
    override val categories = listOf(AutomationTalkerCategory.PRIVATE, AutomationTalkerCategory.GROUP)

    override val titleRes = R.string.feature_auto_accept_transfers_name
    override val globalSummaryRes = R.string.payment_transfer_global_summary

    val placeholders = AutomationReplyExecutor.BASE_PLACEHOLDERS + $$"$amount"

    override val editors = listOf(
        toggleEditor(
            accept, R.string.payment_transfer_accept, R.string.payment_transfer_accept_scope, R.string.payment_transfer_skip_scope,
            globalTitleRes = R.string.payment_transfer_default_accept,
        ),
        timeRangeEditor(timeRange, R.string.payment_transfer_time_range, R.string.payment_transfer_time_unrestricted),
        AutomationRuleEditor(
            key = amountRange,
            titleRes = R.string.payment_transfer_amount_range,
            summary = { rule ->
                if (!rule.enabled) stringResource(R.string.payment_transfer_amount_unrestricted)
                else {
                    val unrestricted = stringResource(R.string.unrestricted)
                    stringResource(
                        R.string.payment_transfer_amount_summary,
                        rule.minimumYuan.ifBlank { unrestricted },
                        rule.maximumYuan.ifBlank { unrestricted },
                    )
                }
            },
            isOn = { it.enabled },
            setOn = { rule, on -> rule.copy(enabled = on) },
            sheet = { _, value, onChange ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        R.string.payment_transfer_minimum_amount to value.minimumYuan,
                        R.string.payment_transfer_maximum_amount to value.maximumYuan,
                    ).forEach { (labelRes, current) ->
                        OutlinedTextField(
                            value = current,
                            onValueChange = { input ->
                                val amount = sanitizeAmount(input)
                                onChange(if (labelRes == R.string.payment_transfer_minimum_amount) value.copy(minimumYuan = amount) else value.copy(maximumYuan = amount))
                            },
                            label = { Text(stringResource(labelRes)) },
                            prefix = { Text("¥") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                AutomationSheetNote(stringResource(R.string.payment_transfer_amount_hint))
            },
        ),
        keywordEditor(memoKeyword, R.string.payment_transfer_memo_keywords, R.string.payment_transfer_memo_unrestricted),
        delayEditor(delay, R.string.payment_transfer_delay, R.string.payment_transfer_immediate),
        toggleEditor(
            notification, R.string.payment_transfer_notification,
            R.string.payment_transfer_notification_enabled, R.string.automation_notification_disabled,
        ),
        replyEditor(reply, R.string.payment_transfer_auto_reply, placeholders),
    )

    override val sections = listOf(
        AutomationSection(R.string.payment_transfer_section_conditions, listOf(accept, timeRange, amountRange, memoKeyword)),
        AutomationSection(R.string.payment_transfer_section_method, listOf(delay)),
        AutomationSection(R.string.payment_transfer_section_after, listOf(notification, reply)),
    )

    override fun validate(rules: AutomationRules, keyIds: Set<String>?): String? {
        val amount = rules[amountRange]
        if (validates(keyIds, amountRange) && amount.enabled) {
            if (amount.minimumYuan.isBlank() && amount.maximumYuan.isBlank()) {
                return localizedAutomationString(R.string.payment_transfer_amount_boundary_required)
            }
            val minimum = amount.minimumYuan.toCentsOrNull()
            val maximum = amount.maximumYuan.toCentsOrNull()
            if (amount.minimumYuan.isNotBlank() && minimum == null) {
                return localizedAutomationString(R.string.payment_transfer_invalid_minimum)
            }
            if (amount.maximumYuan.isNotBlank() && maximum == null) {
                return localizedAutomationString(R.string.payment_transfer_invalid_maximum)
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                return localizedAutomationString(R.string.payment_transfer_minimum_above_maximum)
            }
        }
        if (validates(keyIds, memoKeyword)) {
            rules[memoKeyword].validationError(localizedAutomationString(R.string.payment_transfer_memo_keywords))?.let { return it }
        }
        if (validates(keyIds, delay)) rules[delay].validationError()?.let { return it }
        if (validates(keyIds, reply)) rules[reply].validationError()?.let { return it }
        return null
    }

    private fun sanitizeAmount(value: String): String {
        val filtered = value.filter { it.isDigit() || it == '.' }.take(14)
        val dot = filtered.indexOf('.')
        if (dot < 0) return filtered
        return filtered.take(dot + 1) + filtered.drop(dot + 1).filter(Char::isDigit).take(2)
    }

    private fun String.toCentsOrNull(): Long? {
        if (isBlank()) return null
        return runCatching {
            BigDecimal(this)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
                .takeIf { it >= 0L }
        }.getOrNull()
    }
}
