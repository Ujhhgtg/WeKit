package dev.ujhhgtg.wekit.ui.agent.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Play_arrow
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.entity.LinuxEnvironmentEntity
import dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType
import dev.ujhhgtg.wekit.agent.environment.NATIVE_ENVIRONMENT_ID
import dev.ujhhgtg.wekit.agent.ssh.SshCredentialStore
import dev.ujhhgtg.wekit.agent.ssh.SshCredentials
import dev.ujhhgtg.wekit.agent.ssh.SshHostKeyException
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.serialization.json.jsonObject

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
    var authenticationType by remember { mutableStateOf("PASSWORD") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("/root") }
    var environmentVariablesJson by remember { mutableStateOf("{}") }
    var error by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingHostKey by remember { mutableStateOf<SshHostKeyException?>(null) }
    var operation by remember { mutableStateOf<EnvironmentOperation?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingChrootOperation by remember { mutableStateOf<EnvironmentOperation?>(null) }
    val activity = LocalContext.current as Activity
    val privateKeyImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { activity.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                .onSuccess { privateKey = it }
                .onFailure { error = it.message }
        }
    }
    val isNative = environmentId == NATIVE_ENVIRONMENT_ID
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
        if (isNative) {
            val native = WeAgentService.linuxEnvironmentManager.nativeSnapshot
            name = native.displayName
            type = LinuxEnvironmentType.NATIVE
            workingDirectory = native.workingDirectory
            environmentVariablesJson = kotlinx.serialization.json.JsonObject(
                native.environmentVariables.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
            ).toString()
        }
        existing = environmentId?.takeUnless { isNative }?.let { WeAgentRepository.getLinuxEnvironment(it) }
        existing?.let {
            name = it.name; type = it.type; host = it.sshHost.orEmpty(); port = (it.sshPort ?: 22).toString()
            username = it.sshUsername.orEmpty(); workingDirectory = it.workingDirectory
            authenticationType = it.sshAuthenticationType ?: "PASSWORD"
            environmentVariablesJson = it.environmentVariablesJson
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
                        enabled = !busy && !isNative,
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
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.agent_linux_environment_variables),
                        value = environmentVariablesJson,
                        enabled = !busy,
                        onValueChange = { environmentVariablesJson = it },
                        dialogTitle = stringResource(R.string.agent_linux_environment_variables),
                        confirmLabel = stringResource(android.R.string.ok),
                        dismissLabel = stringResource(android.R.string.cancel),
                    )
                }
                if (type == LinuxEnvironmentType.SSH) {
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_host), value = host, enabled = !busy, onValueChange = { host = it }, dialogTitle = stringResource(R.string.agent_linux_environment_host), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_port), value = port, enabled = !busy, onValueChange = { port = it.filter(Char::isDigit) }, dialogTitle = stringResource(R.string.agent_linux_environment_port), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_username), value = username, enabled = !busy, onValueChange = { username = it }, dialogTitle = stringResource(R.string.agent_linux_environment_username), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    item { BaseWidget(title = stringResource(R.string.agent_linux_environment_auth_password), description = if (authenticationType == "PASSWORD") stringResource(R.string.agent_linux_environment_selected) else null, enabled = !busy, onClick = { authenticationType = "PASSWORD" }) }
                    item { BaseWidget(title = stringResource(R.string.agent_linux_environment_auth_private_key), description = if (authenticationType == "PRIVATE_KEY") stringResource(R.string.agent_linux_environment_selected) else null, enabled = !busy, onClick = { authenticationType = "PRIVATE_KEY" }) }
                    if (authenticationType == "PASSWORD") {
                        item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_password), value = password, enabled = !busy, password = true, onValueChange = { password = it }, dialogTitle = stringResource(R.string.agent_linux_environment_password), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    } else {
                        item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_private_key), value = privateKey, enabled = !busy, password = true, onValueChange = { privateKey = it }, dialogTitle = stringResource(R.string.agent_linux_environment_private_key), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                        item { BaseWidget(title = stringResource(R.string.agent_linux_environment_import_private_key), enabled = !busy, onClick = { privateKeyImporter.launch("*/*") }) }
                        item { TextFieldDialogWidget(title = stringResource(R.string.agent_linux_environment_passphrase), value = passphrase, enabled = !busy, password = true, onValueChange = { passphrase = it }, dialogTitle = stringResource(R.string.agent_linux_environment_passphrase), confirmLabel = stringResource(android.R.string.ok), dismissLabel = stringResource(android.R.string.cancel)) }
                    }
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
                            if (type == LinuxEnvironmentType.CHROOT && existing == null) {
                                pendingChrootOperation = EnvironmentOperation.SAVE
                                return@BaseWidget
                            }
                            operation = EnvironmentOperation.SAVE
                            status = null
                            scope.launch {
                                runCatching { saveOrCreate(environmentId, existing, name, type, workingDirectory, environmentVariablesJson, host, port, username, authenticationType, password, privateKey, passphrase, false) }
                                    .onSuccess { created ->
                                        if (created) onBack()
                                    }.onFailure {
                                        if (it is SshHostKeyException) {
                                            pendingHostKey = it
                                        } else if (it is MissingArchPackException) {
                                            ExtensionPackDialogs.requireArchLinux(activity)
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
                if (environmentId != null) {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Play_arrow,
                            title = stringResource(R.string.agent_linux_environment_test),
                            enabled = !busy,
                            onClick = {
                                if (busy) return@BaseWidget
                                if (type == LinuxEnvironmentType.CHROOT) {
                                    pendingChrootOperation = EnvironmentOperation.TEST
                                    return@BaseWidget
                                }
                                operation = EnvironmentOperation.TEST
                                status = null
                                scope.launch {
                                    runCatching { WeAgentService.linuxEnvironmentManager.checkHealth(environmentId) }
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
                    if (!isNative) item { BaseWidget(icon = MaterialSymbols.Outlined.Delete, title = stringResource(R.string.action_delete), enabled = !busy, onClick = { showDelete = true }) }
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
    if (pendingChrootOperation != null) {
        AgentConfirmDialog(
            true,
            stringResource(R.string.agent_linux_environment_chroot_confirm_title),
            stringResource(R.string.agent_linux_environment_chroot_confirm_message),
            stringResource(android.R.string.ok),
            stringResource(android.R.string.cancel),
            destructive = true,
            loading = busy,
            onConfirm = {
                val requested = pendingChrootOperation ?: return@AgentConfirmDialog
                operation = requested
                status = null
                scope.launch {
                    if (requested == EnvironmentOperation.SAVE) {
                        runCatching { saveOrCreate(environmentId, existing, name, type, workingDirectory, environmentVariablesJson, host, port, username, authenticationType, password, privateKey, passphrase, true) }
                            .onSuccess { if (it) onBack() }
                            .onFailure {
                                if (it is MissingArchPackException) ExtensionPackDialogs.requireArchLinux(activity)
                                else error = it.message
                            }
                    } else {
                        runCatching { WeAgentService.linuxEnvironmentManager.checkHealth(requireNotNull(environmentId), true) }
                            .onSuccess { status = it.detail ?: healthyStatus }
                            .onFailure { error = it.message }
                    }
                    operation = null
                    pendingChrootOperation = null
                }
            },
            onDismiss = { if (!busy) pendingChrootOperation = null },
        )
    }
}

private enum class EnvironmentOperation { SAVE, TEST, DELETE, TRUST }

private class MissingArchPackException : IllegalStateException("Arch Linux extension pack is not installed")

private suspend fun saveOrCreate(id: String?, existing: LinuxEnvironmentEntity?, name: String, type: LinuxEnvironmentType, workingDirectory: String, environmentVariablesJson: String, host: String, port: String, username: String, authenticationType: String, password: String, privateKey: String, passphrase: String, chrootApproved: Boolean): Boolean {
    require(name.isNotBlank()) { "name is required" }
    val normalizedVariables = kotlinx.serialization.json.Json.parseToJsonElement(environmentVariablesJson).jsonObject
        .also { variables -> variables.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
                "environment variables must be a JSON object containing string values"
            }
        } }.toString()
    if (id == NATIVE_ENVIRONMENT_ID) {
        WeAgentService.linuxEnvironmentManager.updateNativeConfiguration(workingDirectory, normalizedVariables)
        return true
    }
    if (existing != null) {
        val credentials = when {
            type != LinuxEnvironmentType.SSH -> null
            authenticationType == "PASSWORD" && password.isNotEmpty() -> SshCredentialStore.encrypt(SshCredentials.Password(password))
            authenticationType == "PRIVATE_KEY" && privateKey.isNotEmpty() -> SshCredentialStore.encrypt(SshCredentials.PrivateKey(privateKey, passphrase.takeIf(String::isNotEmpty)))
            authenticationType != existing.sshAuthenticationType -> error("credentials are required when changing SSH authentication type")
            else -> null
        }
        WeAgentService.linuxEnvironmentManager.upsert(existing.copy(
            name = name, workingDirectory = workingDirectory, environmentVariablesJson = normalizedVariables,
            sshHost = if (type == LinuxEnvironmentType.SSH) host else existing.sshHost,
            sshPort = if (type == LinuxEnvironmentType.SSH) port.toInt() else existing.sshPort,
            sshUsername = if (type == LinuxEnvironmentType.SSH) username else existing.sshUsername,
            sshAuthenticationType = if (type == LinuxEnvironmentType.SSH) authenticationType else existing.sshAuthenticationType,
            sshCredentialCiphertext = credentials?.ciphertext ?: existing.sshCredentialCiphertext,
            sshCredentialIv = credentials?.iv ?: existing.sshCredentialIv,
            sshHostKeyAlgorithm = if (credentials != null && (host != existing.sshHost || username != existing.sshUsername || port.toInt() != existing.sshPort)) null else existing.sshHostKeyAlgorithm,
            sshHostKeyFingerprint = if (credentials != null && (host != existing.sshHost || username != existing.sshUsername || port.toInt() != existing.sshPort)) null else existing.sshHostKeyFingerprint,
        ))
        return true
    }
    if (type == LinuxEnvironmentType.PROOT) {
        return when (WeAgentService.linuxEnvironmentManager.createProotEnvironment(
            name,
            workingDirectory = workingDirectory,
            environmentVariablesJson = normalizedVariables,
        )) {
            is dev.ujhhgtg.wekit.agent.environment.ProotEnvironmentCreationResult.Created -> true
            is dev.ujhhgtg.wekit.agent.environment.ProotEnvironmentCreationResult.MissingPack -> throw MissingArchPackException()
        }
    }
    if (type == LinuxEnvironmentType.CHROOT) {
        return when (WeAgentService.linuxEnvironmentManager.createChrootEnvironment(
            name,
            workingDirectory = workingDirectory,
            environmentVariablesJson = normalizedVariables,
            highRiskApproved = chrootApproved,
        )) {
            is dev.ujhhgtg.wekit.agent.environment.ChrootEnvironmentCreationResult.Created -> true
            is dev.ujhhgtg.wekit.agent.environment.ChrootEnvironmentCreationResult.MissingPack -> throw MissingArchPackException()
        }
    }
    require(type == LinuxEnvironmentType.SSH) { "unsupported environment type" }
    val credentials = if (authenticationType == "PASSWORD") {
        SshCredentialStore.encrypt(SshCredentials.Password(password.also { require(it.isNotEmpty()) { "password is required" } }))
    } else {
        SshCredentialStore.encrypt(SshCredentials.PrivateKey(privateKey.also { require(it.isNotEmpty()) { "private key is required" } }, passphrase.takeIf(String::isNotEmpty)))
    }
    WeAgentService.linuxEnvironmentManager.upsert(LinuxEnvironmentEntity(UUID.randomUUID().toString(), name, type, workingDirectory, environmentVariablesJson = normalizedVariables, sshHost = host, sshPort = port.toInt(), sshUsername = username, sshAuthenticationType = authenticationType, sshCredentialCiphertext = credentials.ciphertext, sshCredentialIv = credentials.iv))
    return true
}
