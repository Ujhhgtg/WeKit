package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Play_arrow
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentManager
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.ssh.SshCredentialStore
import dev.ujhhgtg.wekit.agent.ssh.SshCredentials
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun LinuxEnvironmentDetailScreen(environmentId: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<LinuxEnvironmentEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(LinuxEnvironmentType.PROOT) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("/root") }
    var error by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showHostKey by remember { mutableStateOf(false) }

    LaunchedEffect(environmentId) {
        existing = environmentId?.let { WeAgentRepository.getLinuxEnvironment(it) }
        existing?.let {
            name = it.name; type = it.type; host = it.sshHost.orEmpty(); port = (it.sshPort ?: 22).toString()
            username = it.sshUsername.orEmpty(); workingDirectory = it.workingDirectory
        }
    }

    AgentSettingsScaffold(
        title = stringResource(if (environmentId == null) R.string.agent_linux_environment_add else R.string.agent_linux_environment_detail),
        onBack = onBack,
    ) {
        item {
            SegmentedColumn(title = stringResource(R.string.agent_linux_environment_details_section)) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.agent_linux_environment_name), value = name,
                        onValueChange = { name = it }, dialogTitle = stringResource(R.string.agent_linux_environment_name),
                        confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel),
                    )
                }
                if (environmentId == null) {
                    LinuxEnvironmentType.entries.filter { it != LinuxEnvironmentType.NATIVE }.forEach { candidate ->
                        item {
                            BaseWidget(
                                title = candidate.name,
                                description = if (candidate == type) stringResource(R.string.agent_linux_environment_selected) else null,
                                onClick = { type = candidate },
                            )
                        }
                    }
                } else {
                    item { BaseWidget(title = type.name, description = stringResource(R.string.agent_linux_environment_type_immutable), enabled = false) }
                }
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.agent_linux_environment_working_directory), value = workingDirectory,
                        onValueChange = { value ->
                            workingDirectory = value
                            existing?.let { valueEntity -> scope.launch { WeAgentRepository.upsertLinuxEnvironment(valueEntity.copy(workingDirectory = value)) } }
                        },
                        dialogTitle = stringResource(R.string.agent_linux_environment_working_directory),
                        confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel),
                    )
                }
                if (type == LinuxEnvironmentType.SSH) {
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_host), value = host, onValueChange = { host = it }, dialogTitle = stringResource(R.string.agent_linux_environment_host), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_port), value = port, onValueChange = { port = it.filter(Char::isDigit) }, dialogTitle = stringResource(R.string.agent_linux_environment_port), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_username), value = username, onValueChange = { username = it }, dialogTitle = stringResource(R.string.agent_linux_environment_username), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_password), value = password, password = true, onValueChange = { password = it }, dialogTitle = stringResource(R.string.agent_linux_environment_password), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.agent_linux_environment_actions_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_linux_environment_save),
                        onClick = {
                            scope.launch {
                                runCatching { saveOrCreate(environmentId, existing, name, type, workingDirectory, host, port, username, password) }
                                    .onSuccess { onBack() }.onFailure { error = it.message }
                            }
                        },
                    )
                }
                if (existing != null) {
                    item { BaseWidget(icon = MaterialSymbols.Outlined.Play_arrow, title = stringResource(R.string.agent_linux_environment_test), onClick = { scope.launch { error = WeAgentService.linuxEnvironmentManager.checkHealth(existing!!.id).detail ?: "OK" } }) }
                    item { BaseWidget(icon = MaterialSymbols.Outlined.Delete, title = stringResource(R.string.action_delete), onClick = { showDelete = true }) }
                }
            }
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.agent_linux_environment_error)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) } }) }
    if (showDelete) AgentConfirmDialog(true, stringResource(R.string.action_delete), stringResource(R.string.agent_linux_environment_delete_confirm), stringResource(R.string.action_delete), stringResource(android.R.string.cancel), destructive = true, onConfirm = { scope.launch { WeAgentService.linuxEnvironmentManager.delete(existing!!.id); showDelete = false; onBack() } }, onDismiss = { showDelete = false })
}

private suspend fun saveOrCreate(id: String?, existing: LinuxEnvironmentEntity?, name: String, type: LinuxEnvironmentType, workingDirectory: String, host: String, port: String, username: String, password: String) {
    require(name.isNotBlank()) { "name is required" }
    if (existing != null) {
        WeAgentRepository.upsertLinuxEnvironment(existing.copy(name = name, workingDirectory = workingDirectory))
        return
    }
    if (type == LinuxEnvironmentType.PROOT) {
        WeAgentService.linuxEnvironmentManager.createProotEnvironment(name)
        return
    }
    require(type == LinuxEnvironmentType.SSH) { "unsupported environment type" }
    val credentials = SshCredentialStore.encrypt(SshCredentials.Password(password))
    WeAgentRepository.upsertLinuxEnvironment(LinuxEnvironmentEntity(UUID.randomUUID().toString(), name, type, workingDirectory, sshHost = host, sshPort = port.toInt(), sshUsername = username, sshAuthenticationType = "PASSWORD", sshCredentialCiphertext = credentials.ciphertext, sshCredentialIv = credentials.iv))
}
