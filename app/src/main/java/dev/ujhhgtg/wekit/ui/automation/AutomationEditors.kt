package dev.ujhhgtg.wekit.ui.automation

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTargetRule
import dev.ujhhgtg.wekit.features.automation.AutomationTextRule
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.features.automation.AutomationToggleRule
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumnScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

/** What an editor may ask the hosting screen for. */
class AutomationEditorContext(
    val activity: ComponentActivity,
    val scope: AutomationScope,
    val editText: (AutomationTextEditRequest) -> Unit,
    /** Opens the system file picker; picked files are copied into module storage. */
    val pickFiles: (mimeTypes: Array<String>, onPicked: (List<String>) -> Unit) -> Unit,
)

typealias AutomationEditorDetails<T> =
        SegmentedColumnScope.(context: AutomationEditorContext, value: T, editable: Boolean, onChange: (T) -> Unit) -> Unit

/**
 * UI for one [AutomationRuleKey]: a header row (switch when [isOn]/[setOn] are given, otherwise a
 * plain summary row) followed by [details], shown while the switch is on.
 */
class AutomationRuleEditor<T : Any>(
    val key: AutomationRuleKey<T>,
    @param:StringRes val titleRes: Int,
    val summary: @Composable (T) -> String,
    val isOn: ((T) -> Boolean)? = null,
    val setOn: ((T, Boolean) -> T)? = null,
    val details: AutomationEditorDetails<T>? = null,
    val visibleIn: (AutomationScope) -> Boolean = { true },
)

/**
 * Renders [editors]. [overridden] is null for plain editors (GLOBAL/ITEM scopes); otherwise it
 * holds the ids of rules the scope overrides and the rest show their inherited value.
 */
fun SegmentedColumnScope.automationRuleItems(
    editors: List<AutomationRuleEditor<*>>,
    rules: AutomationRules,
    overridden: Set<String>?,
    parentLabel: String,
    context: AutomationEditorContext,
    onActivate: (AutomationRuleKey<*>) -> Unit,
    onReset: (AutomationRuleKey<*>) -> Unit,
    onChange: (AutomationRuleKey<*>, Any) -> Unit,
) {
    editors.filter { it.visibleIn(context.scope) }.forEach { editor ->
        ruleEditorItems(editor, rules, overridden, parentLabel, context, onActivate, onReset, onChange)
    }
}

private fun <T : Any> SegmentedColumnScope.ruleEditorItems(
    editor: AutomationRuleEditor<T>,
    rules: AutomationRules,
    overridden: Set<String>?,
    parentLabel: String,
    context: AutomationEditorContext,
    onActivate: (AutomationRuleKey<*>) -> Unit,
    onReset: (AutomationRuleKey<*>) -> Unit,
    onChange: (AutomationRuleKey<*>, Any) -> Unit,
) {
    val key = editor.key
    val value = rules[key]
    val isOverridden = overridden?.let { key.id in it }
    val editable = isOverridden != false
    val change: (T) -> Unit = { onChange(key, it) }
    val isOn = editor.isOn
    val setOn = editor.setOn
    item(key = key.id) {
        if (isOn != null && setOn != null) {
            AutomationRuleRow(
                title = stringResource(editor.titleRes),
                summary = editor.summary(value),
                checked = isOn(value),
                overridden = isOverridden,
                parentLabel = parentLabel,
                onActivate = { onActivate(key) },
                onReset = { onReset(key) },
                onCheckedChange = { change(setOn(value, it)) },
            )
        } else {
            AutomationModeRuleRow(
                title = stringResource(editor.titleRes),
                summary = editor.summary(value),
                overridden = isOverridden,
                parentLabel = parentLabel,
                onActivate = { onActivate(key) },
                onReset = { onReset(key) },
            )
        }
    }
    val details = editor.details ?: return
    if (isOn != null && !isOn(value)) return
    details(context, value, editable, change)
}

// ---- builders -------------------------------------------------------------------------------

fun toggleEditor(
    key: AutomationRuleKey<AutomationToggleRule>,
    @StringRes titleRes: Int,
    @StringRes onRes: Int,
    @StringRes offRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { stringResource(if (it.enabled) onRes else offRes) },
    isOn = { it.enabled },
    setOn = { rule, on -> rule.copy(enabled = on) },
    visibleIn = visibleIn,
)

/** A choice among enum values without a switch; [options] maps each value to its label (+ description). */
fun <E : Enum<E>> choiceEditor(
    key: AutomationRuleKey<E>,
    @StringRes titleRes: Int,
    options: List<Pair<E, Int>>,
    descriptions: Map<E, Int> = emptyMap(),
    visibleIn: (AutomationScope) -> Boolean = { true },
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { value -> stringResource(options.first { it.first == value }.second) },
    details = choiceDetails(key.id, options, descriptions, { it }, { _, v -> v }),
    visibleIn = visibleIn,
)

/** Radio rows selecting one enum value inside a larger rule value. */
fun <T : Any, E : Enum<E>> choiceDetails(
    keyPrefix: String,
    options: List<Pair<E, Int>>,
    descriptions: Map<E, Int> = emptyMap(),
    get: (T) -> E,
    set: (T, E) -> T,
): AutomationEditorDetails<T> = { _, value, editable, onChange ->
    options.forEach { (option, labelRes) ->
        item(key = "${keyPrefix}_${option.name}") {
            RadioButtonWidget(
                iconPlaceholder = false,
                title = stringResource(labelRes),
                description = descriptions[option]?.let { stringResource(it) },
                selected = get(value) == option,
                enabled = editable,
                onClick = { onChange(set(value, option)) },
            )
        }
    }
}

/** Checkbox rows toggling members of a set inside a rule value. */
fun <T : Any, V> setDetails(
    keyPrefix: String,
    options: List<Pair<V, Int>>,
    get: (T) -> Set<V>,
    set: (T, Set<V>) -> T,
): AutomationEditorDetails<T> = { _, value, editable, onChange ->
    options.forEach { (option, labelRes) ->
        item(key = "${keyPrefix}_$option") {
            val selected = option in get(value)
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(labelRes),
                enabled = editable,
                onClick = { onChange(set(value, if (selected) get(value) - option else get(value) + option)) },
                trailingContent = { Checkbox(checked = selected, enabled = editable, onCheckedChange = null) },
            )
        }
    }
}

fun timeRangeEditor(
    key: AutomationRuleKey<AutomationTimeRangeRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = {
        if (it.enabled) "${formatAutomationMinute(it.startMinute)} - ${formatAutomationMinute(it.endMinute)}"
        else stringResource(offRes)
    },
    isOn = { it.enabled },
    setOn = { rule, on -> rule.copy(enabled = on) },
    details = { _, value, editable, onChange -> timeRangeItems(key.id, value, editable, true, onChange) },
    visibleIn = visibleIn,
)

fun keywordEditor(
    key: AutomationRuleKey<AutomationKeywordRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    modes: List<AutomationKeywordMode> = AutomationKeywordMode.entries,
    visibleIn: (AutomationScope) -> Boolean = { true },
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { automationKeywordSummary(it, stringResource(offRes)) },
    isOn = { it.enabled },
    setOn = { rule, on -> rule.copy(enabled = on) },
    details = { ctx, value, editable, onChange ->
        keywordItems(key.id, value, editable, true, modes, onChange, ctx.editText)
    },
    visibleIn = visibleIn,
)

fun delayEditor(
    key: AutomationRuleKey<AutomationDelayRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = {
        if (it.enabled) {
            stringResource(R.string.automation_delay_summary, it.baseMs.ifBlank { "0" }, it.randomRangeMs.ifBlank { "0" })
        } else stringResource(offRes)
    },
    isOn = { it.enabled },
    setOn = { rule, on -> rule.copy(enabled = on) },
    details = { ctx, value, editable, onChange ->
        delayItems(
            keyPrefix = key.id,
            baseMs = value.baseMs,
            randomRangeMs = value.randomRangeMs,
            editable = editable,
            visible = true,
            maxDigits = 7,
            onBaseChange = { onChange(value.copy(baseMs = it)) },
            onRandomRangeChange = { onChange(value.copy(randomRangeMs = it)) },
            onEditText = ctx.editText,
        )
    },
)

/** A switched non-negative number; [valueRes] formats the summary with the number. */
fun limitEditor(
    key: AutomationRuleKey<AutomationLimitRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    @StringRes valueRes: Int,
    @StringRes fieldRes: Int,
    maxDigits: Int = 7,
    visibleIn: (AutomationScope) -> Boolean = { true },
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { if (it.enabled) stringResource(valueRes, it.value.toString()) else stringResource(offRes) },
    isOn = { it.enabled },
    setOn = { rule, on -> rule.copy(enabled = on) },
    details = numberDetails(key.id, fieldRes, maxDigits, { it.value.toString() }, { v, s -> v.copy(value = s.toLongOrNull() ?: 0L) }),
    visibleIn = visibleIn,
)

/** A digits-only value row inside a rule value, edited through the text dialog. */
fun <T : Any> numberDetails(
    keyPrefix: String,
    @StringRes fieldRes: Int,
    maxDigits: Int,
    get: (T) -> String,
    set: (T, String) -> T,
): AutomationEditorDetails<T> = { ctx, value, editable, onChange ->
    item(key = "${keyPrefix}_value") {
        val title = stringResource(fieldRes)
        AutomationValueRow(
            title = title,
            value = get(value),
            enabled = editable,
            valueHint = "0",
            onClick = {
                ctx.editText(
                    AutomationTextEditRequest(
                        title = title,
                        initial = get(value),
                        keyboardType = KeyboardType.Number,
                        filter = { it.filter(Char::isDigit).take(maxDigits) },
                        onCommit = { onChange(set(value, it)) },
                    )
                )
            },
        )
    }
}

/** A free-form text row inside a rule value; [placeholders] are listed in the edit dialog. */
fun <T : Any> textDetails(
    keyPrefix: String,
    @StringRes fieldRes: Int,
    get: (T) -> String,
    set: (T, String) -> T,
    placeholders: List<String> = emptyList(),
    singleLine: Boolean = true,
): AutomationEditorDetails<T> = { ctx, value, editable, onChange ->
    item(key = "${keyPrefix}_text") {
        val title = stringResource(fieldRes)
        val hint = placeholders.takeIf { it.isNotEmpty() }
            ?.let { stringResource(R.string.automation_available_placeholders, it.joinToString(" ")) }
        AutomationValueRow(
            title = title,
            value = get(value),
            enabled = editable,
            valueHint = hint,
            onClick = {
                ctx.editText(
                    AutomationTextEditRequest(
                        title = title,
                        initial = get(value),
                        supportingText = hint,
                        singleLine = singleLine,
                        onCommit = { onChange(set(value, it)) },
                    )
                )
            },
        )
    }
}

fun textEditor(
    key: AutomationRuleKey<AutomationTextRule>,
    @StringRes titleRes: Int,
    @StringRes offRes: Int,
    @StringRes fieldRes: Int,
    placeholders: List<String> = emptyList(),
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { if (it.enabled) it.text.ifBlank { stringResource(offRes) } else stringResource(offRes) },
    isOn = { it.enabled },
    setOn = { rule, on -> rule.copy(enabled = on) },
    details = textDetails(key.id, fieldRes, { it.text }, { v, s -> v.copy(text = s) }, placeholders),
)

fun replyEditor(
    key: AutomationRuleKey<AutomationReplyRule>,
    @StringRes titleRes: Int,
    placeholders: List<String>,
    visibleIn: (AutomationScope) -> Boolean = { true },
    switchable: Boolean = true,
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { rule ->
        if (switchable && !rule.enabled) stringResource(R.string.automation_auto_reply_disabled)
        else pluralStringResource(R.plurals.automation_reply_step_count, rule.steps.size, rule.steps.size)
    },
    isOn = if (switchable) ({ it.enabled }) else null,
    setOn = if (switchable) ({ rule, on -> rule.copy(enabled = on) }) else null,
    details = { ctx, value, editable, onChange -> replyStepItems(key.id, value, editable, placeholders, ctx, onChange) },
    visibleIn = visibleIn,
)

val MESSAGE_KIND_LABELS = listOf(
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
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { rule ->
        if (rule.all) stringResource(R.string.automation_kinds_all)
        else pluralStringResource(R.plurals.automation_kinds_selected, rule.kinds.size, rule.kinds.size)
    },
    details = { ctx, value, editable, onChange ->
        choiceDetails<AutomationMessageTypeRule, KindsMode>(
            "${key.id}_mode",
            listOf(KindsMode.ALL to R.string.automation_kinds_all, KindsMode.SELECTED to R.string.automation_kinds_selected_mode),
            descriptions = mapOf(KindsMode.ALL to R.string.automation_kinds_all_summary),
            get = { if (it.all) KindsMode.ALL else KindsMode.SELECTED },
            set = { v, m -> v.copy(all = m == KindsMode.ALL) },
        )(ctx, value, editable, onChange)
        if (!value.all) {
            setDetails<AutomationMessageTypeRule, AutomationMessageKind>(
                "${key.id}_kind", MESSAGE_KIND_LABELS, { it.kinds }, { v, s -> v.copy(kinds = s) },
            )(ctx, value, editable, onChange)
        }
    },
    visibleIn = visibleIn,
)

enum class KindsMode { ALL, SELECTED }

/** Who a rule applies to: all chats, chat kinds, or specific contacts / group members. */
fun targetEditor(
    key: AutomationRuleKey<AutomationTargetRule>,
    @StringRes titleRes: Int,
    visibleIn: (AutomationScope) -> Boolean = { true },
    offerSpecific: Boolean = true,
) = AutomationRuleEditor(
    key = key,
    titleRes = titleRes,
    summary = { rule -> targetSummary(rule) },
    details = { ctx, value, editable, onChange ->
        val modes = buildList {
            add(AutomationTargetRule.Mode.ALL to R.string.automation_target_all)
            add(AutomationTargetRule.Mode.CATEGORIES to R.string.automation_target_categories)
            if (offerSpecific) add(AutomationTargetRule.Mode.SPECIFIC to R.string.automation_target_specific)
        }
        choiceDetails<AutomationTargetRule, AutomationTargetRule.Mode>(
            "${key.id}_mode", modes, get = { it.mode }, set = { v, m -> v.copy(mode = m) },
        )(ctx, value, editable, onChange)
        if (value.mode == AutomationTargetRule.Mode.CATEGORIES) {
            setDetails<AutomationTargetRule, AutomationTalkerCategory>(
                "${key.id}_category", CATEGORY_LABELS, { it.categories }, { v, s -> v.copy(categories = s) },
            )(ctx, value, editable, onChange)
        }
        if (value.mode == AutomationTargetRule.Mode.SPECIFIC) {
            contactSetItem("${key.id}_include", R.string.automation_target_include, value.include, editable, ctx) {
                onChange(value.copy(include = it))
            }
            memberSetItem("${key.id}_include_members", R.string.automation_target_include_members, value.includeMembers, editable, ctx) {
                onChange(value.copy(includeMembers = it))
            }
        }
        contactSetItem("${key.id}_exclude", R.string.automation_target_exclude, value.exclude, editable, ctx) {
            onChange(value.copy(exclude = it))
        }
        memberSetItem("${key.id}_exclude_members", R.string.automation_target_exclude_members, value.excludeMembers, editable, ctx) {
            onChange(value.copy(excludeMembers = it))
        }
    },
    visibleIn = visibleIn,
)

val CATEGORY_LABELS = listOf(
    AutomationTalkerCategory.PRIVATE to R.string.automation_category_private,
    AutomationTalkerCategory.GROUP to R.string.automation_category_group,
    AutomationTalkerCategory.OFFICIAL to R.string.automation_category_official,
)

@Composable
private fun targetSummary(rule: AutomationTargetRule): String {
    val base = when (rule.mode) {
        AutomationTargetRule.Mode.ALL -> stringResource(R.string.automation_target_all)
        AutomationTargetRule.Mode.CATEGORIES -> {
            val labels = CATEGORY_LABELS.associate { (category, res) -> category to stringResource(res) }
            rule.categories.sortedBy { it.ordinal }.joinToString(" · ") { labels.getValue(it) }
                .ifEmpty { stringResource(R.string.automation_target_nothing) }
        }
        AutomationTargetRule.Mode.SPECIFIC -> pluralStringResource(
            R.plurals.automation_target_specific_count,
            rule.include.size + rule.includeMembers.size,
            rule.include.size + rule.includeMembers.size,
        )
    }
    val excluded = rule.exclude.size + rule.excludeMembers.size
    return if (excluded == 0) base
    else base + " · " + pluralStringResource(R.plurals.automation_target_excluded_count, excluded, excluded)
}

/** Row showing a set of contact/group ids, edited with the multi-select contact picker. */
fun SegmentedColumnScope.contactSetItem(
    itemKey: String,
    @StringRes titleRes: Int,
    ids: Set<String>,
    editable: Boolean,
    context: AutomationEditorContext,
    kinds: Set<AutomationTalkerCategory> = setOf(AutomationTalkerCategory.PRIVATE, AutomationTalkerCategory.GROUP),
    onChange: (Set<String>) -> Unit,
) {
    item(key = itemKey) {
        val title = stringResource(titleRes)
        AutomationNavigationRow(
            title = title,
            description = if (ids.isEmpty()) stringResource(R.string.automation_none_selected)
            else pluralStringResource(R.plurals.automation_selected_count, ids.size, ids.size),
            enabled = editable,
            onClick = {
                val contacts = loadAutomationContacts(kinds)
                showComposeDialog(context.activity) {
                    ContactsSelector(
                        title = title,
                        contacts = contacts,
                        initialSelectedWxIds = ids,
                        onDismiss = onDismiss,
                    ) { selected ->
                        onChange(selected)
                        onDismiss()
                    }
                }
            },
        )
    }
}

/** Row showing `group/member` tokens; picking chooses a group, then members of it. */
fun SegmentedColumnScope.memberSetItem(
    itemKey: String,
    @StringRes titleRes: Int,
    tokens: Set<String>,
    editable: Boolean,
    context: AutomationEditorContext,
    fixedGroupId: String? = null,
    onChange: (Set<String>) -> Unit,
) {
    item(key = itemKey) {
        val title = stringResource(titleRes)
        AutomationNavigationRow(
            title = title,
            description = if (tokens.isEmpty()) stringResource(R.string.automation_none_selected)
            else pluralStringResource(R.plurals.automation_selected_count, tokens.size, tokens.size),
            enabled = editable,
            onClick = {
                val pickMembers = { groupId: String ->
                    val members = loadGroupMembers(groupId)
                    val prefix = "$groupId/"
                    showComposeDialog(context.activity) {
                        ContactsSelector(
                            title = WeDatabaseApi.getDisplayName(groupId),
                            contacts = members,
                            initialSelectedWxIds = tokens.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet(),
                            onDismiss = onDismiss,
                        ) { selected ->
                            onChange(tokens.filterNot { it.startsWith(prefix) }.toSet() + selected.map { prefix + it })
                            onDismiss()
                        }
                    }
                }
                if (fixedGroupId != null) {
                    pickMembers(fixedGroupId)
                } else {
                    val groups = loadAutomationContacts(setOf(AutomationTalkerCategory.GROUP))
                    showComposeDialog(context.activity) {
                        SingleContactSelector(
                            title = title,
                            contacts = groups,
                            initialSelectedWxId = null,
                            onDismiss = onDismiss,
                        ) { groupId ->
                            onDismiss()
                            pickMembers(groupId)
                        }
                    }
                }
            },
        )
    }
}

fun loadAutomationContacts(kinds: Set<AutomationTalkerCategory>): List<IWeContact> = runCatching {
    buildList<IWeContact> {
        if (AutomationTalkerCategory.PRIVATE in kinds) addAll(WeDatabaseApi.getFriends())
        if (AutomationTalkerCategory.GROUP in kinds) addAll(WeDatabaseApi.getGroups())
        if (AutomationTalkerCategory.OFFICIAL in kinds) addAll(WeDatabaseApi.getOfficialAccounts())
    }.distinctBy(IWeContact::wxId)
}.onFailure { WeLogger.e("AutomationEditors", "failed to load contacts", it) }.getOrDefault(emptyList())

fun loadGroupMembers(groupId: String): List<IWeContact> = runCatching { WeDatabaseApi.getGroupMembers(groupId) }
    .onFailure { WeLogger.e("AutomationEditors", "failed to load members of $groupId", it) }
    .getOrDefault(emptyList())

fun AutomationScope.isGroupScope(): Boolean = when (type) {
    AutomationScopeType.CATEGORY -> category == AutomationTalkerCategory.GROUP
    AutomationScopeType.CONTACT -> AutomationTalkerCategory.of(scopeId) == AutomationTalkerCategory.GROUP
    AutomationScopeType.GROUP_MEMBER -> true
    else -> true
}
