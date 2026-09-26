package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.automation.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.automation.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.ui.content.WeTimeOfDayField
import dev.ujhhgtg.wekit.ui.content.formatMinuteOfDay
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumnScope
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

/** A free-form value edited in [AutomationTextEditDialog]. */
class AutomationTextEditRequest(
    val title: String,
    val initial: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val filter: (String) -> String = { it },
    val supportingText: String? = null,
    val singleLine: Boolean = true,
    val validator: ((String) -> String?)? = null,
    val onCommit: (String) -> Unit,
)

@Composable
fun AutomationTextEditDialog(
    request: AutomationTextEditRequest,
    onClose: () -> Unit,
) {
    var draft by remember(request) { mutableStateOf(request.initial) }
    val validationError = request.validator?.invoke(draft)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = request.filter(it) },
                keyboardOptions = KeyboardOptions(keyboardType = request.keyboardType),
                supportingText = (validationError ?: request.supportingText)?.let { text -> { Text(text) } },
                isError = validationError != null,
                singleLine = request.singleLine,
                minLines = if (request.singleLine) 1 else 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = validationError == null,
                onClick = { request.onCommit(draft); onClose() },
            ) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Navigation entry inside an automation settings screen, e.g. "Global settings" with a chevron. */
@Composable
fun AutomationNavigationRow(
    title: String,
    description: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = description,
        enabled = enabled,
        onClick = onClick,
        trailingContent = {
            Icon(
                imageVector = MaterialSymbols.Outlined.Chevron_right,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Switch row for one automation rule. [overridden] is null in the global editor (plain toggle);
 * in a per-contact editor it drives the follow-parent semantics: a non-activated rule shows the
 * parent summary, activates on row tap, and exposes a reset action once activated.
 */
@Composable
fun AutomationRuleRow(
    title: String,
    summary: String,
    checked: Boolean,
    overridden: Boolean?,
    parentLabel: String = "",
    onActivate: () -> Unit = {},
    onReset: () -> Unit = {},
    onCheckedChange: (Boolean) -> Unit,
) {
    if (overridden == null) {
        SwitchWidget(
            iconPlaceholder = false,
            title = title,
            description = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        return
    }

    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = if (overridden) {
            summary
        } else {
            stringResource(R.string.automation_follow_parent, parentLabel, summary)
        },
        onClick = { if (overridden) onCheckedChange(!checked) else onActivate() },
        trailingDivider = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = overridden, onClick = onReset) {
                Text(stringResource(R.string.action_reset))
            }
            Switch(
                checked = checked,
                enabled = overridden,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedIconColor = MaterialTheme.colorScheme.primary,
                    uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                thumbContent = {
                    Icon(
                        imageVector = if (checked) MaterialSymbols.Outlined.Check else MaterialSymbols.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                },
            )
        }
    }
}

/** Switch-less variant of [AutomationRuleRow] for rules whose value is a choice, not a toggle. */
@Composable
fun AutomationModeRuleRow(
    title: String,
    summary: String,
    overridden: Boolean?,
    parentLabel: String,
    onActivate: () -> Unit,
    onReset: () -> Unit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = if (overridden == false) {
            stringResource(R.string.automation_follow_parent, parentLabel, summary)
        } else {
            summary
        },
        onClick = if (overridden == false) onActivate else null,
        trailingContent = {
            if (overridden != null) {
                TextButton(enabled = overridden, onClick = onReset) {
                    Text(stringResource(R.string.action_reset))
                }
            }
        },
    )
}

/** Row showing a stored value that is edited through an [AutomationTextEditRequest]. */
@Composable
fun AutomationValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    valueHint: String? = null,
    trailingIcon: ImageVector = MaterialSymbols.Outlined.Edit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = value.ifBlank { valueHint },
        enabled = enabled,
        onClick = onClick,
        trailingContent = { Icon(trailingIcon, null) },
    )
}

@Composable
fun AutomationErrorRow(message: String) {
    BaseWidget(iconPlaceholder = false, title = message, isError = true)
}

/** Time-range fields shown while the owning rule is enabled. */
fun SegmentedColumnScope.timeRangeItems(
    keyPrefix: String,
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    visible: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit,
) {
    item(key = "${keyPrefix}_fields", animatedVisibility = visible) {
        BaseItemContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WeTimeOfDayField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.automation_start),
                    minuteOfDay = rule.startMinute,
                    enabled = editable,
                    onMinuteChange = { onChange(rule.copy(startMinute = it)) }
                )
                WeTimeOfDayField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.automation_end),
                    minuteOfDay = rule.endMinute,
                    enabled = editable,
                    onMinuteChange = { onChange(rule.copy(endMinute = it)) }
                )
            }
        }
    }
}

/** Keyword rule controls: match mode, ignore-case, keyword list / regex. */
fun SegmentedColumnScope.keywordItems(
    keyPrefix: String,
    rule: AutomationKeywordRule,
    editable: Boolean,
    visible: Boolean,
    modes: List<AutomationKeywordMode>,
    onChange: (AutomationKeywordRule) -> Unit,
    onEditText: (AutomationTextEditRequest) -> Unit,
    inlineTextFields: Boolean = false,
) {
    item(key = "${keyPrefix}_mode", animatedVisibility = visible) {
        DropDownMenuWidget(
            iconPlaceholder = false,
            title = stringResource(R.string.automation_keyword_mode_label),
            description = null,
            value = rule.mode,
            options = modes.map { mode ->
                DropdownOption(
                    value = mode,
                    label = stringResource(
                        when (mode) {
                            AutomationKeywordMode.STRING_LIST -> R.string.automation_keyword_mode_contains
                            AutomationKeywordMode.EXACT -> R.string.automation_keyword_mode_exact
                            AutomationKeywordMode.REGEX -> R.string.automation_keyword_mode_regex
                        }
                    ),
                )
            },
            enabled = editable,
            onValueChange = { onChange(rule.copy(mode = it)) },
        )
    }
    item(key = "${keyPrefix}_ignore_case", animatedVisibility = visible) {
        SwitchWidget(
            iconPlaceholder = false,
            title = stringResource(R.string.automation_ignore_case),
            checked = rule.ignoreCase,
            enabled = editable,
            onCheckedChange = { onChange(rule.copy(ignoreCase = it)) },
        )
    }
    if (rule.mode == AutomationKeywordMode.REGEX) {
        item(key = "${keyPrefix}_regex", animatedVisibility = visible) {
            val regexTitle = stringResource(R.string.automation_keyword_mode_regex)
            val invalidRegexMessage = stringResource(R.string.automation_regex_invalid)
            val regexError = invalidRegexMessage.takeIf { runCatching { Regex(rule.regex) }.isFailure }
            if (inlineTextFields) {
                BaseSupportingWidget(
                    title = regexTitle,
                    description = stringResource(R.string.automation_regex_empty_summary),
                ) {
                    OutlinedTextField(
                        value = rule.regex,
                        enabled = editable,
                        onValueChange = { onChange(rule.copy(regex = it)) },
                        supportingText = regexError?.let { error -> { Text(error) } },
                        isError = regexError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            } else {
                AutomationValueRow(
                    title = regexTitle,
                    value = rule.regex,
                    enabled = editable,
                    valueHint = stringResource(R.string.automation_regex_empty_summary),
                    onClick = {
                        onEditText(
                            AutomationTextEditRequest(
                                title = regexTitle,
                                initial = rule.regex,
                                validator = { input ->
                                    invalidRegexMessage.takeIf { runCatching { Regex(input) }.isFailure }
                                },
                                onCommit = { onChange(rule.copy(regex = it)) },
                            )
                        )
                    },
                )
            }
        }
        return
    }
    rule.strings.forEach { keyword ->
        item(key = "${keyPrefix}_entry_$keyword", animatedVisibility = visible) {
            BaseWidget(
                iconPlaceholder = false,
                title = keyword,
                enabled = editable,
                trailingContent = {
                    IconButton(enabled = editable, onClick = { onChange(rule.copy(strings = rule.strings - keyword)) }) {
                        Icon(MaterialSymbols.Outlined.Delete, null)
                    }
                },
            )
        }
    }
    item(key = "${keyPrefix}_add", animatedVisibility = visible) {
        val addTitle = stringResource(R.string.automation_new_keyword)
        if (inlineTextFields) {
            var pendingKeyword by remember { mutableStateOf("") }
            BaseSupportingWidget(title = addTitle) {
                OutlinedTextField(
                    value = pendingKeyword,
                    enabled = editable,
                    onValueChange = { pendingKeyword = it },
                    trailingIcon = {
                        IconButton(
                            enabled = editable && pendingKeyword.trim().isNotEmpty(),
                            onClick = {
                                val keyword = pendingKeyword.trim()
                                if (keyword !in rule.strings) {
                                    onChange(rule.copy(strings = rule.strings + keyword))
                                }
                                pendingKeyword = ""
                            },
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, null)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        } else {
            AutomationValueRow(
                title = addTitle,
                value = "",
                enabled = editable,
                onClick = {
                    onEditText(
                        AutomationTextEditRequest(
                            title = addTitle,
                            initial = "",
                            onCommit = { input ->
                                val keyword = input.trim()
                                if (keyword.isNotEmpty() && keyword !in rule.strings) {
                                    onChange(rule.copy(strings = rule.strings + keyword))
                                }
                            },
                        )
                    )
                },
                trailingIcon = MaterialSymbols.Outlined.Add,
            )
        }
    }
}

/** Delay value rows shown while the delay rule is enabled. */
fun SegmentedColumnScope.delayItems(
    keyPrefix: String,
    baseMs: String,
    randomRangeMs: String,
    editable: Boolean,
    visible: Boolean,
    maxDigits: Int,
    onBaseChange: (String) -> Unit,
    onRandomRangeChange: (String) -> Unit,
    onEditText: (AutomationTextEditRequest) -> Unit,
) {
    val digitFilter: (String) -> String = { it.filter(Char::isDigit).take(maxDigits) }

    item(key = "${keyPrefix}_base", animatedVisibility = visible) {
        val baseTitle = stringResource(R.string.automation_base_delay_ms)
        AutomationValueRow(
            title = baseTitle,
            value = baseMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    AutomationTextEditRequest(
                        title = baseTitle,
                        initial = baseMs,
                        keyboardType = KeyboardType.Number,
                        filter = digitFilter,
                        onCommit = onBaseChange,
                    )
                )
            },
        )
    }
    item(key = "${keyPrefix}_random", animatedVisibility = visible) {
        val randomTitle = stringResource(R.string.automation_random_delay_ms)
        AutomationValueRow(
            title = randomTitle,
            value = randomRangeMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    AutomationTextEditRequest(
                        title = randomTitle,
                        initial = randomRangeMs,
                        keyboardType = KeyboardType.Number,
                        filter = digitFilter,
                        onCommit = onRandomRangeChange,
                    )
                )
            },
        )
    }
}

fun formatAutomationMinute(value: Int): String = formatMinuteOfDay(value)

@Composable
fun automationKeywordSummary(rule: AutomationKeywordRule, unrestrictedText: String): String {
    if (!rule.enabled) return unrestrictedText
    return when (rule.mode) {
        AutomationKeywordMode.STRING_LIST -> pluralStringResource(
            R.plurals.automation_keyword_contains_summary,
            rule.strings.size,
            rule.strings.size,
        )
        AutomationKeywordMode.EXACT -> pluralStringResource(
            R.plurals.automation_keyword_exact_summary,
            rule.strings.size,
            rule.strings.size,
        )
        AutomationKeywordMode.REGEX -> if (rule.regex.isBlank()) {
            stringResource(R.string.automation_regex_empty_summary)
        } else {
            stringResource(R.string.automation_regex_summary)
        }
    }
}
