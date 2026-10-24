package dev.ujhhgtg.wekit.ui.automation

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.automation.AutomationDelayRule
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationLimitRule
import dev.ujhhgtg.wekit.features.automation.AutomationMessageKind
import dev.ujhhgtg.wekit.features.automation.AutomationMessageTypeRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTargetRule
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.ui.content.formatSecondOfDay
import dev.ujhhgtg.wekit.utils.WeLogger

/** What an editor may ask the hosting screen for. */
class AutomationEditorContext(
    val activity: ComponentActivity,
    /** The scope being edited; the first selected scope in a batch edit. */
    val scope: AutomationScope,
    /** Opens the system file picker; picked files are copied into module storage. */
    val pickFiles: (mimeTypes: Array<String>, onPicked: (List<String>) -> Unit) -> Unit,
    /** Opens full-screen pickers above the current screen and any open sheet. */
    val picker: AutomationPicker,
)

/** Controls shown inside a rule's row, below its header, while the rule is on. */
typealias AutomationInlineEditor<T> =
    @Composable (context: AutomationEditorContext, value: T, onChange: (T) -> Unit) -> Unit

/** Content of the bottom sheet that edits a rule; edits a draft committed by the sheet's Done. */
typealias AutomationSheetEditor<T> =
    @Composable
    ColumnScope.(context: AutomationEditorContext, value: T, onChange: (T) -> Unit) -> Unit

/**
 * UI for one [AutomationRuleKey]: a row showing [summary] (with a switch when [isOn]/[setOn] are
 * given), plus either [inline] controls under the row, a bottom [sheet] opened from the row, or the
 * reply sequence page ([opensReplyPage]).
 */
class AutomationRuleEditor<T : Any>(
    val key: AutomationRuleKey<T>,
    @param:StringRes val titleRes: Int,
    val summary: @Composable (T) -> String,
    val isOn: ((T) -> Boolean)? = null,
    val setOn: ((T, Boolean) -> T)? = null,
    /** Shown under the summary, e.g. keyword chips or the 24-hour bar of a time window. */
    val summaryExtra: (@Composable (T) -> Unit)? = null,
    val inline: AutomationInlineEditor<T>? = null,
    val sheet: AutomationSheetEditor<T>? = null,
    /** One-line explanation under the sheet title. */
    @param:StringRes val sheetHintRes: Int? = null,
    val opensReplyPage: Boolean = false,
    /** Placeholders offered by the reply page's text steps. */
    val replyPlaceholders: List<String> = emptyList(),
    /**
     * Title in the GLOBAL scope, where the rule is the default (e.g. "Claim red packets by
     * default").
     */
    @param:StringRes val globalTitleRes: Int? = null,
    val visibleIn: (AutomationScope) -> Boolean = { true },
) {
    val opensEditor: Boolean
        get() = sheet != null || opensReplyPage

    @StringRes
    fun titleFor(scope: AutomationScope): Int =
        if (scope.type == AutomationScopeType.GLOBAL && globalTitleRes != null) globalTitleRes
        else titleRes
}

// ---- builders -------------------------------------------------------------------------------

fun toggleEditor(
    key: AutomationRuleKey<AutomationToggleRule>,
    @StringRes titleRes: Int,
    @StringRes onRes: Int,
    @StringRes offRes: Int,
    @StringRes globalTitleRes: Int? = null,
    visibleIn: (AutomationScope) -> Boolean = { true },
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = { stringResource(if (it.enabled) onRes else offRes) },
        isOn = { it.enabled },
        setOn = { rule, on -> rule.copy(enabled = on) },
        globalTitleRes = globalTitleRes,
        visibleIn = visibleIn,
    )

/** A choice among enum values, shown as a connected button group under the row. */
fun <E : Enum<E>> choiceEditor(
    key: AutomationRuleKey<E>,
    @StringRes titleRes: Int,
    options: List<Pair<E, Int>>,
    visibleIn: (AutomationScope) -> Boolean = { true },
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = { value -> stringResource(options.first { it.first == value }.second) },
        inline = choiceInline(options, { it }, { _, v -> v }),
        visibleIn = visibleIn,
    )

/** Connected button group selecting one enum value inside a larger rule value. */
fun <T : Any, E : Enum<E>> choiceInline(
    options: List<Pair<E, Int>>,
    get: (T) -> E,
    set: (T, E) -> T,
    descriptions: Map<E, Int> = emptyMap(),
): AutomationInlineEditor<T> = { _, value, onChange ->
    AutomationChoiceGroup(
        options = options.map { (option, labelRes) -> option to stringResource(labelRes) },
        selected = get(value),
        onSelect = { onChange(set(value, it)) },
        description = descriptions[get(value)]?.let { stringResource(it) },
        modifier = Modifier.padding(AutomationInlinePadding),
    )
}

/** A switch inside a rule's row, e.g. "Reply as quote" under a quick-reply rule. */
fun <T : Any> switchInline(
    @StringRes titleRes: Int,
    get: (T) -> Boolean,
    set: (T, Boolean) -> T,
): AutomationInlineEditor<T> = { _, value, onChange ->
    Box(Modifier.padding(AutomationInlinePadding)) {
        AutomationInlineSwitch(stringResource(titleRes), get(value)) { onChange(set(value, it)) }
    }
}

fun timeRangeEditor(
    key: AutomationRuleKey<AutomationTimeRangeRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = {
            if (!it.enabled) stringResource(offRes)
            else if (it.endSecond < it.startSecond) {
                stringResource(
                    R.string.automation_time_overnight_summary,
                    formatSecondOfDay(it.startSecond),
                    formatSecondOfDay(it.endSecond),
                )
            } else "${formatSecondOfDay(it.startSecond)} – ${formatSecondOfDay(it.endSecond)}"
        },
        isOn = { it.enabled },
        setOn = { rule, on -> rule.copy(enabled = on) },
        summaryExtra = { if (it.enabled) AutomationDayLine(it.startSecond, it.endSecond) },
        sheet = { _, value, onChange -> TimeRangeSheetContent(value, onChange) },
        visibleIn = visibleIn,
    )

fun keywordEditor(
    key: AutomationRuleKey<AutomationKeywordRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    modes: List<AutomationKeywordMode> = AutomationKeywordMode.entries,
    @StringRes hintRes: Int? = null,
    visibleIn: (AutomationScope) -> Boolean = { true },
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = { automationKeywordSummary(it, stringResource(offRes)) },
        isOn = { it.enabled },
        setOn = { rule, on -> rule.copy(enabled = on) },
        summaryExtra = {
            if (it.enabled && it.mode != AutomationKeywordMode.REGEX)
                AutomationMiniChips(it.strings)
        },
        sheet = { _, value, onChange -> KeywordSheetContent(value, modes, onChange) },
        sheetHintRes = hintRes,
        visibleIn = visibleIn,
    )

fun delayEditor(
    key: AutomationRuleKey<AutomationDelayRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = {
            if (!it.enabled) stringResource(offRes)
            else {
                val base = it.baseMs.toLongOrNull() ?: 0L
                val range = it.randomRangeMs.toLongOrNull() ?: 0L
                stringResource(
                    R.string.automation_delay_summary,
                    base,
                    range,
                    (base - range).coerceAtLeast(0L),
                    base + range,
                )
            }
        },
        isOn = { it.enabled },
        setOn = { rule, on -> rule.copy(enabled = on) },
        sheet = { _, value, onChange -> DelaySheetContent(value, onChange) },
    )

/** A switched non-negative number edited with a slider (and exact input) under the row. */
fun limitEditor(
    key: AutomationRuleKey<AutomationLimitRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    @StringRes valueRes: Int,
    @StringRes fieldRes: Int,
    range: IntRange,
    step: Int,
    @StringRes unitRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = {
            if (it.enabled) stringResource(valueRes, it.value.toString())
            else stringResource(offRes)
        },
        isOn = { it.enabled },
        setOn = { rule, on -> rule.copy(enabled = on) },
        inline =
            numberInline(
                fieldRes,
                range,
                step,
                unitRes,
                { it.value },
                { v, n -> v.copy(value = n) },
            ),
        visibleIn = visibleIn,
    )

/**
 * A number slider over [range] in [step]s; tapping the value types an exact number, which may
 * exceed the slider's range up to [maxExact].
 */
fun <T : Any> numberInline(
    @StringRes fieldRes: Int,
    range: IntRange,
    step: Int,
    @StringRes unitRes: Int,
    get: (T) -> Long,
    set: (T, Long) -> T,
    maxExact: Long = 9_999_999L,
): AutomationInlineEditor<T> = { _, value, onChange ->
    AutomationNumberSlider(
        title = stringResource(fieldRes),
        value = get(value),
        range = range,
        step = step,
        unit = stringResource(unitRes),
        maxExact = maxExact,
        onChange = { onChange(set(value, it)) },
    )
}

fun replyEditor(
    key: AutomationRuleKey<AutomationReplyRule>,
    @StringRes titleRes: Int,
    placeholders: List<String>,
    visibleIn: (AutomationScope) -> Boolean = { true },
    switchable: Boolean = true,
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = { rule ->
            when {
                switchable && !rule.enabled ->
                    stringResource(R.string.automation_auto_reply_disabled)
                rule.steps.isEmpty() -> stringResource(R.string.automation_reply_no_steps)
                else ->
                    pluralStringResource(
                        R.plurals.automation_reply_step_count,
                        rule.steps.size,
                        rule.steps.size,
                    )
            }
        },
        isOn = if (switchable) ({ it.enabled }) else null,
        setOn = if (switchable) ({ rule, on -> rule.copy(enabled = on) }) else null,
        summaryExtra = { rule ->
            if (!switchable || rule.enabled) {
                AutomationMiniChips(rule.steps.map { step -> replyStepChip(step) })
            }
        },
        opensReplyPage = true,
        replyPlaceholders = placeholders,
        visibleIn = visibleIn,
    )

val MESSAGE_KIND_LABELS =
    listOf(
        AutomationMessageKind.TEXT to R.string.automation_kind_text,
        AutomationMessageKind.QUOTE to R.string.automation_kind_quote,
        AutomationMessageKind.IMAGE to R.string.automation_kind_image,
        AutomationMessageKind.VIDEO to R.string.automation_kind_video,
        AutomationMessageKind.VOICE to R.string.automation_kind_voice,
        AutomationMessageKind.LINK to R.string.automation_kind_link,
        AutomationMessageKind.MUSIC to R.string.automation_kind_music,
        AutomationMessageKind.MINI_PROGRAM to R.string.automation_kind_mini_program,
        AutomationMessageKind.CARD to R.string.automation_kind_card,
        AutomationMessageKind.EMOJI to R.string.automation_kind_emoji,
        AutomationMessageKind.RED_PACKET to R.string.automation_kind_red_packet,
        AutomationMessageKind.TRANSFER to R.string.automation_kind_transfer,
        AutomationMessageKind.VOIP to R.string.automation_kind_voip,
        AutomationMessageKind.LOCATION to R.string.automation_kind_location,
        AutomationMessageKind.SYSTEM to R.string.automation_kind_system,
        AutomationMessageKind.PAT to R.string.automation_kind_pat,
        AutomationMessageKind.CHANNELS_VIDEO to R.string.automation_kind_channels_video,
        AutomationMessageKind.UNKNOWN to R.string.automation_kind_unknown,
    )

/** All message kinds, or a chosen subset. */
fun messageTypeEditor(
    key: AutomationRuleKey<AutomationMessageTypeRule>,
    @StringRes titleRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = { rule ->
            if (rule.all) stringResource(R.string.automation_kinds_all)
            else
                pluralStringResource(
                    R.plurals.automation_kinds_selected,
                    rule.kinds.size,
                    rule.kinds.size,
                )
        },
        summaryExtra = { rule ->
            if (!rule.all)
                AutomationMiniChips(
                    MESSAGE_KIND_LABELS.filter { it.first in rule.kinds }
                        .map { stringResource(it.second) },
                )
        },
        sheet = { _, value, onChange ->
            AutomationChoiceGroup(
                options =
                    listOf(
                        true to stringResource(R.string.automation_kinds_all),
                        false to stringResource(R.string.automation_kinds_selected_mode),
                    ),
                selected = value.all,
                onSelect = { onChange(value.copy(all = it)) },
            )
            if (value.all) {
                AutomationSheetNote(stringResource(R.string.automation_kinds_all_summary))
            } else {
                AutomationChipSet(
                    options =
                        MESSAGE_KIND_LABELS.map { (kind, res) -> kind to stringResource(res) },
                    selected = value.kinds,
                    onChange = { onChange(value.copy(kinds = it)) },
                )
            }
        },
        visibleIn = visibleIn,
    )

/** Who a rule applies to: all chats, chat kinds, or specific contacts / group members. */
fun targetEditor(
    key: AutomationRuleKey<AutomationTargetRule>,
    @StringRes titleRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
    offerSpecific: Boolean = true,
) =
    AutomationRuleEditor(
        key = key,
        titleRes = titleRes,
        summary = { rule -> targetSummary(rule) },
        sheet = { ctx, value, onChange -> TargetSheetContent(ctx, value, offerSpecific, onChange) },
        visibleIn = visibleIn,
    )

val CATEGORY_LABELS =
    listOf(
        AutomationTalkerCategory.PRIVATE to R.string.automation_category_private,
        AutomationTalkerCategory.GROUP to R.string.automation_category_group,
        AutomationTalkerCategory.OFFICIAL to R.string.automation_category_official,
    )

@Composable
private fun targetSummary(rule: AutomationTargetRule): String {
    val base =
        when (rule.mode) {
            AutomationTargetRule.Mode.ALL -> stringResource(R.string.automation_target_all)
            AutomationTargetRule.Mode.CATEGORIES -> {
                val labels = CATEGORY_LABELS.associate { (category, res) ->
                    category to stringResource(res)
                }
                rule.categories
                    .sortedBy { it.ordinal }
                    .joinToString(" · ") { labels.getValue(it) }
                    .ifEmpty { stringResource(R.string.automation_target_nothing) }
            }
            AutomationTargetRule.Mode.SPECIFIC ->
                pluralStringResource(
                    R.plurals.automation_target_specific_count,
                    rule.include.size + rule.includeMembers.size,
                    rule.include.size + rule.includeMembers.size,
                )
        }
    val excluded = rule.exclude.size + rule.excludeMembers.size
    return if (excluded == 0) base
    else
        base +
            " · " +
            pluralStringResource(R.plurals.automation_target_excluded_count, excluded, excluded)
}

@Composable
fun automationKeywordSummary(rule: AutomationKeywordRule, unrestrictedText: String): String {
    if (!rule.enabled) return unrestrictedText
    return when (rule.mode) {
        AutomationKeywordMode.STRING_LIST ->
            pluralStringResource(
                R.plurals.automation_keyword_contains_summary,
                rule.strings.size,
                rule.strings.size,
            )
        AutomationKeywordMode.EXACT ->
            pluralStringResource(
                R.plurals.automation_keyword_exact_summary,
                rule.strings.size,
                rule.strings.size,
            )
        AutomationKeywordMode.REGEX ->
            if (rule.regex.isBlank()) {
                stringResource(R.string.automation_regex_empty_summary)
            } else {
                stringResource(R.string.automation_regex_summary_value, rule.regex)
            }
    }
}

fun loadAutomationContacts(kinds: Set<AutomationTalkerCategory>): List<IWeContact> = runCatching {
    buildList<IWeContact> {
            if (AutomationTalkerCategory.PRIVATE in kinds) addAll(WeDatabaseApi.getFriends())
            if (AutomationTalkerCategory.GROUP in kinds) addAll(WeDatabaseApi.getGroups())
            if (AutomationTalkerCategory.OFFICIAL in kinds)
                addAll(WeDatabaseApi.getOfficialAccounts())
        }
        .distinctBy(IWeContact::wxId)
}
    .onFailure { WeLogger.e("AutomationEditors", "failed to load contacts", it) }
    .getOrDefault(emptyList())

fun loadGroupMembers(groupId: String): List<IWeContact> = runCatching {
    WeDatabaseApi.getGroupMembers(groupId)
}
    .onFailure { WeLogger.e("AutomationEditors", "failed to load members of $groupId", it) }
    .getOrDefault(emptyList())

fun AutomationScope.isGroupScope(): Boolean =
    when (type) {
        AutomationScopeType.CATEGORY -> category == AutomationTalkerCategory.GROUP
        AutomationScopeType.CONTACT ->
            AutomationTalkerCategory.of(scopeId) == AutomationTalkerCategory.GROUP
        AutomationScopeType.GROUP_MEMBER -> true
        else -> true
    }
