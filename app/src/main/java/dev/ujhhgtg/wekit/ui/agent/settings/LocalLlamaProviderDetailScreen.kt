package dev.ujhhgtg.wekit.ui.agent.settings

import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Play_arrow
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.model.local.LlamaHealth
import dev.ujhhgtg.wekit.agent.model.local.LlamaState
import dev.ujhhgtg.wekit.agent.model.local.LocalLlama
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaController
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaModels
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaSync
import dev.ujhhgtg.wekit.extensions.ExtensionPack
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.extensions.ExtensionPackState
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.extensions.LlamaNativePack
import dev.ujhhgtg.wekit.extensions.QwenModelPack
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocalLlamaProviderDetailScreen(
    onOpenModel: (providerId: String, modelId: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current ?: error("activity not provided")
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val state by LocalLlamaController.state.collectAsState()
    val health by LocalLlamaController.health.collectAsState()
    val nativePackState by ExtensionPacks.stateFlow(LlamaNativePack).collectAsState()
    val modelPackState by ExtensionPacks.stateFlow(QwenModelPack).collectAsState()
    val models by remember(LocalLlama.PROVIDER_ID) {
        WeAgentRepository.observeModelsForProvider(LocalLlama.PROVIDER_ID)
    }.collectAsState(initial = emptyList())
    var backend by remember { mutableStateOf("auto") }
    var backendLoaded by remember { mutableStateOf(false) }
    var backendPersisting by remember { mutableStateOf(false) }
    var serverOperation by remember { mutableStateOf<ServerOperation?>(null) }

    LaunchedEffect(Unit) {
        LocalLlamaSync.schedule()
        ExtensionPacks.refresh(LlamaNativePack)
        ExtensionPacks.refresh(QwenModelPack)
        ExtensionPacks.checkUpdates()
        try {
            backend = WeAgentSettings.localComputeBackend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showToast(
                localizedContext.getString(
                    R.string.local_llm_backend_load_failed,
                    e.message ?: e.javaClass.simpleName,
                )
            )
        } finally {
            backendLoaded = true
        }
    }

    val installedById = remember(models, modelPackState) {
        LocalLlamaModels.listInstalled().associateBy { it.id }
    }
    val localModels = models.mapNotNull { model ->
        installedById[model.modelIdRemote]?.let { model to it }
    }
    val startModel = localModels.firstOrNull()
    val starting = state is LlamaState.Starting
    val running = state is LlamaState.Running
    val serverOperationPending = serverOperation != null
    val lifecycleBusy = starting || serverOperationPending
    val backendUnavailable = running && backend != "auto" &&
            health?.backend?.available?.none { it.equals(backend, ignoreCase = true) } == true
    val backendDescription = buildString {
        append(stringResource(R.string.local_llm_backend_restart_note))
        if (backendUnavailable) {
            append(" · ")
            append(stringResource(R.string.local_llm_backend_unavailable))
        }
    }
    val healthDescription = health?.let { healthLine(it) }

    AgentSettingsScaffold(
        title = stringResource(R.string.local_llm_provider_name),
        onBack = onBack,
    ) {
        if (!LlamaNativePack.isSupported()) {
            item {
                SegmentedColumn {
                    item {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.local_llm_device_unsupported),
                            isError = true,
                        )
                    }
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.local_llm_section_server)) {
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = serverStateLine(state),
                        description = healthDescription,
                    )
                }
                item {
                    DropDownMenuWidget(
                        icon = null,
                        iconPlaceholder = false,
                        title = stringResource(R.string.local_llm_backend_label),
                        description = backendDescription,
                        value = backend,
                        options = LocalLlama.BACKENDS.map { value ->
                            DropdownOption(value, backendLabel(value))
                        },
                        enabled = backendLoaded && !backendPersisting && !lifecycleBusy,
                        onValueChange = { value ->
                            if (!backendLoaded || backendPersisting || serverOperation != null ||
                                LocalLlamaController.state.value is LlamaState.Starting
                            ) {
                                return@DropDownMenuWidget
                            }
                            val previous = backend
                            backend = value
                            backendPersisting = true
                            scope.launch {
                                try {
                                    WeAgentSettings.setLocalComputeBackend(value)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    backend = previous
                                    showToast(
                                        localizedContext.getString(
                                            R.string.agent_save_failed,
                                            e.message ?: e.javaClass.simpleName,
                                        )
                                    )
                                } finally {
                                    backendPersisting = false
                                }
                            }
                        },
                    )
                }
            }
        }
        item {
            AgentActionRow {
                AgentListActionButton(
                    label = stringResource(R.string.local_llm_server_start),
                    icon = MaterialSymbols.Outlined.Play_arrow,
                    loading = serverOperation == ServerOperation.START || starting,
                    enabled = !lifecycleBusy && backendLoaded && !backendPersisting && !running &&
                            startModel != null && LlamaNativePack.isSupported(),
                    onClick = {
                        val (model, installed) = startModel ?: return@AgentListActionButton
                        val gguf = LocalLlamaModels.resolveModelFile(model.modelIdRemote)
                            ?: return@AgentListActionButton
                        if (!backendLoaded || backendPersisting || serverOperation != null ||
                            LocalLlamaController.state.value is LlamaState.Starting ||
                            LocalLlamaController.state.value is LlamaState.Running
                        ) {
                            return@AgentListActionButton
                        }
                        serverOperation = ServerOperation.START
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    LocalLlamaController.ensureReady(
                                        gguf = gguf,
                                        nCtx = model.contextWindow ?: installed.defaultContextWindow,
                                        backend = backend,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                showToast(
                                    localizedContext.getString(
                                        R.string.local_llm_server_operation_failed,
                                        e.message ?: e.javaClass.simpleName,
                                    )
                                )
                            } finally {
                                serverOperation = null
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        if (serverOperation != null ||
                            LocalLlamaController.state.value !is LlamaState.Running
                        ) {
                            return@OutlinedButton
                        }
                        serverOperation = ServerOperation.STOP
                        scope.launch {
                            try {
                                LocalLlamaController.stop().join()
                                val after = LocalLlamaController.state.value
                                check(after !is LlamaState.Running && after !is LlamaState.Starting) {
                                    "server remained ${after.javaClass.simpleName} after stop"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                showToast(
                                    localizedContext.getString(
                                        R.string.local_llm_server_operation_failed,
                                        e.message ?: e.javaClass.simpleName,
                                    )
                                )
                            } finally {
                                serverOperation = null
                            }
                        }
                    },
                    enabled = running && !lifecycleBusy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    if (serverOperation == ServerOperation.STOP) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.local_llm_server_stop))
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.local_llm_section_packs)) {
                item { PackStatusWidget(LlamaNativePack, nativePackState) }
                item { PackStatusWidget(QwenModelPack, modelPackState) }
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.local_llm_manage_packs),
                        onClick = {
                            ExtensionPackDialogs.openExtensions(activity, null, autoDownload = false)
                        },
                        trailingContent = {
                            Icon(
                                MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        item { LocalLlamaSectionTitle(stringResource(R.string.local_llm_section_models)) }
        if (localModels.isEmpty()) {
            item {
                SegmentedColumn {
                    item {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.local_llm_no_models_hint),
                        )
                    }
                }
            }
        } else {
            lazySegmentedItems(localModels, key = { it.first.id }) { (model, installed) ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = model.displayName,
                        description = stringResource(
                            R.string.local_llm_model_desc,
                            installed.quant,
                            model.contextWindow ?: installed.defaultContextWindow,
                        ),
                        onClick = { onOpenModel(LocalLlama.PROVIDER_ID, model.id) },
                        trailingContent = {
                            Icon(
                                MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun serverStateLine(state: LlamaState): String = when (state) {
    LlamaState.Stopped -> stringResource(R.string.local_llm_server_state_stopped)
    LlamaState.Starting -> stringResource(R.string.local_llm_server_state_starting)
    is LlamaState.Running -> stringResource(R.string.local_llm_server_state_running, state.port)
    is LlamaState.Failed -> stringResource(R.string.local_llm_server_state_failed, state.reason)
}

@Composable
private fun healthLine(health: LlamaHealth): String = stringResource(
    R.string.local_llm_health_line,
    health.backend.requested,
    health.backend.active,
    health.backend.devices.joinToString().ifBlank { "—" },
    health.backend.gpuLayers,
    health.backend.totalLayers,
    DateUtils.formatElapsedTime(health.uptimeSec),
    health.rssBytes / (1024.0 * 1024.0),
    health.tokensPerSec,
).let { summary ->
    health.backend.fallbackReason?.let { reason ->
        "$summary · ${stringResource(R.string.local_llm_backend_fallback, reason)}"
    } ?: summary
}

private enum class ServerOperation { START, STOP }

@Composable
private fun LocalLlamaSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 16.dp),
    )
}

@Composable
private fun backendLabel(value: String): String = stringResource(
    when (value) {
        "auto" -> R.string.local_llm_backend_auto
        "cpu" -> R.string.local_llm_backend_cpu
        "vulkan" -> R.string.local_llm_backend_vulkan
        "opencl" -> R.string.local_llm_backend_opencl
        else -> error("Unknown local LLM backend: $value")
    }
)

@Composable
private fun PackStatusWidget(pack: ExtensionPack, state: ExtensionPackState) {
    BaseWidget(
        icon = pack.icon,
        title = stringResource(pack.nameRes),
        description = packStateLine(pack, state),
    )
}

@Composable
private fun packStateLine(pack: ExtensionPack, state: ExtensionPackState): String {
    val base = stringResource(pack.descriptionRes)
    val status = when (state) {
        ExtensionPackState.NotInstalled -> stringResource(R.string.extensions_pack_state_not_installed)
        is ExtensionPackState.Downloading -> {
            val percent = (state.progress.coerceIn(0f, 1f) * 100).roundToInt()
            "${stringResource(R.string.extensions_pack_downloading)} $percent%"
        }
        ExtensionPackState.Verifying -> stringResource(R.string.extensions_pack_verifying)
        is ExtensionPackState.Installed -> stringResource(
            R.string.extensions_pack_installed_version,
            state.version,
        )
        is ExtensionPackState.UpdateAvailable -> stringResource(
            R.string.extensions_pack_state_update_available,
            state.installedVersion,
            state.latestVersion,
        )
        is ExtensionPackState.Failed -> stringResource(
            R.string.extensions_pack_state_failed,
            state.reason,
        )
    }
    return "$base\n$status"
}
