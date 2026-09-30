package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Cancel
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Error
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationDelayRule
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.AutomationTargetRule
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.WeTimeOfDaySecondsField
import dev.ujhhgtg.wekit.ui.content.formatSecondOfDay

private val KEYWORD_MODE_LABELS = mapOf(
    AutomationKeywordMode.STRING_LIST to R.string.automation_keyword_mode_contains,
    AutomationKeywordMode.EXACT to R.string.automation_keyword_mode_exact,
    AutomationKeywordMode.REGEX to R.string.automation_keyword_mode_regex,
)

// ---- keywords -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.KeywordSheetContent(
    value: AutomationKeywordRule,
    modes: List<AutomationKeywordMode>,
    onChange: (AutomationKeywordRule) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(stringResource(R.string.automation_keyword_mode_label))
        AutomationChoiceGroup(
            options = modes.map { it to stringResource(KEYWORD_MODE_LABELS.getValue(it)) },
            selected = value.mode,
            onSelect = { onChange(value.copy(mode = it)) },
        )
    }
    if (value.mode == AutomationKeywordMode.REGEX) {
        val invalid = value.regex.isNotEmpty() && runCatching { Regex(value.regex) }.isFailure
        OutlinedTextField(
            value = value.regex,
            onValueChange = { onChange(value.copy(regex = it)) },
            label = { Text(stringResource(R.string.automation_keyword_mode_regex)) },
            isError = invalid,
            supportingText = { Text(stringResource(if (invalid) R.string.automation_regex_invalid else R.string.automation_regex_hint)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        var pending by remember { mutableStateOf("") }
        val add = {
            val keyword = pending.trim()
            if (keyword.isNotEmpty() && keyword !in value.strings) onChange(value.copy(strings = value.strings + keyword))
            pending = ""
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomationFieldLabel(stringResource(R.string.automation_keywords_label, value.strings.size))
            if (value.strings.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    value.strings.forEach { keyword ->
                        InputChip(
                            selected = false,
                            onClick = { onChange(value.copy(strings = value.strings - keyword)) },
                            label = { Text(keyword) },
                            trailingIcon = {
                                Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.action_delete), Modifier.size(InputChipDefaults.IconSize))
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = pending,
                onValueChange = { pending = it },
                label = { Text(stringResource(R.string.automation_new_keyword)) },
                supportingText = {
                    Text(stringResource(if (value.mode == AutomationKeywordMode.EXACT) R.string.automation_keyword_exact_hint else R.string.automation_keyword_contains_hint))
                },
                trailingIcon = {
                    IconButton(enabled = pending.isNotBlank(), onClick = add) {
                        Icon(MaterialSymbols.Outlined.Add, stringResource(R.string.automation_add_item))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    AutomationInlineSwitch(stringResource(R.string.automation_ignore_case), value.ignoreCase) { onChange(value.copy(ignoreCase = it)) }
    KeywordTester(value)
}

/** Lets the user type a message and see whether (and on which keyword) the rule matches it. */
@Composable
private fun KeywordTester(rule: AutomationKeywordRule) {
    var sample by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sample,
                onValueChange = { sample = it },
                label = { Text(stringResource(R.string.automation_keyword_test)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val (icon, color, text) = keywordTestResult(rule, sample)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    }
}

@Composable
private fun keywordTestResult(rule: AutomationKeywordRule, sample: String): Triple<androidx.compose.ui.graphics.vector.ImageVector?, Color, String> {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (sample.isEmpty()) return Triple(null, muted, stringResource(R.string.automation_keyword_test_hint))
    if (rule.mode == AutomationKeywordMode.REGEX) {
        val regex = runCatching { Regex(rule.regex, if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }.getOrNull()
            ?: return Triple(MaterialSymbols.Outlined.Error, MaterialTheme.colorScheme.error, stringResource(R.string.automation_regex_invalid))
        val match = regex.find(sample)
        return if (match != null) Triple(MaterialSymbols.Outlined.Check_circle, MaterialTheme.colorScheme.primary, stringResource(R.string.automation_keyword_test_regex_hit, match.value))
        else Triple(MaterialSymbols.Outlined.Cancel, muted, stringResource(R.string.automation_keyword_test_miss))
    }
    val hit = rule.strings.map(String::trim).filter(String::isNotEmpty).firstOrNull {
        if (rule.mode == AutomationKeywordMode.EXACT) sample.equals(it, rule.ignoreCase) else sample.contains(it, rule.ignoreCase)
    }
    return if (hit != null) Triple(MaterialSymbols.Outlined.Check_circle, MaterialTheme.colorScheme.primary, stringResource(R.string.automation_keyword_test_hit, hit))
    else Triple(MaterialSymbols.Outlined.Cancel, muted, stringResource(R.string.automation_keyword_test_miss))
}

// ---- time window ----------------------------------------------------------------------------

private val TIME_PRESETS = listOf(
    Triple(R.string.automation_time_preset_night, 23, 7),
    Triple(R.string.automation_time_preset_day, 8, 23),
    Triple(R.string.automation_time_preset_work, 9, 18),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.TimeRangeSheetContent(value: AutomationTimeRangeRule, onChange: (AutomationTimeRangeRule) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WeTimeOfDaySecondsField(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.automation_start),
            secondOfDay = value.startSecond,
            onSecondChange = { onChange(value.copy(startSecond = it)) },
        )
        WeTimeOfDaySecondsField(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.automation_end),
            secondOfDay = value.endSecond,
            onSecondChange = { onChange(value.copy(endSecond = it)) },
        )
    }
    Column {
        AutomationDayLine(value.startSecond, value.endSecond, height = 14)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val duration = ((value.endSecond - value.startSecond + 86400) % 86400).let { if (it == 0) 86400 else it }
        val hours = duration / 3600
        val minutes = duration % 3600 / 60
        AutomationSheetNote(
            when {
                value.startSecond == value.endSecond -> stringResource(R.string.automation_time_all_day)
                value.endSecond < value.startSecond -> stringResource(
                    R.string.automation_time_overnight_detail,
                    formatSecondOfDay(value.startSecond), formatSecondOfDay(value.endSecond), hours, minutes,
                )
                else -> stringResource(R.string.automation_time_detail, formatSecondOfDay(value.startSecond), formatSecondOfDay(value.endSecond), hours, minutes)
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(stringResource(R.string.automation_common_values))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TIME_PRESETS.forEach { (labelRes, start, end) ->
                SuggestionChip(
                    onClick = { onChange(value.copy(startSecond = start * 3600, endSecond = end * 3600)) },
                    label = { Text(stringResource(labelRes, start, end)) },
                )
            }
        }
    }
}

// ---- delay ----------------------------------------------------------------------------------

private val DELAY_PRESETS = listOf(0L to 0L, 500L to 300L, 1500L to 500L, 3000L to 1000L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.DelaySheetContent(value: AutomationDelayRule, onChange: (AutomationDelayRule) -> Unit) {
    val base = value.baseMs.toLongOrNull() ?: 0L
    val range = value.randomRangeMs.toLongOrNull() ?: 0L
    val ms = stringResource(R.string.automation_unit_ms)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AutomationNumberSlider(
            title = stringResource(R.string.automation_base_delay),
            value = base, range = 0..10_000, step = 100, unit = ms, maxExact = 600_000,
            onChange = { onChange(value.copy(baseMs = it.toString())) },
        )
        AutomationNumberSlider(
            title = stringResource(R.string.automation_random_delay),
            value = range, range = 0..5_000, step = 100, unit = ms, maxExact = 60_000,
            onChange = { onChange(value.copy(randomRangeMs = it.toString())) },
        )
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val low = (base - range).coerceAtLeast(0L)
            val high = base + range
            Text(stringResource(R.string.automation_delay_preview, low, high), style = MaterialTheme.typography.titleSmall)
            val scale = maxOf(15_000L, high).toFloat()
            AutomationFractionBar(listOf(low / scale to maxOf(high / scale, low / scale + 0.01f)), modifier = Modifier.padding(top = 4.dp))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(stringResource(R.string.automation_common_values))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DELAY_PRESETS.forEach { (presetBase, presetRange) ->
                SuggestionChip(
                    onClick = { onChange(value.copy(baseMs = presetBase.toString(), randomRangeMs = presetRange.toString())) },
                    label = {
                        Text(if (presetBase == 0L) stringResource(R.string.automation_delay_preset_now) else "$presetBase ± $presetRange $ms")
                    },
                )
            }
        }
    }
}

// ---- target ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.TargetSheetContent(
    context: AutomationEditorContext,
    value: AutomationTargetRule,
    offerSpecific: Boolean,
    onChange: (AutomationTargetRule) -> Unit,
) {
    AutomationChoiceGroup(
        options = buildList {
            add(AutomationTargetRule.Mode.ALL to stringResource(R.string.automation_target_all))
            add(AutomationTargetRule.Mode.CATEGORIES to stringResource(R.string.automation_target_categories))
            if (offerSpecific) add(AutomationTargetRule.Mode.SPECIFIC to stringResource(R.string.automation_target_specific))
        },
        selected = value.mode,
        onSelect = { onChange(value.copy(mode = it)) },
    )
    if (value.mode == AutomationTargetRule.Mode.CATEGORIES) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomationFieldLabel(stringResource(R.string.automation_target_categories))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORY_LABELS.forEach { (category, res) ->
                    val on = category in value.categories
                    FilterChip(
                        selected = on,
                        onClick = { onChange(value.copy(categories = if (on) value.categories - category else value.categories + category)) },
                        label = { Text(stringResource(res)) },
                        leadingIcon = if (on) {
                            { Icon(MaterialSymbols.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
        }
    }
    val allKinds = AutomationTalkerCategory.entries.toSet()
    if (value.mode == AutomationTargetRule.Mode.SPECIFIC) {
        ContactChipsField(context, stringResource(R.string.automation_target_include), value.include, allKinds) { onChange(value.copy(include = it)) }
        MemberChipsField(context, stringResource(R.string.automation_target_include_members), value.includeMembers) { onChange(value.copy(includeMembers = it)) }
    }
    ContactChipsField(context, stringResource(R.string.automation_target_exclude), value.exclude, allKinds, excluding = true) { onChange(value.copy(exclude = it)) }
    MemberChipsField(context, stringResource(R.string.automation_target_exclude_members), value.excludeMembers, excluding = true) { onChange(value.copy(excludeMembers = it)) }
}

/** Contacts / groups as avatar chips, plus a chip that opens the multi-select contact picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactChipsField(
    context: AutomationEditorContext,
    title: String,
    ids: Set<String>,
    kinds: Set<AutomationTalkerCategory>,
    excluding: Boolean = false,
    onChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ids.forEach { id ->
                InputChip(
                    selected = false,
                    onClick = { onChange(ids - id) },
                    label = { Text(rememberContactName(id)) },
                    avatar = { AutomationAvatar(id, InputChipDefaults.AvatarSize) },
                    trailingIcon = { Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.action_delete), Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
            AssistChip(
                onClick = {
                    context.picker.open(
                        AutomationPick.Contacts(
                            title = title,
                            loadContacts = { loadAutomationContacts(kinds) },
                            initialSelected = ids,
                            onConfirm = onChange,
                        )
                    )
                },
                label = { Text(stringResource(if (excluding) R.string.automation_exclude else R.string.automation_add_item)) },
                leadingIcon = { Icon(MaterialSymbols.Outlined.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
    }
}

/** `groupId/memberId` tokens as chips; adding picks a group, then members of it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberChipsField(
    context: AutomationEditorContext,
    title: String,
    tokens: Set<String>,
    excluding: Boolean = false,
    onChange: (Set<String>) -> Unit,
) {
    val groupTitle = stringResource(R.string.automation_pick_group_first)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tokens.forEach { token ->
                val groupId = token.substringBefore('/', "")
                val memberId = token.substringAfter('/')
                val label = if (groupId.isEmpty()) memberId else "${rememberContactName(groupId)} · ${rememberMemberName(groupId, memberId)}"
                InputChip(
                    selected = false,
                    onClick = { onChange(tokens - token) },
                    label = { Text(label) },
                    avatar = { AutomationAvatar(memberId, InputChipDefaults.AvatarSize) },
                    trailingIcon = { Icon(MaterialSymbols.Outlined.Close, stringResource(R.string.action_delete), Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
            AssistChip(
                onClick = {
                    context.picker.open(
                        AutomationPick.Contact(
                            title = groupTitle,
                            loadContacts = { loadAutomationContacts(setOf(AutomationTalkerCategory.GROUP)) },
                            onPick = { group ->
                                val prefix = group.wxId + "/"
                                context.picker.open(
                                    AutomationPick.Contacts(
                                        title = group.displayName,
                                        loadContacts = { loadGroupMembers(group.wxId) },
                                        initialSelected = tokens.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet(),
                                        onConfirm = { picked -> onChange(tokens.filterNot { it.startsWith(prefix) }.toSet() + picked.map { prefix + it }) },
                                    )
                                )
                            },
                        )
                    )
                },
                label = { Text(stringResource(if (excluding) R.string.automation_exclude else R.string.automation_add_item)) },
                leadingIcon = { Icon(MaterialSymbols.Outlined.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
    }
}
