package dev.ujhhgtg.wekit.ui.agent.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyException
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
    var pendingHostKey by remember { mutableStateOf<SshHostKeyException?>(null) }
    var operation by remember { mutableStateOf<EnvironmentOperation?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val busy = operation != null
    val healthyStatus = stringResource(R.string.agent_linux_environment_healthy)
    val operationMessage = when (operation) {
        EnvironmentOperation.SAVE -> stringResource(R.string.agent_linux_environment_saving)
        EnvironmentOperation.TEST -> stringResource(R.string.agent_linux_environment_testing)
        EnvironmentOperation.DELETE -> stringResource(R.string.agent_linux_environment_deleting)
        EnvironmentOperation.TRUST -> stringResource(R.string.agent_linux_environment_trusting)
        null -> null
    }

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
                        enabled = !busy,
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
                                enabled = !busy,
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
                        enabled = !busy,
                        onValueChange = { value ->
                            workingDirectory = value
                            existing?.let { valueEntity -> scope.launch { WeAgentService.linuxEnvironmentManager.upsert(valueEntity.copy(workingDirectory = value)) } }
                        },
                        dialogTitle = stringResource(R.string.agent_linux_environment_working_directory),
                        confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel),
                    )
                }
                if (type == LinuxEnvironmentType.SSH) {
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_host), value = host, enabled = !busy, onValueChange = { host = it }, dialogTitle = stringResource(R.string.agent_linux_environment_host), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_port), value = port, enabled = !busy, onValueChange = { port = it.filter(Char::isDigit) }, dialogTitle = stringResource(R.string.agent_linux_environment_port), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_username), value = username, enabled = !busy, onValueChange = { username = it }, dialogTitle = stringResource(R.string.agent_linux_environment_username), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_password), value = password, enabled = !busy, password = true, onValueChange = { password = it }, dialogTitle = stringResource(R.string.agent_linux_environment_password), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.agent_linux_environment_actions_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.agent_linux_environment_save),
                        enabled = !busy,
                        onClick = {
                            if (busy) return@BaseWidget
                            operation = EnvironmentOperation.SAVE
                            status = null
                            scope.launch {
                                runCatching { saveOrCreate(environmentId, existing, name, type, workingDirectory, host, port, username, password) }
                                    .onSuccess { created ->
                                        if (created) onBack()
                                    }.onFailure {
                                        if (it is SshHostKeyException) {
                                            pendingHostKey = it
                                        } else error = it.message
                                    }
                                operation = null
                            }
                        },
                        trailingContent = {
                            if (operation == EnvironmentOperation.SAVE) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        },
                    )
                }
                if (existing != null) {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Play_arrow,
                            title = stringResource(R.string.agent_linux_environment_test),
                            enabled = !busy,
                            onClick = {
                                if (busy) return@BaseWidget
                                operation = EnvironmentOperation.TEST
                                status = null
                                scope.launch {
                                    runCatching { WeAgentService.linuxEnvironmentManager.checkHealth(existing!!.id) }
                                        .onSuccess { status = it.detail ?: healthyStatus }
                                        .onFailure {
                                            if (it is SshHostKeyException) pendingHostKey = it else error = it.message
                                        }
                                    operation = null
                                }
                            },
                            trailingContent = {
                                if (operation == EnvironmentOperation.TEST) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            },
                        )
                    }
                    item { BaseWidget(icon = MaterialSymbols.Outlined.Delete, title = stringResource(R.string.action_delete), enabled = !busy, onClick = { showDelete = true }) }
                }
                (operationMessage ?: status)?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.agent_linux_environment_error)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) } }) }
    if (showDelete) AgentConfirmDialog(
        true,
        stringResource(R.string.action_delete),
        stringResource(R.string.agent_linux_environment_delete_confirm),
        stringResource(R.string.action_delete),
        stringResource(android.R.string.cancel),
        destructive = true,
        loading = operation == EnvironmentOperation.DELETE,
        onConfirm = {
            if (busy) return@AgentConfirmDialog
            operation = EnvironmentOperation.DELETE
            status = null
            scope.launch {
                runCatching { WeAgentService.linuxEnvironmentManager.delete(existing!!.id) }
                    .onSuccess { showDelete = false; onBack() }
                    .onFailure { error = it.message }
                operation = null
            }
        },
        onDismiss = { if (!busy) showDelete = false },
    )
    pendingHostKey?.let { hostKeyError ->
        AgentConfirmDialog(
            true,
            stringResource(R.string.agent_linux_environment_host_key_title),
            stringResource(
                R.string.agent_linux_environment_host_key_message,
                hostKeyError.observed.algorithm,
                hostKeyError.observed.fingerprint,
            ),
            stringResource(R.string.agent_linux_environment_host_key_confirm),
            stringResource(android.R.string.cancel),
            loading = operation == EnvironmentOperation.TRUST,
            onConfirm = {
                if (busy) return@AgentConfirmDialog
                operation = EnvironmentOperation.TRUST
                status = null
                scope.launch {
                    runCatching {
                        WeAgentService.linuxEnvironmentManager.confirmSshHostKey(
                            requireNotNull(existing).id,
                            hostKeyError.endpoint,
                            hostKeyError.observed,
                        )
                        WeAgentService.linuxEnvironmentManager.checkHealth(requireNotNull(existing).id)
                    }.onSuccess { result ->
                        pendingHostKey = null
                        status = result.detail ?: healthyStatus
                    }.onFailure { error = it.message }
                    operation = null
                }
            },
            onDismiss = { if (!busy) pendingHostKey = null },
        )
    }
}

private enum class EnvironmentOperation { SAVE, TEST, DELETE, TRUST }

private suspend fun saveOrCreate(id: String?, existing: LinuxEnvironmentEntity?, name: String, type: LinuxEnvironmentType, workingDirectory: String, host: String, port: String, username: String, password: String): Boolean {
    require(name.isNotBlank()) { "name is required" }
    if (existing != null) {
        val credentials = if (password.isNotEmpty()) SshCredentialStore.encrypt(SshCredentials.Password(password)) else null
        WeAgentService.linuxEnvironmentManager.upsert(existing.copy(
            name = name, workingDirectory = workingDirectory,
            sshHost = if (type == LinuxEnvironmentType.SSH) host else existing.sshHost,
            sshPort = if (type == LinuxEnvironmentType.SSH) port.toInt() else existing.sshPort,
            sshUsername = if (type == LinuxEnvironmentType.SSH) username else existing.sshUsername,
            sshCredentialCiphertext = credentials?.ciphertext ?: existing.sshCredentialCiphertext,
            sshCredentialIv = credentials?.iv ?: existing.sshCredentialIv,
            sshHostKeyAlgorithm = if (credentials != null && (host != existing.sshHost || username != existing.sshUsername || port.toInt() != existing.sshPort)) null else existing.sshHostKeyAlgorithm,
            sshHostKeyFingerprint = if (credentials != null && (host != existing.sshHost || username != existing.sshUsername || port.toInt() != existing.sshPort)) null else existing.sshHostKeyFingerprint,
        ))
        return true
    }
    if (type == LinuxEnvironmentType.PROOT) {
        return when (WeAgentService.linuxEnvironmentManager.createProotEnvironment(name)) {
            is dev.ujhhgtg.wekit.agent.environment.ProotEnvironmentCreationResult.Created -> true
            is dev.ujhhgtg.wekit.agent.environment.ProotEnvironmentCreationResult.MissingPack -> error("Arch Linux extension pack is not installed")
        }
    }
    if (type == LinuxEnvironmentType.CHROOT) {
        return when (WeAgentService.linuxEnvironmentManager.createChrootEnvironment(name)) {
            is dev.ujhhgtg.wekit.agent.environment.ChrootEnvironmentCreationResult.Created -> true
            is dev.ujhhgtg.wekit.agent.environment.ChrootEnvironmentCreationResult.MissingPack -> error("Arch Linux extension pack is not installed")
        }
    }
    require(type == LinuxEnvironmentType.SSH) { "unsupported environment type" }
    val credentials = SshCredentialStore.encrypt(SshCredentials.Password(password))
    WeAgentService.linuxEnvironmentManager.upsert(LinuxEnvironmentEntity(UUID.randomUUID().toString(), name, type, workingDirectory, sshHost = host, sshPort = port.toInt(), sshUsername = username, sshAuthenticationType = "PASSWORD", sshCredentialCiphertext = credentials.ciphertext, sshCredentialIv = credentials.iv))
    return true
}
