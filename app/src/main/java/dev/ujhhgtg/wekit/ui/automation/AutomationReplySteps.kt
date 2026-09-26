package dev.ujhhgtg.wekit.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Delete
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.automation.AutomationReplyRule
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStep
import dev.ujhhgtg.wekit.features.automation.AutomationReplyStepKind
import dev.ujhhgtg.wekit.features.automation.AutomationTalkerCategory
import dev.ujhhgtg.wekit.features.automation.splitReplyValues
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumnScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

val REPLY_STEP_KIND_LABELS = mapOf(
    AutomationReplyStepKind.TEXT to R.string.automation_step_text,
    AutomationReplyStepKind.IMAGE to R.string.automation_step_image,
    AutomationReplyStepKind.VOICE to R.string.automation_step_voice,
    AutomationReplyStepKind.RANDOM_VOICE to R.string.automation_step_random_voice,
    AutomationReplyStepKind.EMOJI to R.string.automation_step_emoji,
    AutomationReplyStepKind.VIDEO to R.string.automation_step_video,
    AutomationReplyStepKind.FILE to R.string.automation_step_file,
    AutomationReplyStepKind.FAVORITE to R.string.automation_step_favorite,
    AutomationReplyStepKind.CARD to R.string.automation_step_card,
    AutomationReplyStepKind.INVITE_GROUP to R.string.automation_step_invite_group,
    AutomationReplyStepKind.XML to R.string.automation_step_xml,
)

private val REPLY_STEP_KIND_HINTS = mapOf(
    AutomationReplyStepKind.TEXT to R.string.automation_step_text_hint,
    AutomationReplyStepKind.RANDOM_VOICE to R.string.automation_step_random_voice_hint,
    AutomationReplyStepKind.FAVORITE to R.string.automation_step_favorite_hint,
    AutomationReplyStepKind.CARD to R.string.automation_step_card_hint,
    AutomationReplyStepKind.INVITE_GROUP to R.string.automation_step_invite_group_hint,
    AutomationReplyStepKind.XML to R.string.automation_step_xml_hint,
)

private fun mimeTypesFor(kind: AutomationReplyStepKind): Array<String> = when (kind) {
    AutomationReplyStepKind.IMAGE, AutomationReplyStepKind.EMOJI -> arrayOf("image/*")
    AutomationReplyStepKind.VOICE, AutomationReplyStepKind.RANDOM_VOICE -> arrayOf("audio/*", "application/octet-stream")
    AutomationReplyStepKind.VIDEO -> arrayOf("video/*")
    else -> arrayOf("*/*")
}

/** Step list of a reply rule: each step opens [ReplyStepDialog]; steps can be reordered. */
fun SegmentedColumnScope.replyStepItems(
    keyPrefix: String,
    rule: AutomationReplyRule,
    editable: Boolean,
    placeholders: List<String>,
    context: AutomationEditorContext,
    onChange: (AutomationReplyRule) -> Unit,
) {
    fun update(steps: List<AutomationReplyStep>) = onChange(rule.copy(steps = steps))

    item(key = "${keyPrefix}_quote") {
        dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget(
            iconPlaceholder = false,
            title = stringResource(R.string.automation_reply_as_quote),
            description = stringResource(R.string.automation_reply_as_quote_summary),
            checked = rule.asQuote,
            enabled = editable,
            onCheckedChange = { onChange(rule.copy(asQuote = it)) },
        )
    }
    rule.steps.forEachIndexed { index, step ->
        item(key = "${keyPrefix}_step_$index") {
            var editing by remember { mutableStateOf(false) }
            BaseWidget(
                iconPlaceholder = false,
                title = "${index + 1}. " + stringResource(REPLY_STEP_KIND_LABELS.getValue(step.kind)),
                description = stepSummary(step),
                enabled = editable,
                onClick = { editing = true },
                trailingContent = {
                    Row {
                        IconButton(enabled = editable && index > 0, onClick = {
                            update(rule.steps.toMutableList().apply { add(index - 1, removeAt(index)) })
                        }) { Icon(MaterialSymbols.Outlined.Arrow_upward, null) }
                        IconButton(enabled = editable && index < rule.steps.lastIndex, onClick = {
                            update(rule.steps.toMutableList().apply { add(index + 1, removeAt(index)) })
                        }) { Icon(MaterialSymbols.Outlined.Arrow_downward, null) }
                        IconButton(enabled = editable, onClick = {
                            update(rule.steps.toMutableList().apply { removeAt(index) })
                        }) { Icon(MaterialSymbols.Outlined.Delete, null) }
                    }
                },
            )
            if (editing) {
                ReplyStepDialog(step, placeholders, context, onDismiss = { editing = false }) { updated ->
                    update(rule.steps.toMutableList().apply { set(index, updated) })
                    editing = false
                }
            }
        }
    }
    item(key = "${keyPrefix}_add_step") {
        var adding by remember { mutableStateOf(false) }
        AutomationValueRow(
            title = stringResource(R.string.automation_add_step),
            value = "",
            enabled = editable,
            trailingIcon = MaterialSymbols.Outlined.Add,
            onClick = { adding = true },
        )
        if (adding) {
            ReplyStepDialog(AutomationReplyStep(), placeholders, context, onDismiss = { adding = false }) { created ->
                update(rule.steps + created)
                adding = false
            }
        }
    }
}

@Composable
private fun stepSummary(step: AutomationReplyStep): String {
    val content = when {
        step.kind.usesFiles || step.kind == AutomationReplyStepKind.CARD ||
                step.kind == AutomationReplyStepKind.INVITE_GROUP || step.kind == AutomationReplyStepKind.FAVORITE -> {
            val values = splitReplyValues(step.content)
            if (values.isEmpty()) stringResource(R.string.automation_none_selected)
            else values.joinToString(", ") { it.substringAfterLast('/') }
        }

        else -> step.content.ifBlank { stringResource(R.string.automation_step_empty) }
    }
    val delay = if (step.delayMs > 0 || step.randomDelay) {
        " · " + stringResource(
            if (step.randomDelay) R.string.automation_step_delay_random else R.string.automation_step_delay,
            step.delayMs.toString(),
        )
    } else ""
    return content.take(120) + delay
}

@Composable
private fun ReplyStepDialog(
    initial: AutomationReplyStep,
    placeholders: List<String>,
    context: AutomationEditorContext,
    onDismiss: () -> Unit,
    onConfirm: (AutomationReplyStep) -> Unit,
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var content by remember { mutableStateOf(initial.content) }
    var delay by remember { mutableStateOf(initial.delayMs.toString()) }
    var randomDelay by remember { mutableStateOf(initial.randomDelay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_edit_step)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DropDownMenuWidget(
                    title = stringResource(R.string.automation_step_kind),
                    description = null,
                    value = kind,
                    options = AutomationReplyStepKind.entries.map {
                        DropdownOption(it, stringResource(REPLY_STEP_KIND_LABELS.getValue(it)))
                    },
                    onValueChange = { kind = it },
                )
                val hint = REPLY_STEP_KIND_HINTS[kind]?.let { stringResource(it) }
                    ?: if (kind.usesFiles) stringResource(R.string.automation_step_files_hint) else null
                val placeholderHint = if (kind == AutomationReplyStepKind.TEXT || kind == AutomationReplyStepKind.XML) {
                    stringResource(R.string.automation_available_placeholders, placeholders.joinToString(" "))
                } else null
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.automation_step_content)) },
                    supportingText = listOfNotNull(hint, placeholderHint).takeIf { it.isNotEmpty() }
                        ?.let { lines -> { Text(lines.joinToString("\n")) } },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    kind.usesFiles -> OutlinedButton(onClick = {
                        context.pickFiles(mimeTypesFor(kind)) { paths ->
                            content = (splitReplyValues(content) + paths).joinToString("\n")
                        }
                    }) { Text(stringResource(R.string.automation_pick_files)) }

                    kind == AutomationReplyStepKind.CARD || kind == AutomationReplyStepKind.INVITE_GROUP -> {
                        val groups = kind == AutomationReplyStepKind.INVITE_GROUP
                        val pickerTitle = stringResource(REPLY_STEP_KIND_LABELS.getValue(kind))
                        OutlinedButton(onClick = {
                            val contacts = loadAutomationContacts(
                                setOf(if (groups) AutomationTalkerCategory.GROUP else AutomationTalkerCategory.PRIVATE)
                            )
                            showComposeDialog(context.activity) {
                                ContactsSelector(
                                    title = pickerTitle,
                                    contacts = contacts,
                                    initialSelectedWxIds = splitReplyValues(content).toSet(),
                                    onDismiss = onDismiss,
                                ) { selected ->
                                    content = selected.joinToString("\n")
                                    onDismiss()
                                }
                            }
                        }) { Text(stringResource(R.string.automation_pick_contacts)) }
                    }

                }
                OutlinedTextField(
                    value = delay,
                    onValueChange = { delay = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.automation_step_delay_ms)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.automation_step_random_delay), modifier = Modifier.weight(1f))
                    Switch(checked = randomDelay, onCheckedChange = { randomDelay = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = {
                    onConfirm(
                        AutomationReplyStep(
                            kind = kind,
                            content = content.trim(),
                            delayMs = delay.toLongOrNull() ?: 0L,
                            randomDelay = randomDelay,
                        )
                    )
                },
            ) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
