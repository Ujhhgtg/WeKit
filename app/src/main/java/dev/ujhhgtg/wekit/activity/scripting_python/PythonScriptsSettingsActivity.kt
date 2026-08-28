@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ujhhgtg.wekit.activity.scripting_python

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Restart_alt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.extensions.PythonRuntimePack
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginManager
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginRecord
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginStatus
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonCrashGuard
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLoader
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.agent.settings.AgentConfirmDialog
import dev.ujhhgtg.wekit.ui.agent.settings.AgentSettingsScaffold
import dev.ujhhgtg.wekit.ui.animation.predictiveback.weKitNavTransition
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.navigation.LocalNavigator
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import dev.ujhhgtg.wekit.ui.navigation.rememberM3NavEffects
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import kotlin.io.path.div

@Keep
class PythonScriptsSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme { PythonSettingsRoot(this@PythonScriptsSettingsActivity, ::finish) }
            }
        }
    }
}

@Serializable
private sealed interface PythonSettingsRoute : NavKey {
    @Serializable data object Home : PythonSettingsRoute
    @Serializable data class Detail(val pluginId: String) : PythonSettingsRoute
    @Serializable data class Edit(val pluginId: String) : PythonSettingsRoute
    @Serializable data class Diagnostics(val pluginId: String) : PythonSettingsRoute
}

@Composable
private fun PythonSettingsRoot(activity: ComponentActivity, onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<PythonSettingsRoute>(PythonSettingsRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (navigator.backStackSize() <= 1) onFinish() else navigator.pop() },
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<PythonSettingsRoute.Home> {
                PythonHomeScreen(activity, onFinish) { navigator.push(PythonSettingsRoute.Detail(it)) }
            }
            entry<PythonSettingsRoute.Detail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                PythonDetailScreen(
                    route.pluginId,
                    navigator::pop,
                    { navigator.push(PythonSettingsRoute.Edit(route.pluginId)) },
                    { navigator.push(PythonSettingsRoute.Diagnostics(route.pluginId)) },
                )
            }
            entry<PythonSettingsRoute.Edit>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                PythonEditorScreen(route.pluginId, navigator::pop)
            }
            entry<PythonSettingsRoute.Diagnostics>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                PythonDiagnosticsScreen(route.pluginId, navigator::pop)
            }
        }
    }
}

@Composable
private fun PythonHomeScreen(activity: ComponentActivity, onBack: () -> Unit, openPlugin: (String) -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val runtime by PythonRuntimeLoader.status.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var pendingTrustPlugin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PythonPluginManager.discover() } }

    AgentSettingsScaffold(stringResource(R.string.python_scripts_title), onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.python_runtime_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.extensions_pack_python_runtime_name),
                        description = runtime.state.name + (runtime.version?.let { " · $it" } ?: ""),
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_runtime_install),
                        icon = MaterialSymbols.Outlined.Download,
                        onClick = { ExtensionPackDialogs.openExtensions(activity, PythonRuntimePack, false) },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.python_plugins_section)) {
                if (records.isEmpty()) {
                    item { BaseWidget(title = stringResource(R.string.python_plugins_empty)) }
                } else {
                    records.values.forEach { record ->
                        item(key = record.id) {
                            SwitchWidget(
                                title = record.manifest?.name ?: record.id,
                                description = "${record.id} · ${record.status.name}" +
                                    (record.lastError?.let { "\n$it" } ?: ""),
                                checked = record.desiredEnabled,
                                enabled = record.manifest != null &&
                                    record.status != PythonPluginStatus.LOADING &&
                                    record.status != PythonPluginStatus.UNLOADING,
                                onClick = { openPlugin(record.id) },
                                trailingDivider = true,
                                onCheckedChange = { enabled ->
                                    if (enabled && !PythonPluginManager.isTrustWarningAccepted()) {
                                        pendingTrustPlugin = record.id
                                    } else {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            PythonPluginManager.setDesiredEnabled(record.id, enabled)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.python_security_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_security_warning_title),
                        description = stringResource(R.string.python_security_warning),
                        isError = true,
                    )
                }
            }
        }
    }
    AgentConfirmDialog(
        show = pendingTrustPlugin != null,
        title = stringResource(R.string.python_security_warning_title),
        message = stringResource(R.string.python_security_warning),
        confirmLabel = stringResource(R.string.dialog_confirm),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            val pluginId = pendingTrustPlugin ?: return@AgentConfirmDialog
            pendingTrustPlugin = null
            PythonPluginManager.acceptTrustWarning()
            coroutineScope.launch(Dispatchers.IO) {
                PythonPluginManager.setDesiredEnabled(pluginId, true)
            }
        },
        onDismiss = { pendingTrustPlugin = null },
    )
}

@Composable
private fun PythonDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
    openEditor: () -> Unit,
    openDiagnostics: () -> Unit,
) {
    val records by PythonPluginManager.records.collectAsState()
    val record = records[pluginId] ?: return
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val inFlight = record.status == PythonPluginStatus.LOADING ||
        record.status == PythonPluginStatus.UNLOADING
    AgentSettingsScaffold(record.manifest?.name ?: pluginId, onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.python_plugin_info_section)) {
                item { BaseWidget(title = pluginId, description = record.description()) }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_edit_entry),
                        enabled = !inFlight,
                        onClick = openEditor,
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_open_directory),
                        icon = MaterialSymbols.Outlined.Folder,
                        onClick = {
                            val relative = record.root.relativeTo(Environment.getExternalStorageDirectory())
                                .path.replace(java.io.File.separatorChar, '/')
                            val uri = DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:$relative",
                            )
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                                type = DocumentsContract.Document.MIME_TYPE_DIR
                            })
                        },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.python_plugin_actions_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_reload_plugin),
                        icon = MaterialSymbols.Outlined.Refresh,
                        enabled = record.desiredEnabled && !inFlight,
                        onClick = { coroutineScope.launch(Dispatchers.IO) { PythonPluginManager.reload(pluginId) } },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_view_diagnostics),
                        icon = MaterialSymbols.Outlined.Bug_report,
                        onClick = openDiagnostics,
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_clear_data),
                        icon = MaterialSymbols.Outlined.Delete,
                        isError = true,
                        enabled = record.status != PythonPluginStatus.ACTIVE && !inFlight,
                        onClick = { confirmClear = true },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_restart_wechat),
                        icon = MaterialSymbols.Outlined.Restart_alt,
                        onClick = { Process.killProcess(Process.myPid()) },
                    )
                }
            }
        }
    }
    AgentConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.python_clear_data),
        message = stringResource(R.string.python_clear_data_confirm),
        confirmLabel = stringResource(R.string.python_clear_data),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            confirmClear = false
            coroutineScope.launch(Dispatchers.IO) {
                (KnownPaths.moduleData / "python" / "data" / pluginId).toFile().deleteRecursively()
            }
        },
        onDismiss = { confirmClear = false },
    )
}

@Composable
private fun PythonEditorScreen(pluginId: String, onBack: () -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val record = records[pluginId] ?: return
    val entry = record.manifest?.entry ?: return
    val entryPath = java.io.File(record.root, entry.replace('.', java.io.File.separatorChar))
    val sourceFile = java.io.File(entryPath.path + ".py").takeIf { it.isFile }
        ?: java.io.File(entryPath, "__init__.py")
    var text by remember(pluginId) { mutableStateOf("") }
    var feedback by remember(pluginId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(sourceFile) {
        text = withContext(Dispatchers.IO) { sourceFile.readText() }
    }
    AgentSettingsScaffold(stringResource(R.string.python_edit_entry), onBack) {
        item {
            BaseItemContainer {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 14,
                    )
                    feedback?.let { Text(it, Modifier.padding(top = 8.dp)) }
                    Button(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { sourceFile.writeText(text) }
                                }
                                feedback = result.fold(
                                    onSuccess = { context.getString(R.string.python_saved) },
                                    onFailure = { it.message },
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.python_save_entry)) }
                }
            }
        }
    }
}

@Composable
private fun PythonDiagnosticsScreen(pluginId: String, onBack: () -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val runtime by PythonRuntimeLoader.status.collectAsState()
    val record = records[pluginId] ?: return
    val crashMarker = remember(pluginId) { PythonCrashGuard.suspect()?.takeIf { it.pluginId == pluginId } }
    val mountedRuntime = PythonRuntimePack.mounted()
    val environment = remember {
        "process=${TargetProcesses.currentName}\n" +
            "abi=${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "pageSize=${Os.sysconf(OsConstants._SC_PAGESIZE)}\n" +
            "loader=${StartupInfo.loaderService.javaClass.name}\n" +
            "moduleClassLoader=${PythonRuntimeLoader::class.java.classLoader}"
    }
    AgentSettingsScaffold(stringResource(R.string.python_diagnostics_title), onBack) {
        item {
            SegmentedColumn {
                item { BaseWidget(title = stringResource(R.string.python_diag_runtime), description = runtime.toString()) }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_diag_runtime_pack),
                        description = mountedRuntime?.let {
                            "version=${it.manifest.version}\nsha256=${it.manifest.sha256}\napk=${it.runtimeApk}\nnative=${it.nativeDirectory}"
                        } ?: stringResource(R.string.python_diag_not_mounted),
                    )
                }
                item { BaseWidget(title = stringResource(R.string.python_diag_environment), description = environment) }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_diag_p0_title),
                        description = stringResource(R.string.python_diag_p0_unverified),
                        isError = true,
                    )
                }
                item { BaseWidget(title = stringResource(R.string.python_diag_plugin), description = record.status.name) }
                crashMarker?.let { marker ->
                    item { BaseWidget(title = "CrashGuard", description = marker.toString(), isError = true) }
                }
                record.traceback?.let { traceback ->
                    item { BaseWidget(title = stringResource(R.string.python_traceback), description = traceback, isError = true) }
                }
            }
        }
    }
}

private fun PythonPluginRecord.description(): String = buildList {
    manifest?.version?.let { add(it) }
    manifest?.author?.takeIf(String::isNotBlank)?.let(::add)
    manifest?.description?.takeIf(String::isNotBlank)?.let(::add)
    add(status.name)
}.joinToString(" · ")
