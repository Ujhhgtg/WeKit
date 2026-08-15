package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitWindowDialog
import dev.ujhhgtg.wekit.agent.skill.SkillStore
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skills management (§ Skills): add/edit/delete skills and toggle each on/off globally. Skills are
 * `SKILL.md` files under `moduleData/agent/skills/<name>/`; only enabled ones are advertised to the
 * model (as a name+description catalog), and the model loads a skill's body via the `load_skill`
 * tool — the dynamic-discovery model.
 */
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // SkillStore is filesystem-backed (no Flow); reload via a tick after each mutation.
    var reloadTick by remember { mutableStateOf(0) }
    var skills by remember { mutableStateOf<List<SkillStore.Skill>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(reloadTick) {
        skills = withContext(Dispatchers.IO) { SkillStore.list() }
    }

    // null = closed; Skill(...) = editing existing; empty-name Skill = adding new.
    var editing by remember { mutableStateOf<SkillStore.Skill?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    AgentSettingsScaffold(title = stringResource(R.string.agent_skills_title), onBack = onBack) {
        if (skills.isEmpty()) item { EmptyHint(stringResource(R.string.agent_skills_empty)) }
        items(skills.size, key = { skills[it].name }) { i ->
            val s = skills[i]
            SegmentedColumn {
                item {
                    SwitchWidget(
                        title = s.name,
                        description = s.description.ifBlank { stringResource(R.string.agent_no_description) },
                        checked = s.enabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                withContext(Dispatchers.IO) { SkillStore.setEnabled(s.name, on) }
                                reloadTick++
                            }
                        },
                    )
                }
                item {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        TextButton(onClick = { editing = s; showEditor = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_edit)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { scope.launch { withContext(Dispatchers.IO) { SkillStore.delete(s.name) }; reloadTick++ } },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { editing = null; showEditor = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text(stringResource(R.string.agent_add_skill)) }
        }
    }

    SkillEditorDialog(
        show = showEditor,
        existing = editing,
        // Clear [editing] too: the editor's field state is keyed on it, so leaving it set would keep
        // the abandoned edits alive and re-show them the next time the same skill is opened.
        onDismiss = { showEditor = false; editing = null },
        onSave = { name, description, body ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { SkillStore.save(name, description, body) }
                if (ok == null) {
                    val localized = LocalizedContextFactory.create(
                        context,
                        WeKitLocaleController.resolvedLocale,
                        LocaleResourceMode.InjectedHost,
                    )
                    showToast(localized.getString(R.string.agent_invalid_skill_name))
                }
                else {
                    // Renaming isn't in-place: if the dir name changed, drop the old one.
                    editing?.name?.takeIf { it != ok }?.let { old ->
                        withContext(Dispatchers.IO) { SkillStore.delete(old) }
                    }
                    reloadTick++
                    showEditor = false
                }
            }
        },
    )
}

@Composable
private fun SkillEditorDialog(
    show: Boolean,
    existing: SkillStore.Skill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, body: String) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember(existing) { mutableStateOf(existing?.description.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }

    WeKitWindowDialog(
        show = show,
        title = stringResource(if (existing == null) R.string.agent_add_skill else R.string.agent_edit_skill),
        onDismissRequest = onDismiss,
    ) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.agent_skill_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.agent_skill_description_label)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.agent_skill_body_label)) },
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { onSave(name, description, body) },
                    enabled = name.isNotBlank() && body.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
