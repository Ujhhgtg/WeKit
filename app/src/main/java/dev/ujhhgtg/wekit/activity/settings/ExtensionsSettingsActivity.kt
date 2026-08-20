@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.ujhhgtg.wekit.activity.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Cloud_download
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.ExtensionPack
import dev.ujhhgtg.wekit.extensions.ExtensionPackState
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Downloading
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Failed
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Installed
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.NotInstalled
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.UpdateAvailable
import dev.ujhhgtg.wekit.extensions.ExtensionPackState.Verifying
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.agent.settings.AgentConfirmDialog
import dev.ujhhgtg.wekit.ui.agent.settings.AgentListActionButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import java.util.Locale

/**
 * 扩展包管理页。入口：设置页“更新”分组的“扩展包”行；依赖弹窗带
 * EXTRA_PACK_ID + EXTRA_AUTO_DOWNLOAD 跳入并立即开始下载。
 *
 * 命名以 SettingsActivity 结尾，使 ActivityProxy 走透明启动代理。
 */
@Keep
class ExtensionsSettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACK_ID = "pack_id"
        const val EXTRA_AUTO_DOWNLOAD = "auto_download"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    ExtensionsRoot(
                        autoPackId = intent.getStringExtra(EXTRA_PACK_ID),
                        autoDownload = intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false),
                        onFinish = ::finish,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionsRoot(autoPackId: String?, autoDownload: Boolean, onFinish: () -> Unit) {
    // Re-scan disk on open so installed packs show their real state, then check
    // the remote index to surface available updates.
    LaunchedEffect(Unit) {
        ExtensionPacks.packs.forEach(ExtensionPacks::refresh)
        ExtensionPacks.checkUpdates()
    }
    // Deep-link auto-download: refresh once, then start downloading if it would help.
    LaunchedEffect(autoPackId, autoDownload) {
        if (!autoDownload || autoPackId == null) return@LaunchedEffect
        val pack = ExtensionPacks.byId(autoPackId) ?: return@LaunchedEffect
        ExtensionPacks.refresh(pack)
        val state = ExtensionPacks.stateFlow(pack).value
        if (state is NotInstalled || state is Failed) {
            ExtensionPacks.download(pack)
        }
    }

    M3ListScaffold(
        title = stringResource(R.string.extensions_screen_title),
        navigationIcon = { ExpressiveBackButton(onClick = onFinish) },
    ) {
        for (pack in ExtensionPacks.packs) {
            item(key = pack.id) { PackGroup(pack) }
        }
    }
}

@Composable
private fun PackGroup(pack: ExtensionPack) {
    val state by ExtensionPacks.stateFlow(pack).collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val localizedContext = LocalWeKitLocalizedContext.current

    SegmentedColumn {
        item(key = "info") {
            BaseWidget(
                title = stringResource(pack.nameRes),
                description = descriptionLine(pack, state),
                icon = pack.icon,
            )
        }
        when (val s = state) {
            is Downloading -> item(key = "downloading") {
                BaseItemContainer {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${formatBytes(s.bytesDownloaded)} / ${formatBytes(s.bytesTotal)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { ExtensionPacks.cancelDownload(pack) }) {
                                Text(stringResource(R.string.extensions_pack_cancel))
                            }
                        }
                    }
                }
            }
            is Verifying -> item(key = "verifying") {
                BaseWidget(
                    title = stringResource(R.string.extensions_pack_verifying),
                    description = stringResource(R.string.extensions_pack_downloading),
                )
            }
            else -> item(key = "actions") {
                BaseItemContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AgentListActionButton(
                            label = stringResource(
                                when (s) {
                                    is UpdateAvailable -> R.string.extensions_pack_update
                                    is Failed -> R.string.extensions_pack_retry
                                    else -> R.string.extensions_pack_download
                                }
                            ),
                            icon = MaterialSymbols.Outlined.Cloud_download,
                            onClick = { ExtensionPacks.download(pack) },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = s is Installed || s is UpdateAvailable,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.extensions_pack_delete)) }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AgentConfirmDialog(
            show = true,
            title = stringResource(R.string.extensions_pack_delete_confirm_title),
            message = stringResource(R.string.extensions_pack_delete_confirm_msg),
            confirmLabel = stringResource(R.string.extensions_pack_delete),
            dismissLabel = stringResource(R.string.dialog_cancel),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                if (!ExtensionPacks.delete(pack)) {
                    Toast.makeText(
                        context,
                        localizedContext.getString(R.string.extensions_pack_in_use),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun descriptionLine(pack: ExtensionPack, state: ExtensionPackState): String {
    val base = stringResource(pack.descriptionRes)
    val status = when (state) {
        is NotInstalled -> stringResource(R.string.extensions_pack_state_not_installed)
        is Downloading -> stringResource(R.string.extensions_pack_downloading)
        is Verifying -> stringResource(R.string.extensions_pack_verifying)
        is Installed -> stringResource(R.string.extensions_pack_installed_version, state.version)
        is UpdateAvailable -> stringResource(R.string.extensions_pack_state_update_available, state.installedVersion, state.latestVersion)
        is Failed -> stringResource(R.string.extensions_pack_state_failed, state.reason)
    }
    return "$base\n$status"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1fMB", mb)
}
