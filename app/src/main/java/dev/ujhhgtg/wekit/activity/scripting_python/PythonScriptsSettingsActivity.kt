
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Archive
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Restart_alt
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Wrap_text
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberCodeEditorController
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.extensions.PythonRuntimePack
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginManager
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginManifest
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginRecord
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonPluginStatus
import dev.ujhhgtg.wekit.features.items.scripting_python.plugin.PythonCrashGuard
import dev.ujhhgtg.wekit.features.items.scripting_python.runtime.PythonRuntimeLoader
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.agent.settings.AgentActionRow
import dev.ujhhgtg.wekit.ui.agent.settings.AgentConfirmDialog
import dev.ujhhgtg.wekit.ui.agent.settings.AgentListActionButton
import dev.ujhhgtg.wekit.ui.agent.settings.AgentSettingsScaffold
import dev.ujhhgtg.wekit.ui.agent.settings.rememberCreationBackGuard
import dev.ujhhgtg.wekit.ui.animation.predictiveback.weKitNavTransition
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.ui.content.m3BackdropLayer
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.ui.navigation.LocalNavigator
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import dev.ujhhgtg.wekit.ui.navigation.rememberM3NavEffects
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
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
    @Serializable data class PluginInfo(val pluginId: String?) : PythonSettingsRoute
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
                PythonHomeScreen(
                    activity,
                    onFinish,
                    { navigator.push(PythonSettingsRoute.PluginInfo(null)) },
                ) { navigator.push(PythonSettingsRoute.Detail(it)) }
            }
            entry<PythonSettingsRoute.PluginInfo>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                PythonPluginInfoScreen(route.pluginId, navigator::pop)
            }
            entry<PythonSettingsRoute.Detail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                PythonDetailScreen(
                    route.pluginId,
                    navigator::pop,
                    { navigator.push(PythonSettingsRoute.PluginInfo(route.pluginId)) },
                    { navigator.push(PythonSettingsRoute.Edit(route.pluginId)) },
                    { navigator.push(PythonSettingsRoute.Diagnostics(route.pluginId)) },
                    navigator::pop,
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
private fun PythonHomeScreen(
    activity: ComponentActivity,
    onBack: () -> Unit,
    onNewPlugin: () -> Unit,
    openPlugin: (String) -> Unit,
) {
    val records by PythonPluginManager.records.collectAsState()
    val runtime by PythonRuntimeLoader.status.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedContext = LocalWeKitLocalizedContext.current
    var pendingTrustPlugin by remember { mutableStateOf<String?>(null) }
    var showImportWarning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val importSuccessText = stringResource(R.string.python_import_success)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PythonPluginManager.discover() } }

    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            importing = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = java.io.File(context.cacheDir, "plugin-import-${System.currentTimeMillis()}.zip")
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            temp.outputStream().use(input::copyTo)
                        }
                        PythonPluginManager.importPlugin(temp)
                    } finally {
                        temp.delete()
                    }
                }
            }
            importing = false
            result.fold(
                onSuccess = { showToast(context, importSuccessText) },
                onFailure = {
                    showToast(context, localizedContext.getString(R.string.python_import_failed, it.message ?: ""))
                },
            )
        }
    }

    AgentSettingsScaffold(stringResource(R.string.python_scripts_title), onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.python_runtime_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.extensions_pack_python_runtime_name),
                        description = runtime.state.name + (runtime.version?.let { " · $it" } ?: ""),
                        onClick = { ExtensionPackDialogs.openExtensions(activity, PythonRuntimePack, false) },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.python_plugins_section)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_new_plugin),
                        icon = MaterialSymbols.Outlined.Add,
                        enabled = !importing,
                        onClick = onNewPlugin,
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_import_plugin),
                        icon = MaterialSymbols.Outlined.Archive,
                        enabled = !importing,
                        onClick = { showImportWarning = true },
                    )
                }
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
            val enabledPluginId = pendingTrustPlugin ?: return@AgentConfirmDialog
            pendingTrustPlugin = null
            PythonPluginManager.acceptTrustWarning()
            coroutineScope.launch(Dispatchers.IO) {
                PythonPluginManager.setDesiredEnabled(enabledPluginId, true)
            }
        },
        onDismiss = { pendingTrustPlugin = null },
    )
    // 导入的 zip 携带任意代码，无论之前是否接受过警告，每次导入都要重新确认。
    AgentConfirmDialog(
        show = showImportWarning,
        title = stringResource(R.string.python_security_warning_title),
        message = stringResource(R.string.python_security_warning),
        confirmLabel = stringResource(R.string.dialog_confirm),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            showImportWarning = false
            importZipLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        },
        onDismiss = { showImportWarning = false },
    )
}

/**
 * Create (pluginId == null) / basic-info edit page for a plugin, mirroring
 * [dev.ujhhgtg.wekit.ui.agent.settings.ModelProviderDetailScreen]: draft fields + one save action
 * in creation mode, instant-apply per row in edit mode. The plugin ID doubles as its directory
 * name, so it is only editable before creation.
 */
@Composable
private fun PythonPluginInfoScreen(pluginId: String?, onBack: () -> Unit) {
    val creating = pluginId == null
    val records by PythonPluginManager.records.collectAsState()
    val manifest = pluginId?.let(records::get)?.manifest
    if (!creating && manifest == null) return
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    var draftId by remember { mutableStateOf("") }
    var draftName by remember { mutableStateOf("") }
    var draftVersion by remember { mutableStateOf("1.0.0") }
    var draftAuthor by remember { mutableStateOf("") }
    var draftDescription by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val savable = PythonPluginManager.isValidPluginId(draftId.trim()) &&
        draftName.isNotBlank() && draftVersion.isNotBlank()
    val guardedBack = rememberCreationBackGuard(creating && savable, onBack)
    val confirmLabel = stringResource(R.string.dialog_confirm)
    val cancelLabel = stringResource(R.string.dialog_cancel)

    fun commitInfo(transform: (PythonPluginManifest) -> PythonPluginManifest) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PythonPluginManager.updatePluginInfo(pluginId!!, transform(manifest!!)) }
            }
            result.onFailure {
                showToast(localizedContext.getString(R.string.python_manifest_save_failed, it.message ?: ""))
            }
        }
    }

    AgentSettingsScaffold(
        title = if (creating) stringResource(R.string.python_new_plugin) else manifest?.name ?: pluginId.orEmpty(),
        onBack = guardedBack,
    ) {
        item {
            SegmentedColumn(title = stringResource(R.string.python_plugin_info_section)) {
                if (creating) {
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_id_label),
                            value = draftId,
                            onValueChange = { draftId = it },
                            dialogTitle = stringResource(R.string.python_plugin_id_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                            valueHint = stringResource(R.string.python_plugin_id_hint),
                            filter = { it.trim() },
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_name_label),
                            value = draftName,
                            onValueChange = { draftName = it },
                            dialogTitle = stringResource(R.string.python_plugin_name_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_version_label),
                            value = draftVersion,
                            onValueChange = { draftVersion = it },
                            dialogTitle = stringResource(R.string.python_plugin_version_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_author_label),
                            value = draftAuthor,
                            onValueChange = { draftAuthor = it },
                            dialogTitle = stringResource(R.string.python_plugin_author_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_description_label),
                            value = draftDescription,
                            onValueChange = { draftDescription = it },
                            dialogTitle = stringResource(R.string.python_plugin_description_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                            singleLine = false,
                        )
                    }
                } else {
                    val m = manifest!!
                    item { BaseWidget(title = stringResource(R.string.python_plugin_id_label), description = pluginId) }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_name_label),
                            value = m.name,
                            onValueChange = { value -> commitInfo { info -> info.copy(name = value.trim()) } },
                            dialogTitle = stringResource(R.string.python_plugin_name_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_version_label),
                            value = m.version,
                            onValueChange = { value -> commitInfo { info -> info.copy(version = value.trim()) } },
                            dialogTitle = stringResource(R.string.python_plugin_version_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_author_label),
                            value = m.author,
                            onValueChange = { value -> commitInfo { info -> info.copy(author = value.trim()) } },
                            dialogTitle = stringResource(R.string.python_plugin_author_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = stringResource(R.string.python_plugin_description_label),
                            value = m.description,
                            onValueChange = { value -> commitInfo { info -> info.copy(description = value.trim()) } },
                            dialogTitle = stringResource(R.string.python_plugin_description_label),
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                            singleLine = false,
                        )
                    }
                }
            }
        }
        if (creating) {
            item {
                AgentActionRow {
                    AgentListActionButton(
                        label = stringResource(R.string.action_save),
                        icon = MaterialSymbols.Outlined.Save,
                        loading = saving,
                        enabled = savable,
                        onClick = {
                            saving = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        PythonPluginManager.createPlugin(
                                            draftId.trim(),
                                            draftName.trim(),
                                            draftVersion.trim(),
                                            draftAuthor.trim(),
                                            draftDescription.trim(),
                                        )
                                    }
                                }
                                saving = false
                                result.fold(
                                    onSuccess = { onBack() },
                                    onFailure = {
                                        showToast(
                                            localizedContext.getString(
                                                R.string.python_manifest_save_failed,
                                                it.message ?: "",
                                            ),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PythonDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
    openInfoEdit: () -> Unit,
    openEditor: () -> Unit,
    openDiagnostics: () -> Unit,
    onDeleted: () -> Unit,
) {
    val records by PythonPluginManager.records.collectAsState()
    val record = records[pluginId] ?: return
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedContext = LocalWeKitLocalizedContext.current
    var pendingTrustPlugin by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exportSuccessText = stringResource(R.string.python_export_success)
    val exportZipLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                            PythonPluginManager.exportPlugin(pluginId, output)
                        }
                    }
                }
                result.fold(
                    onSuccess = { showToast(context, exportSuccessText) },
                    onFailure = {
                        showToast(context, localizedContext.getString(R.string.python_export_failed, it.message ?: ""))
                    },
                )
            }
        }
    val inFlight = record.status == PythonPluginStatus.LOADING ||
        record.status == PythonPluginStatus.UNLOADING
    // 基本信息写入 plugin.json,插件运行中禁止编辑;开关本身保持可用以便停用。
    val infoEditable = !inFlight && record.status != PythonPluginStatus.ACTIVE
    AgentSettingsScaffold(record.manifest?.name ?: pluginId, onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.python_plugin_info_section)) {
                item {
                    SwitchWidget(
                        title = record.manifest?.name ?: record.id,
                        description = record.description(),
                        checked = record.desiredEnabled,
                        enabled = record.manifest != null && !inFlight,
                        trailingDivider = true,
                        onClick = {
                            if (infoEditable) {
                                openInfoEdit()
                            } else {
                                showToast(
                                    context,
                                    localizedContext.getString(R.string.python_edit_info_locked),
                                )
                            }
                        },
                        onCheckedChange = { enabled ->
                            if (enabled && !PythonPluginManager.isTrustWarningAccepted()) {
                                pendingTrustPlugin = pluginId
                            } else {
                                coroutineScope.launch(Dispatchers.IO) {
                                    PythonPluginManager.setDesiredEnabled(pluginId, enabled)
                                }
                            }
                        },
                    )
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.python_edit_entry),
                        icon = MaterialSymbols.Outlined.Edit,
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
                        title = stringResource(R.string.python_export_plugin),
                        icon = MaterialSymbols.Outlined.Upload,
                        onClick = { exportZipLauncher.launch("$pluginId.zip") },
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
                        title = stringResource(R.string.python_delete_plugin),
                        icon = MaterialSymbols.Outlined.Delete_forever,
                        isError = true,
                        enabled = !inFlight,
                        onClick = { confirmDelete = true },
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
    AgentConfirmDialog(
        show = confirmDelete,
        title = stringResource(R.string.python_delete_plugin),
        message = stringResource(R.string.python_delete_plugin_confirm),
        confirmLabel = stringResource(R.string.python_delete_plugin),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
            confirmDelete = false
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { PythonPluginManager.deletePlugin(pluginId) }
                }
                result.fold(
                    onSuccess = { onDeleted() },
                    onFailure = {
                        showToast(
                            context,
                            localizedContext.getString(R.string.python_delete_failed, it.message ?: ""),
                        )
                    },
                )
            }
        },
        onDismiss = { confirmDelete = false },
    )
    AgentConfirmDialog(
        show = pendingTrustPlugin != null,
        title = stringResource(R.string.python_security_warning_title),
        message = stringResource(R.string.python_security_warning),
        confirmLabel = stringResource(R.string.dialog_confirm),
        dismissLabel = stringResource(R.string.dialog_cancel),
        destructive = true,
        onConfirm = {
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
private fun PythonEditorScreen(pluginId: String, onBack: () -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val record = records[pluginId] ?: return
    val entry = record.manifest?.entry ?: return
    val entryPath = java.io.File(record.root, entry.replace('.', java.io.File.separatorChar))
    val sourceFile = java.io.File(entryPath.path + ".py").takeIf { it.isFile }
        ?: java.io.File(entryPath, "__init__.py")
    val controller = rememberCodeEditorController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    var softWrap by rememberSaveable { mutableStateOf(Preferences.pythonEditorSoftWrap) }
    val savedText = stringResource(R.string.python_saved)
    val saveFailedText = stringResource(R.string.python_save_failed)
    val discardTitle = stringResource(R.string.python_discard_changes_title)
    val discardMessage = stringResource(R.string.python_discard_changes_message)
    val discardConfirm = stringResource(R.string.python_discard_changes_confirm)
    val guardedBack = rememberCreationBackGuard(
        controller.isModified,
        onBack,
        dialogTitle = discardTitle,
        dialogMessage = discardMessage,
        confirmLabel = discardConfirm,
    )
    LaunchedEffect(sourceFile) {
        controller.setDocument(withContext(Dispatchers.IO) { sourceFile.readText() })
    }

    // 编辑器内部自行滚动并消费底部系统栏 / IME insets：只给顶部留出固定顶栏，底部不加 padding。
    // backdrop 捕获层必须只包内容——若连顶栏一起捕获，顶栏模糊会采样到自己的输出，
    // AGSL 着色器无限嵌套，渲染线程栈溢出（SIGSEGV）。
    val barBackdrop = rememberMaterial3BlurBackdrop()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                modifier = Modifier.m3AppBarBlur(barBackdrop),
                title = { Text(stringResource(R.string.python_edit_entry)) },
                navigationIcon = {
                    Row {
                        ExpressiveBackButton(onClick = guardedBack)
                        Spacer(modifier = Modifier.size(16.dp))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            softWrap = !softWrap
                            Preferences.pythonEditorSoftWrap = softWrap
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (softWrap) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            contentColor = if (softWrap) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                LocalContentColor.current
                            },
                        ),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Wrap_text,
                            contentDescription = stringResource(R.string.python_editor_soft_wrap),
                        )
                    }
                    IconButton(
                        enabled = controller.isModified && !saving,
                        onClick = {
                            val version = controller.documentVersion
                            val text = controller.getText(controller.lineEnding)
                            saving = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { sourceFile.writeText(text) }
                                }
                                saving = false
                                result.fold(
                                    onSuccess = {
                                        controller.markSaved(version)
                                        showToast(context, savedText)
                                    },
                                    onFailure = { showToast(context, it.message ?: saveFailedText) },
                                )
                            }
                        },
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Save,
                                contentDescription = stringResource(R.string.python_save_entry),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barBackdrop.m3AppBarColor(),
                    scrolledContainerColor = barBackdrop.m3AppBarColor(),
                ),
            )
        },
    ) { innerPadding ->
        CodeEditor(
            controller = controller,
            language = EditorLanguage.PlainText,
            highlighter = remember { PythonHighlighter() },
            colors = if (ThemeSettings.themeMode.resolve()) EditorColors.Default else EditorColors.Light,
            softWrap = softWrap,
            modifier = Modifier
                .fillMaxSize()
                .m3BackdropLayer(barBackdrop)
                .padding(top = innerPadding.calculateTopPadding()),
        )
    }
}

@Composable
private fun PythonDiagnosticsScreen(pluginId: String, onBack: () -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val runtime by PythonRuntimeLoader.status.collectAsState()
    val record = records[pluginId] ?: return
    val crashMarker = remember(pluginId) { PythonCrashGuard.suspect()?.takeIf { it.pluginId == pluginId } }
    val mountedRuntime = PythonRuntimePack.mounted()
    val context = LocalContext.current
    val environment = remember {
        "process=${TargetProcesses.currentName}\n" +
            "abi=${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "pageSize=${Os.sysconf(OsConstants._SC_PAGESIZE)}\n" +
            "loader=${StartupInfo.loaderService.javaClass.name}\n" +
            "moduleClassLoader=${PythonRuntimeLoader::class.java.classLoader}"
    }
    val runtimePackText = mountedRuntime?.let {
        "version=${it.manifest.version}\nsha256=${it.manifest.sha256}\napk=${it.runtimeApk}\nnative=${it.nativeDirectory}"
    } ?: stringResource(R.string.python_diag_not_mounted)
    val runtimeTitle = stringResource(R.string.python_diag_runtime)
    val runtimePackTitle = stringResource(R.string.python_diag_runtime_pack)
    val environmentTitle = stringResource(R.string.python_diag_environment)
    val pluginTitle = stringResource(R.string.python_diag_plugin)
    val tracebackTitle = stringResource(R.string.python_traceback)
    val diagnosticsText = buildString {
        appendLine(runtimeTitle); appendLine(runtime)
        appendLine()
        appendLine(runtimePackTitle); appendLine(runtimePackText)
        appendLine()
        appendLine(environmentTitle); appendLine(environment)
        appendLine()
        appendLine(pluginTitle); appendLine(record.status.name)
        crashMarker?.let {
            appendLine()
            appendLine("CrashGuard"); appendLine(it)
        }
        record.traceback?.let {
            appendLine()
            appendLine(tracebackTitle); appendLine(it)
        }
    }
    val copiedText = stringResource(R.string.copied_to_clipboard)
    AgentSettingsScaffold(
        title = stringResource(R.string.python_diagnostics_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                copyToClipboard(context, diagnosticsText)
                showToast(context, copiedText)
            }) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Content_copy,
                    contentDescription = stringResource(R.string.python_diagnostics_copy),
                )
            }
        },
    ) {
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
