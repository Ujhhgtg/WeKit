package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Undo
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationScopeType
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.contacts.ContactAvatar
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.ConnectedButtonGroup
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.gesture.WindowNavigationEventBridge

private const val SECONDS_PER_DAY = 24 * 3600

// ---- sheet & dialogs ------------------------------------------------------------------------

/**
 * Modal bottom sheet for editing one value: [content] scrolls between a title and a fixed footer
 * with Cancel / Done ([onConfirm] null shows only Cancel) and optional [footerStart] actions. The
 * footer callback receives a completion helper so actions can run after the hide animation.
 */
@Composable
fun AutomationSheet(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)?,
    subtitle: String? = null,
    icon: ImageVector? = null,
    confirmEnabled: Boolean = true,
    footerStart: @Composable RowScope.((() -> Unit) -> Unit) -> Unit = { _ -> },
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()

    fun finishSheet(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        WindowNavigationEventBridge()
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (icon != null) {
                    Box(
                        modifier =
                            Modifier.size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier =
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                footerStart(::finishSheet)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { finishSheet(onDismiss) }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                if (onConfirm != null) {
                    val confirm = onConfirm
                    Button(onClick = { finishSheet(confirm) }, enabled = confirmEnabled) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }
}

/** Label above a group of controls inside a sheet. */
@Composable
fun AutomationFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun AutomationSheetNote(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/** Exact number input; values outside [range] are rejected inline. */
@Composable
fun AutomationNumberDialog(
    title: String,
    value: Long,
    range: LongRange,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val parsed = text.toLongOrNull()?.takeIf { it in range }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.filter(Char::isDigit).take(range.last.toString().length)
                },
                singleLine = true,
                suffix = { Text(unit) },
                isError = parsed == null,
                supportingText = {
                    Text(stringResource(R.string.automation_number_range, range.first, range.last))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { onConfirm(parsed!!) }) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

// ---- controls -------------------------------------------------------------------------------

/** Connected button group with an optional explanation of the selected option below it. */
@Composable
fun <T> AutomationChoiceGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectedButtonGroup(options = options, selected = selected, onSelect = onSelect)
        if (description != null) AutomationSheetNote(description)
    }
}

/**
 * The switch used on rule rows, with check / close thumb icons like
 * [dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget].
 */
@Composable
fun AutomationSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = {
            haptic.performHapticFeedback(
                if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
            )
            onCheckedChange(it)
        },
        colors =
            SwitchDefaults.colors(
                checkedIconColor = MaterialTheme.colorScheme.primary,
                uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        thumbContent = {
            Icon(
                imageVector =
                    if (checked) MaterialSymbols.Outlined.Check else MaterialSymbols.Outlined.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

/** A labelled switch on a surface card, for use inside rows and sheets. */
@Composable
fun AutomationInlineSwitch(
    title: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AutomationSwitch(checked, onCheckedChange)
        }
    }
}

/** Slider row with a tappable value that opens [AutomationNumberDialog]. */
@Composable
fun AutomationNumberSlider(
    title: String,
    value: Long,
    range: IntRange,
    step: Int,
    unit: String,
    maxExact: Long,
    onChange: (Long) -> Unit,
    description: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    BaseItemContainer {
        IntNumberPickerWidget(
            title = title,
            description = description,
            value = value.toInt(),
            startInt = range.first,
            endInt = range.last,
            stepSize = step,
            valueSuffix = " $unit",
            onValueClick = { editing = true },
            onValueChange = { onChange(it.toLong()) },
        )
    }
    if (editing) {
        AutomationNumberDialog(
            title = title,
            value = value,
            range = range.first.toLong()..maxExact,
            unit = unit,
            onDismiss = { editing = false },
            onConfirm = {
                onChange(it)
                editing = false
            },
        )
    }
}

/** Filter chips toggling members of [selected], with select-all / clear actions. */
@Composable
fun <V> AutomationChipSet(
    options: List<Pair<V, String>>,
    selected: Set<V>,
    onChange: (Set<V>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationFieldLabel("${selected.size} / ${options.size}") {
            TextButton(onClick = { onChange(options.map { it.first }.toSet()) }) {
                Text(stringResource(R.string.automation_select_all))
            }
            TextButton(onClick = { onChange(emptySet()) }) {
                Text(stringResource(R.string.automation_select_none))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (option, label) ->
                val on = option in selected
                FilterChip(
                    selected = on,
                    onClick = { onChange(if (on) selected - option else selected + option) },
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
}

// ---- small displays -------------------------------------------------------------------------

/** Compact chips under a row's summary; shows the first [max] items and a "+n" chip. */
@Composable
fun AutomationMiniChips(items: List<String>, max: Int = 4) {
    if (items.isEmpty()) return
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (items.take(max) + listOfNotNull((items.size - max).takeIf { it > 0 }?.let { "+$it" }))
            .forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
    }
}

/** Small pill label (InstallerX's CapsuleTag). */
@Composable
fun AutomationTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: AutomationTagTone = AutomationTagTone.Primary,
) {
    val (container, content) =
        when (tone) {
            AutomationTagTone.Primary -> {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to
                    MaterialTheme.colorScheme.primary
            }

            AutomationTagTone.Muted -> {
                MaterialTheme.colorScheme.surfaceContainerHighest to
                    MaterialTheme.colorScheme.onSurfaceVariant
            }

            AutomationTagTone.Tertiary -> {
                MaterialTheme.colorScheme.tertiaryContainer to
                    MaterialTheme.colorScheme.onTertiaryContainer
            }

            AutomationTagTone.Warning -> {
                MaterialTheme.colorScheme.errorContainer to
                    MaterialTheme.colorScheme.onErrorContainer
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

enum class AutomationTagTone {
    Primary,
    Muted,
    Tertiary,
    Warning,
}

/** A 24-hour bar highlighting the window [startSecond]..[endSecond], wrapping past midnight. */
@Composable
fun AutomationDayLine(
    startSecond: Int,
    endSecond: Int,
    height: Int = 8,
    modifier: Modifier = Modifier,
) {
    val day = SECONDS_PER_DAY.toFloat()
    val segments =
        when {
            startSecond == endSecond -> listOf(0f to 1f)
            startSecond < endSecond -> listOf(startSecond / day to endSecond / day)
            else -> listOf(0f to endSecond / day, startSecond / day to 1f)
        }
    AutomationFractionBar(segments, height, modifier)
}

/** A track with filled [segments], each a fraction range of the width. */
@Composable
fun AutomationFractionBar(
    segments: List<Pair<Float, Float>>,
    height: Int = 8,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(height.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        segments.forEach { (from, to) ->
            Box(
                Modifier.offset(x = maxWidth * from)
                    .width(maxWidth * (to - from))
                    .height(height.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Leading button that removes a scope's own value so it inherits again. */
@Composable
fun AutomationRevertButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            MaterialSymbols.Outlined.Undo,
            contentDescription = stringResource(R.string.automation_revert),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Trailing marker on inherited rows: tapping the row overrides the rule here. */
@Composable
fun AutomationOverrideMarker() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceBright,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Icon(
            MaterialSymbols.Outlined.Add,
            contentDescription = stringResource(R.string.automation_override),
            modifier = Modifier.padding(6.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- contacts -------------------------------------------------------------------------------

/** Display name of a contact, group or official account (never blank). */
@Composable
fun rememberContactName(wxId: String): String =
    remember(wxId) { WeDatabaseApi.getDisplayName(wxId).ifBlank { wxId } }

@Composable
fun rememberMemberName(groupId: String, memberId: String): String =
    remember(groupId, memberId) {
        WeDatabaseApi.getGroupMemberDisplayName(groupId, memberId).ifBlank { memberId }
    }

@Composable
fun AutomationAvatar(wxId: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val url = remember(wxId) { WeDatabaseApi.getAvatarUrl(wxId) }
    ContactAvatar(url.ifBlank { null }, size = size)
}

/** Avatar of a CONTACT or GROUP_MEMBER scope; null for other scopes. */
@Composable
fun AutomationScopeAvatar(scope: AutomationScope, size: androidx.compose.ui.unit.Dp = 40.dp) {
    when (scope.type) {
        AutomationScopeType.CONTACT -> AutomationAvatar(scope.scopeId, size)
        AutomationScopeType.GROUP_MEMBER -> AutomationAvatar(scope.memberId, size)
        else -> Unit
    }
}
