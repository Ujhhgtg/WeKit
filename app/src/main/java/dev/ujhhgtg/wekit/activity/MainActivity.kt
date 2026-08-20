package dev.ujhhgtg.wekit.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Info
import com.composables.icons.materialsymbols.outlinedfilled.More_vert
import com.composables.icons.materialsymbols.outlinedfilled.Warning
import com.topjohnwu.superuser.Shell
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleAppTheme
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.androidUserId
import dev.ujhhgtg.wekit.utils.android.getEnabled
import dev.ujhhgtg.wekit.utils.android.setEnabled
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.hook_status.HookStatus
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.registerBshSnapshotDecompileLaunchers
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var isLaunchingWeChat = false

    override fun onStop() {
        super.onStop()
        if (isLaunchingWeChat) {
            isLaunchingWeChat = false
            finishAndRemoveTask()
        }
    }

    private val selectFileLauncher = registerBshSnapshotDecompileLaunchers()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.action == ManagerLaunchContract.ACTION_OPEN_LSPOSED_MANAGER) {
            handleOpenLsposedManager()
            return
        }

        if (intent?.action == TelegramDatabaseImportContract.ACTION_PICK_ROOT_STICKER_SETS) {
            if (!PackageNames.isWeChat(callingPackage.orEmpty())) {
                setResult(
                    RESULT_CANCELED,
                    Intent().putExtra(
                        TelegramDatabaseImportContract.EXTRA_ERROR,
                        localizedString(R.string.module_app_sticker_import_invalid_caller),
                    ),
                )
                finish()
                return
            }
            Shell.getShell()
            setContent {
                ModuleAppTheme {
                    RootTelegramStickerSetPickerContent(
                        discoverInstances = {
                            RootTelegramStickerSetRepository.discoverInstances(
                                this,
                                applicationInfo.uid / 100000,
                            )
                        },
                        readInstalledSets = { instance ->
                            RootTelegramStickerSetRepository.readInstalledSets(
                                this,
                                cacheDir,
                                applicationInfo.uid,
                                instance,
                            )
                        },
                        onCancel = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                        onComplete = { stickerSets ->
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(
                                    TelegramDatabaseImportContract.EXTRA_STICKER_SETS,
                                    DefaultJson.encodeToString(stickerSets),
                                ),
                            )
                            finish()
                        },
                    )
                }
            }
            return
        }

        if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
            runCatching { HookStatus.init(this) }
        }

        Shell.getShell()
        setContent {
            ModuleAppTheme {
                AppContent(
                    selectFileLauncher,
                    onUrlClick = { url ->
                        url.toUri().openInSystem(this, true)
                    }
                )
            }
        }
    }

    private fun localizedString(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        LocalizedContextFactory.create(
            this,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.ModuleApp,
        ).getString(resourceId, *formatArgs)

    private fun handleOpenLsposedManager() {
        if (!PackageNames.isWeChat(callingPackage.orEmpty())) {
            setResult(
                RESULT_CANCELED,
                Intent().putExtra(
                    ManagerLaunchContract.EXTRA_ERROR,
                    localizedString(R.string.manager_launch_invalid_caller),
                ),
            )
            finish()
            return
        }

        Shell.getShell()
        if (Shell.isAppGrantedRoot() != true) {
            showManagerLaunchError(R.string.manager_launch_root_required)
            return
        }

        setContent {
            ModuleAppTheme {
                ManagerLaunchContent(
                    title = stringResource(R.string.manager_launch_opening_title),
                    message = null,
                    showProgress = true,
                    onClose = ::finish,
                )
            }
        }

        val secretCodeAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "android.telephony.action.SECRET_CODE"
        } else {
            "android.provider.Telephony.SECRET_CODE"
        }
        Shell.cmd(
            "am start -c org.matrix.vector.manager.LAUNCH_MANAGER " +
                "com.android.shell/.BugreportWarningActivity",
        ).submit { vectorResult ->
            Shell.cmd(
                "am broadcast -a $secretCodeAction " +
                    "-d android_secret_code://5776733 android",
            ).submit { lsposedResult ->
                if (vectorResult.isSuccess || lsposedResult.isSuccess) {
                    runOnUiThread {
                        setResult(RESULT_OK)
                        finish()
                    }
                } else {
                    val details = (vectorResult.out + vectorResult.err +
                        lsposedResult.out + lsposedResult.err)
                        .joinToString("\n")
                    WeLogger.e("MainActivity", "manager launch commands failed: $details")
                    runOnUiThread {
                        showManagerLaunchError(R.string.manager_launch_failed_message)
                    }
                }
            }
        }
    }

    private fun showManagerLaunchError(@StringRes message: Int) {
        setContent {
            ModuleAppTheme {
                ManagerLaunchContent(
                    title = stringResource(R.string.manager_launch_failed_title),
                    message = stringResource(message),
                    showProgress = false,
                    onClose = ::finish,
                )
            }
        }
    }

    @Composable
    private fun ManagerLaunchContent(
        title: String,
        message: String?,
        showProgress: Boolean,
        onClose: () -> Unit,
    ) {
        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (showProgress) {
                    CircularProgressIndicator()
                } else {
                    Icon(
                        imageVector = MaterialSymbols.OutlinedFilled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onClose) {
                        Text(stringResource(R.string.dialog_close))
                    }
                }
            }
        }
    }

    private data class ActivationState(
        val isActivated: Boolean,
        val title: String,
        val desc: String,
        val color: Color
    )

    @Composable
    private fun AppContent(resultLauncher: ActivityResultLauncher<String>, onUrlClick: (String) -> Unit) {
        val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        var showMenu by remember { mutableStateOf(false) }
        var showAboutDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteTinkerDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteModuleDataDialog by remember { mutableStateOf(false) }
        var showNoRootDialog by remember { mutableStateOf(false) }
        var showModifyHostPkgNameDialog by remember { mutableStateOf(false) }
        var shortcutError by remember { mutableStateOf<String?>(null) }

        var isLauncherIconEnabled by remember {
            mutableStateOf(
                ComponentName(
                    this,
                    "${PackageNames.MODULE}.activity.MainActivityAlias"
                ).getEnabled(this)
            )
        }

        @Composable
        fun rememberActivationState(): ActivationState {
            val xposedService by HookStatus.xposedService.collectAsState()
            val isHookEnabled = remember(xposedService) {
                xposedService?.scope?.contains(PackageNames.WECHAT) == true
            }
            val title = stringResource(
                if (isHookEnabled) R.string.module_app_activation_active
                else R.string.module_app_activation_inactive,
            )
            val description = if (xposedService == null) {
                stringResource(R.string.module_app_activation_xposed_not_detected)
            } else {
                stringResource(
                    R.string.module_app_activation_framework_details,
                    xposedService!!.frameworkName,
                    xposedService!!.frameworkVersion,
                    xposedService!!.frameworkVersionCode,
                    xposedService!!.apiVersion,
                )
            }
            return ActivationState(
                isActivated = isHookEnabled,
                title = title,
                desc = description,
                color = if (isHookEnabled) Color(0xFF4CAF50) else Color(0xFFF44336),
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = BuildConfig.TAG,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = stringResource(
                                    R.string.module_app_version_summary,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                            .copy(alpha = 0.9f)
                    ),
                    actions = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                MaterialSymbols.OutlinedFilled.More_vert,
                                contentDescription = stringResource(R.string.accessibility_overflow_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.module_app_set_host_package_title)) },
                                onClick = {
                                    showMenu = false
                                    showModifyHostPkgNameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.module_app_repair_loading_title)) },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteTinkerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.module_app_clear_data_title)) },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteModuleDataDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feature_decompile_bean_shell_snapshot_name)) },
                                onClick = {
                                    showMenu = false
                                    resultLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (isLauncherIconEnabled) R.string.module_app_hide_launcher_icon
                                            else R.string.module_app_show_launcher_icon,
                                        ),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val componentName = ComponentName(
                                        this@MainActivity,
                                        "${PackageNames.MODULE}.activity.MainActivityAlias"
                                    )
                                    val newState = !isLauncherIconEnabled
                                    componentName.setEnabled(this@MainActivity, newState)
                                    isLauncherIconEnabled = newState
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.module_app_about_title)) },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(top = 16.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The activation card relies on the libxposed service, which is only
                // bound when the module ships the libxposed entry point (standard flavor).
                if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
                    val activationState = rememberActivationState()
                    Card(
                        colors = CardDefaults.cardColors(containerColor = activationState.color),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (activationState.isActivated) MaterialSymbols.OutlinedFilled.Check_circle else MaterialSymbols.OutlinedFilled.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = activationState.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = activationState.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                MaterialSymbols.OutlinedFilled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                stringResource(R.string.module_app_build_info_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        InfoItem(stringResource(R.string.module_app_build_commit_hash_label), BuildConfig.COMMIT_HASH)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoItem(
                            stringResource(R.string.module_app_build_commit_time_label),
                            formatEpoch(BuildConfig.BUILD_TIMESTAMP, true)
                        )
                    }
                }

                ElevatedCard(
                    onClick = {
                        if (!(Shell.isAppGrantedRoot() ?: false)) {
                            showNoRootDialog = true
                        } else {
                            val userId = androidUserId
                            val hostPkg =
                                prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                            Shell.cmd(
                                "am force-stop --user $userId $hostPkg",
                                "am start --user $userId -n $hostPkg/${PackageNames.WECHAT}.ui.LauncherUI"
                            ).submit { result ->
                                if (result.isSuccess) {
                                    finishAndRemoveTask()
                                } else {
                                    shortcutError = (result.out + result.err)
                                        .joinToString("\n")
                                        .ifBlank {
                                            localizedContext.getString(
                                                R.string.module_app_force_start_wechat_failed,
                                                hostPkg,
                                            )
                                        }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Open_in_new,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.module_app_open_wechat_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.module_app_open_wechat_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                ElevatedCard(
                    onClick = {
                        val hostPkg =
                            prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                        try {
                            startActivity(dev.ujhhgtg.wekit.utils.android.Intent {
                                setClassName(hostPkg, "${PackageNames.WECHAT}.ui.LauncherUI")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(BuildConfig.TAG, "1")
                            })
                            isLaunchingWeChat = true
                        } catch (e: Exception) {
                            shortcutError = e.message
                                ?: localizedContext.getString(R.string.module_app_open_wechat_failed, hostPkg)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.module_app_open_settings_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.module_app_open_settings_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showModifyHostPkgNameDialog) {
                    var pkgName by remember {
                        mutableStateOf(
                            prefs.getString(
                                "host_pkg_name",
                                PackageNames.WECHAT
                            )!!
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showModifyHostPkgNameDialog = false },
                        title = { Text(stringResource(R.string.module_app_set_host_package_title)) },
                        text = {
                            DefaultColumn {
                                Text(stringResource(R.string.module_app_host_package_explanation))

                                TextField(
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.module_app_host_package_label,
                                                PackageNames.WECHAT,
                                            ),
                                        )
                                    },
                                    value = pkgName,
                                    onValueChange = { pkgName = it }
                                )
                            }

                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showModifyHostPkgNameDialog = false
                            }) { Text(stringResource(R.string.dialog_cancel)) }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showModifyHostPkgNameDialog = false
                                prefs.edit {
                                    putString("host_pkg_name", pkgName)
                                }
                            }) { Text(stringResource(R.string.dialog_confirm)) }
                        })
                }

                if (showConfirmDeleteTinkerDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/tinker",
                            "/data/data/${PackageNames.WECHAT}/tinker_server",
                            "/data/data/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/0/${PackageNames.WECHAT}/tinker",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/999/${PackageNames.WECHAT}/tinker",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_temp"
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteTinkerDialog = false },
                        title = { Text(stringResource(R.string.module_app_repair_loading_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.module_app_repair_loading_message,
                                    paths.joinToString("\n") { "- $it" },
                                ),
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteTinkerDialog = false
                            }) { Text(stringResource(R.string.dialog_cancel)) }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteTinkerDialog = false
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    paths.forEach { path ->
                                        ProcessBuilder(
                                            "su", "-mm", "-c",
                                            "if [ -d '$path' ]; then " +
                                                    "find '$path' -mindepth 1 -exec rm -rf {} + ; " +
                                                    "chmod -R 000 '$path' ; " +
                                                    "fi"
                                        )
                                            .redirectErrorStream(true)
                                            .start()
                                    }
                                    showToast(
                                        this@MainActivity,
                                        localizedContext.getString(R.string.module_app_repair_loading_success),
                                    )
                                }
                            }) { Text(stringResource(R.string.dialog_confirm)) }
                        })
                }

                if (showConfirmDeleteModuleDataDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs",
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs.crc",
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteModuleDataDialog = false },
                        title = { Text(stringResource(R.string.module_app_clear_data_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.module_app_clear_data_message,
                                    paths.joinToString("\n") { "- $it" },
                                ),
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                            }) { Text(stringResource(R.string.dialog_cancel)) }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    // if using Shell.cmd or su -c without -mm, the view of /data/user/0 is restricted
                                    paths.forEach { path ->
                                        ProcessBuilder("su", "-mm", "-c", "rm -rf $path")
                                            .redirectErrorStream(true)
                                            .start()
                                    }
                                    showToast(
                                        this@MainActivity,
                                        localizedContext.getString(R.string.module_app_clear_data_success),
                                    )
                                }
                            }) { Text(stringResource(R.string.dialog_confirm)) }
                        })
                }

                if (showNoRootDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoRootDialog = false },
                        title = { Text(stringResource(R.string.module_app_root_required_title)) },
                        text = { Text(stringResource(R.string.module_app_root_required_message)) },
                        confirmButton = {
                            Button(onClick = {
                                showNoRootDialog = false
                            }) { Text(stringResource(R.string.dialog_confirm)) }
                        })
                }

                shortcutError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { shortcutError = null },
                        title = { Text(stringResource(R.string.module_app_operation_failed_title)) },
                        text = { Text(error) },
                        confirmButton = {
                            Button(onClick = {
                                shortcutError = null
                            }) { Text(stringResource(R.string.dialog_confirm)) }
                        })
                }

                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .alpha(0.1f),
                    color = MaterialTheme.colorScheme.onSurface
                )

                LinkCard(
                    icon = GitHubIcon,
                    title = stringResource(R.string.brand_github),
                    subtitle = "Ujhhgtg/WeKit",
                    onClick = { onUrlClick("https://github.com/Ujhhgtg/WeKit") }
                )
                LinkCard(
                    icon = TelegramIcon,
                    title = stringResource(R.string.brand_telegram),
                    subtitle = "https://t.me/+7j5dJ6g16B43OWVl",
                    onClick = { onUrlClick("https://t.me/+7j5dJ6g16B43OWVl") }
                )
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = stringResource(R.string.module_app_about_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.module_app_about_description, BuildConfig.TAG))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.module_app_about_version, BuildConfig.VERSION_NAME))
                            Text(stringResource(R.string.module_app_about_version_code, BuildConfig.VERSION_CODE))
                            Text(stringResource(R.string.module_app_about_authors))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text(stringResource(R.string.dialog_confirm))
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun InfoItem(label: String, value: String) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
