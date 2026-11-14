package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationRuleKey
import dev.ujhhgtg.wekit.features.automation.AutomationRuleValues
import dev.ujhhgtg.wekit.features.automation.AutomationRules
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationStoredScope
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumnScope

/**
 * How a rule row is shown: [PLAIN] in GLOBAL / ITEM editors, [OWN] for a rule the scope overrides
 * (with a revert button), [INHERITED] for a dimmed, read-only inherited value (tap to override).
 */
enum class AutomationRuleMode {
    PLAIN,
    OWN,
    INHERITED,
}

/** Callbacks from rule rows to the hosting screen. */
class AutomationRuleActions(
    val onChange: (AutomationRuleKey<*>, Any) -> Unit,
    /** Opens the rule's sheet (with [value] as its draft) or its reply page. */
    val onOpen: (editor: AutomationRuleEditor<*>, value: Any) -> Unit,
    val onOverride: (AutomationRuleEditor<*>) -> Unit = {},
    val onRevert: (AutomationRuleKey<*>) -> Unit = {},
)

/** One rule as a segmented row, plus its inline controls while it is on. */
fun <T : Any> SegmentedColumnScope.automationRuleItem(
    spec: AutomationSpec,
    editor: AutomationRuleEditor<T>,
    value: T,
    mode: AutomationRuleMode,
    scope: AutomationScope,
    rules: AutomationRules,
    context: AutomationEditorContext,
    actions: AutomationRuleActions,
    error: String? = null,
    /** Where an inherited value comes from, when that isn't the direct parent. */
    source: (@Composable () -> String)? = null,
    highlighted: Boolean = false,
) {
    val on = editor.isOn?.invoke(value)
    val header: @Composable () -> Unit = {
        AutomationRuleHeader(
            spec,
            editor,
            value,
            mode,
            scope,
            rules,
            actions,
            error,
            source,
            highlighted,
        )
    }
    val inline = editor.inline
    if (inline == null) {
        item(key = editor.key.id) { header() }
    } else {
        expandableItem(
            key = editor.key.id,
            expanded = mode != AutomationRuleMode.INHERITED && on != false,
            topContent = { header() },
            bottomContent = {
                BaseItemContainer {
                    inline(context, value) { actions.onChange(editor.key, it) }
                }
            },
        )
    }
}

@Composable
private fun <T : Any> AutomationRuleHeader(
    spec: AutomationSpec,
    editor: AutomationRuleEditor<T>,
    value: T,
    mode: AutomationRuleMode,
    scope: AutomationScope,
    rules: AutomationRules,
    actions: AutomationRuleActions,
    error: String?,
    source: (@Composable () -> String)?,
    highlighted: Boolean,
) {
    val on = editor.isOn?.invoke(value)
    val inherited = mode == AutomationRuleMode.INHERITED
    val setOn = editor.setOn
    val toggle: (Boolean) -> Unit = { checked ->
        val next = setOn!!(value, checked)
        // Turning on a rule that still needs content (e.g. keywords) goes straight to its sheet.
        if (
            checked &&
                editor.sheet != null &&
                spec.ruleError(rules.with(editor.key, next), editor.key) != null
        ) {
            actions.onOpen(editor, next)
        } else {
            actions.onChange(editor.key, next)
        }
    }
    BaseWidget(
        title = stringResource(editor.titleFor(scope)),
        description = editor.summary(value),
        descriptionContent =
            if (editor.summaryExtra != null || source != null) {
                {
                    editor.summaryExtra?.invoke(value)
                    if (source != null) {
                        AutomationTag(
                            stringResource(R.string.automation_value_from, source()),
                            Modifier.padding(top = 4.dp),
                            AutomationTagTone.Muted,
                        )
                    }
                }
            } else {
                null
            },
        errorLines = listOfNotNull(error),
        contentAlpha = if (inherited) 0.62f else 1f,
        selected = highlighted,
        leadingContent =
            if (mode == AutomationRuleMode.OWN) {
                { AutomationRevertButton { actions.onRevert(editor.key) } }
            } else {
                null
            },
        onClick =
            when {
                inherited -> ({ actions.onOverride(editor) })
                editor.opensEditor -> ({ actions.onOpen(editor, value) })
                on != null -> ({ toggle(!on) })
                else -> null
            },
        trailingDivider = !inherited && on != null && editor.opensEditor,
        trailingContent = {
            when {
                inherited -> {
                    AutomationOverrideMarker()
                }

                on != null -> {
                    AutomationSwitch(on, toggle)
                }

                editor.opensEditor -> {
                    Icon(
                        MaterialSymbols.Outlined.Chevron_right,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** Padding of inline controls under a rule's row; sliders bring their own. */
val AutomationInlinePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)

/** Rules with [value] set for [key]. */
fun <T : Any> AutomationRules.with(key: AutomationRuleKey<T>, value: T): AutomationRules =
    overlay(AutomationRuleValues().with(key, value))

/** The bottom sheet editing [editor]'s value; Done stays disabled while the rule is invalid. */
@Composable
fun <T : Any> AutomationRuleSheet(
    spec: AutomationSpec,
    editor: AutomationRuleEditor<T>,
    initial: T,
    scope: AutomationScope,
    rules: AutomationRules,
    context: AutomationEditorContext,
    onDismiss: () -> Unit,
    onDone: (T) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val error = spec.ruleError(rules.with(editor.key, value), editor.key)
    AutomationSheet(
        title = stringResource(editor.titleFor(scope)),
        subtitle = editor.sheetHintRes?.let { stringResource(it) },
        onDismiss = onDismiss,
        onConfirm = { onDone(value) },
        confirmEnabled = error == null,
    ) {
        editor.sheet!!(this, context, value) { value = it }
        if (error != null) AutomationSheetNote(error, isError = true)
    }
}

/**
 * Unsaved edits of one editor screen, shared with the reply page opened from it. For a batch edit,
 * [batch] lists the edited scopes and [presets] stays null until presets are chosen.
 */
class AutomationScopeDraft(
    val initial: AutomationStoredScope,
    val batch: List<AutomationScope>? = null,
) {
    var values by mutableStateOf(initial.values)
    var presets by mutableStateOf(if (batch == null) initial.presets else null)
    var name by mutableStateOf(initial.name)

    /**
     * Values of rules opened on another page without being changed there; not part of [modified].
     */
    val openedValues = HashMap<String, Any>()

    val scope: AutomationScope
        get() = initial.scope

    val modified: Boolean
        get() =
            values != initial.values ||
                name != initial.name ||
                presets != (if (batch == null) initial.presets else null)

    fun <T : Any> set(key: AutomationRuleKey<T>, value: T) {
        values = values.with(key, value)
    }

    fun stored(): AutomationStoredScope =
        initial.copy(values = values, presets = presets ?: initial.presets, name = name)
}

@Suppress("UNCHECKED_CAST")
fun AutomationScopeDraft.setAny(key: AutomationRuleKey<*>, value: Any) =
    set(key as AutomationRuleKey<Any>, value)

@Suppress("UNCHECKED_CAST")
fun <T : Any> AutomationRules.getAny(editor: AutomationRuleEditor<T>): T = this[editor.key]
