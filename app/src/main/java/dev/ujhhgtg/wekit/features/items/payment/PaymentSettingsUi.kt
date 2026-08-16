package dev.ujhhgtg.wekit.features.items.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import dev.ujhhgtg.wekit.features.items.AutomationKeywordMode
import dev.ujhhgtg.wekit.features.items.AutomationKeywordRule
import dev.ujhhgtg.wekit.features.items.AutomationTimeRangeRule
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.WeTimeOfDayField
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumnScope
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget

/**
 * Free-form value being edited through an in-dialog edit view. The payment dialogs live in a
 * single [dev.ujhhgtg.wekit.ui.utils.showComposeDialog] window, so text values swap the dialog
 * content instead of opening a second window.
 */
internal class PaymentTextEditMode(
    val title: String,
    val initial: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val filter: (String) -> String = { it },
    val supportingText: String? = null,
    val onCommit: (String) -> Unit,
)

@Composable
internal fun PaymentTextEditDialog(
    mode: PaymentTextEditMode,
    onClose: () -> Unit,
) {
    var draft by remember(mode) { mutableStateOf(mode.initial) }

    AlertDialogContent(
        title = { Text(mode.title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = mode.filter(it) },
                keyboardOptions = KeyboardOptions(keyboardType = mode.keyboardType),
                supportingText = mode.supportingText?.let { text -> { Text(text) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { mode.onCommit(draft); onClose() }) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Navigation entry inside a payment settings dialog, e.g. "Global settings" with a chevron. */
@Composable
internal fun PaymentNavigationRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = description,
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
internal fun PaymentRuleRow(
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

/** Switch-less variant of [PaymentRuleRow] for rules whose value is a choice, not a toggle. */
@Composable
internal fun PaymentModeRuleRow(
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

/** Row showing a stored value that is edited through an in-dialog [PaymentTextEditMode]. */
@Composable
internal fun PaymentValueRow(
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
internal fun PaymentErrorRow(message: String) {
    BaseWidget(iconPlaceholder = false, title = message, isError = true)
}

/** Time-range fields shown while the owning rule is enabled. */
internal fun SegmentedColumnScope.timeRangeItems(
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    visible: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit,
) {
    item(key = "time_range_fields", animatedVisibility = visible) {
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
internal fun SegmentedColumnScope.keywordItems(
    keyPrefix: String,
    rule: AutomationKeywordRule,
    editable: Boolean,
    visible: Boolean,
    modes: List<AutomationKeywordMode>,
    onChange: (AutomationKeywordRule) -> Unit,
    onEditText: (PaymentTextEditMode) -> Unit,
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
            PaymentValueRow(
                title = regexTitle,
                value = rule.regex,
                enabled = editable,
                valueHint = stringResource(R.string.automation_regex_empty_summary),
                onClick = {
                    onEditText(
                        PaymentTextEditMode(
                            title = regexTitle,
                            initial = rule.regex,
                            onCommit = { onChange(rule.copy(regex = it)) },
                        )
                    )
                },
            )
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
        PaymentValueRow(
            title = addTitle,
            value = "",
            enabled = editable,
            onClick = {
                onEditText(
                    PaymentTextEditMode(
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

/** Delay value rows shown while the delay rule is enabled. */
internal fun SegmentedColumnScope.delayItems(
    baseMs: String,
    randomRangeMs: String,
    editable: Boolean,
    visible: Boolean,
    maxDigits: Int,
    onBaseChange: (String) -> Unit,
    onRandomRangeChange: (String) -> Unit,
    onEditText: (PaymentTextEditMode) -> Unit,
) {
    val digitFilter: (String) -> String = { it.filter(Char::isDigit).take(maxDigits) }

    item(key = "delay_base", animatedVisibility = visible) {
        val baseTitle = stringResource(R.string.automation_base_delay_ms)
        PaymentValueRow(
            title = baseTitle,
            value = baseMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    PaymentTextEditMode(
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
    item(key = "delay_random", animatedVisibility = visible) {
        val randomTitle = stringResource(R.string.automation_random_delay_ms)
        PaymentValueRow(
            title = randomTitle,
            value = randomRangeMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    PaymentTextEditMode(
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
