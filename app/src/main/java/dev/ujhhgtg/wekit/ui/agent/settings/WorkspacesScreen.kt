package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.WeKitBasicDialog
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.WorkspaceEntity
import dev.ujhhgtg.wekit.agent.workspace.WorkspaceStore
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.launch
import java.util.UUID

/** Workspace directory management (§7). Each workspace's name doubles as its on-disk folder. No
 *  global switch — whether a workspace is used is per-session state. Tapping a row edits (renames). */
@Composable
fun WorkspacesScreen(onBack: () -> Unit) {
    val workspaces by WeAgentRepository.observeWorkspaces().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showAdd = remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceEntity?>(null) }

    AgentSettingsScaffold(title = stringResource(R.string.agent_workspaces_title), onBack = onBack) {
        if (workspaces.isEmpty()) item { EmptyHint(stringResource(R.string.agent_workspaces_empty)) }
        items(workspaces.size, key = { workspaces[it].id }) { i ->
            val w = workspaces[i]
            SegmentedColumn {
                item {
                    BaseWidget(
                        title = w.name,
                        description = stringResource(R.string.agent_workspace_path_summary, w.name),
                        onClick = { editing = w },
                        trailingContent = { Icon(MaterialSymbols.Outlined.Chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
        item {
            Button(
                onClick = { showAdd.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text(stringResource(R.string.agent_add_workspace)) }
        }
    }

    AddWorkspaceDialog(showAdd) { name ->
        when (val v = WorkspaceStore.validateWorkspaceName(name)) {
            is WorkspaceStore.NameValidation.Invalid -> showToast(context.getString(v.reason.stringRes()))
            WorkspaceStore.NameValidation.Ok -> scope.launch {
                val n = name.trim()
                WorkspaceStore.workspaceDir(n) // create the real folder
                WeAgentRepository.upsertWorkspace(WorkspaceEntity(UUID.randomUUID().toString(), n))
            }
        }
    }

    EditWorkspaceDialog(
        show = editing != null,
        initialName = editing?.name.orEmpty(),
        onDismiss = { editing = null },
        onDelete = { editing?.let { w -> scope.launch { WeAgentRepository.deleteWorkspace(w.id) } }; editing = null },
        onRename = { newName ->
            editing?.let { w ->
                when (val v = WorkspaceStore.validateWorkspaceName(newName)) {
                    is WorkspaceStore.NameValidation.Invalid -> showToast(context.getString(v.reason.stringRes()))
                    WorkspaceStore.NameValidation.Ok -> scope.launch {
                        if (WorkspaceStore.renameWorkspaceDir(w.name, newName.trim())) {
                            WeAgentRepository.upsertWorkspace(w.copy(name = newName.trim()))
                        } else {
                            val localized = LocalizedContextFactory.create(
                                context,
                                WeKitLocaleController.resolvedLocale,
                                LocaleResourceMode.InjectedHost,
                            )
                            showToast(localized.getString(R.string.agent_workspace_rename_failed))
                        }
                    }.also { editing = null }
                }
            }
        },
    )
}

private fun WorkspaceStore.InvalidNameReason.stringRes(): Int = when (this) {
    WorkspaceStore.InvalidNameReason.EMPTY -> R.string.agent_name_required
    WorkspaceStore.InvalidNameReason.DOT_PATH -> R.string.agent_name_dot_invalid
    WorkspaceStore.InvalidNameReason.ILLEGAL_CHARACTER -> R.string.agent_name_illegal_characters
}

@Composable
private fun EditWorkspaceDialog(
    show: Boolean,
    initialName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    // Keyed on [initialName]: the dialog is composed unconditionally, so on first composition nothing
    // is being edited and [initialName] is blank. Unkeyed state would leave the field empty when the
    // user later taps a workspace to rename it.
    var name by remember(initialName, show) { mutableStateOf(initialName) }
    WeKitBasicDialog(show = show, title = stringResource(R.string.agent_edit_workspace), onDismissRequest = onDismiss) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.agent_workspace_edit_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_delete)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onRename(name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun AddWorkspaceDialog(
    show: androidx.compose.runtime.MutableState<Boolean>,
    onConfirm: (String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    WeKitBasicDialog(show = show.value, title = stringResource(R.string.agent_add_workspace), onDismissRequest = { show.value = false }) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.agent_workspace_add_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { show.value = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dialog_cancel)) }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { onConfirm(name); show.value = false },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_add)) }
            }
        }
    }
}
