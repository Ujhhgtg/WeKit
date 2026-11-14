package dev.ujhhgtg.wekit.bootstrap.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Build
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Code
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Info
import com.composables.icons.materialsymbols.outlinedfilled.More_vert
import com.composables.icons.materialsymbols.outlinedfilled.Warning
import com.topjohnwu.superuser.Shell
import dev.ujhhgtg.wekit.bootstrap.BuildConfig
import dev.ujhhgtg.wekit.bootstrap.R
import dev.ujhhgtg.wekit.bootstrap.constants.PackageNames
import dev.ujhhgtg.wekit.bootstrap.ui.content.Button
import dev.ujhhgtg.wekit.bootstrap.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.bootstrap.ui.content.IconButton
import dev.ujhhgtg.wekit.bootstrap.ui.content.TextButton
import dev.ujhhgtg.wekit.bootstrap.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.bootstrap.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.bootstrap.ui.content.m3.SettingsScaffold
import dev.ujhhgtg.wekit.bootstrap.ui.utils.theme.ModuleAppTheme
import dev.ujhhgtg.wekit.bootstrap.utils.WeLogger
import dev.ujhhgtg.wekit.bootstrap.utils.android.androidUserId
import dev.ujhhgtg.wekit.bootstrap.utils.android.getEnabled
import dev.ujhhgtg.wekit.bootstrap.utils.android.setEnabled
import dev.ujhhgtg.wekit.bootstrap.utils.android.showToast
import dev.ujhhgtg.wekit.bootstrap.utils.formatEpoch
import dev.ujhhgtg.wekit.bootstrap.utils.hook_status.HookStatus
import dev.ujhhgtg.wekit.bootstrap.utils.openInSystem
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("activity.MainActivity", MODE_PRIVATE) }

    private var isLaunchingWeChat = false

    override fun onStop() {
        super.onStop()
        if (isLaunchingWeChat) {
            isLaunchingWeChat = false
            finishAndRemoveTask()
        }
    }

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
                    Intent()
                        .putExtra(
                            TelegramDatabaseImportContract.EXTRA_ERROR,
                            getString(R.string.module_app_sticker_import_invalid_caller),
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
                                Intent()
                                    .putExtra(
                                        TelegramDatabaseImportContract.EXTRA_STICKER_SETS,
                                        Json.encodeToString(stickerSets),
                                    ),
                            )
                            finish()
                        },
                    )
                }
            }
            return
        }

        runCatching { HookStatus.init(this) }

        Shell.getShell()
        setContent {
            ModuleAppTheme {
                AppContent(
                    onUrlClick = { url ->
                        url.toUri().openInSystem(this, true)
                    },
                )
            }
        }
    }

    private fun handleOpenLsposedManager() {
        if (!PackageNames.isWeChat(callingPackage.orEmpty())) {
            setResult(
                RESULT_CANCELED,
                Intent()
                    .putExtra(
                        ManagerLaunchContract.EXTRA_ERROR,
                        getString(R.string.manager_launch_invalid_caller),
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

        val secretCodeAction = "android.telephony.action.SECRET_CODE"
        Shell.cmd(
                "am start -c org.matrix.vector.manager.LAUNCH_MANAGER " +
                    "com.android.shell/.BugreportWarningActivity",
            )
            .submit { vectorResult ->
                Shell.cmd(
                        "am broadcast -a $secretCodeAction " +
                            "-d android_secret_code://5776733 android",
                    )
                    .submit { lsposedResult ->
                        if (vectorResult.isSuccess || lsposedResult.isSuccess) {
                            runOnUiThread {
                                setResult(RESULT_OK)
                                finish()
                            }
                        } else {
                            val details =
                                (vectorResult.out +
                                        vectorResult.err +
                                        lsposedResult.out +
                                        lsposedResult.err)
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
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
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
    )

    @Composable
    private fun AppContent(onUrlClick: (String) -> Unit) {
        val context by rememberUpdatedState(LocalContext.current)

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
                        "${PackageNames.MODULE}.activity.MainActivityAlias",
                    )
                    .getEnabled(this),
            )
        }

        @Composable
        fun rememberActivationState(): ActivationState {
            val xposedService by HookStatus.xposedService.collectAsState()
            val isHookEnabled =
                remember(xposedService) {
                    xposedService?.scope?.contains(PackageNames.WECHAT) == true
                }
            val title =
                stringResource(
                    if (isHookEnabled) R.string.module_app_activation_active
                    else R.string.module_app_activation_inactive,
                )
            val description =
                if (xposedService == null) {
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
            )
        }

        val activationState = rememberActivationState()
        SettingsScaffold(
            title = BuildConfig.TAG,
            actions = {
                Box {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(
                            MaterialSymbols.OutlinedFilled.More_vert,
                            contentDescription =
                                stringResource(R.string.accessibility_overflow_menu),
                        )
                    }
                    DropdownMenuPopup(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(0, 5).shape,
                                leadingIcon = {
                                    Icon(MaterialSymbols.Outlined.Tune, contentDescription = null)
                                },
                                text = {
                                    Text(stringResource(R.string.module_app_set_host_package_title))
                                },
                                onClick = {
                                    showMenu = false
                                    showModifyHostPkgNameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(1, 5).shape,
                                leadingIcon = {
                                    Icon(
                                        MaterialSymbols.Outlined.Build,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(R.string.module_app_repair_loading_title))
                                },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteTinkerDialog = true
                                },
                            )
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(2, 5).shape,
                                leadingIcon = {
                                    Icon(MaterialSymbols.Outlined.Delete, contentDescription = null)
                                },
                                text = {
                                    Text(stringResource(R.string.module_app_clear_data_title))
                                },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteModuleDataDialog = true
                                },
                            )
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(3, 5).shape,
                                leadingIcon = {
                                    Icon(
                                        if (isLauncherIconEnabled)
                                            MaterialSymbols.Outlined.Visibility_off
                                        else MaterialSymbols.Outlined.Visibility,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(
                                        stringResource(
                                            if (isLauncherIconEnabled)
                                                R.string.module_app_hide_launcher_icon
                                            else R.string.module_app_show_launcher_icon,
                                        ),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val componentName =
                                        ComponentName(
                                            this@MainActivity,
                                            "${PackageNames.MODULE}.activity.MainActivityAlias",
                                        )
                                    val newState = !isLauncherIconEnabled
                                    componentName.setEnabled(this@MainActivity, newState)
                                    isLauncherIconEnabled = newState
                                },
                            )
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(4, 5).shape,
                                leadingIcon = {
                                    Icon(
                                        MaterialSymbols.OutlinedFilled.Info,
                                        contentDescription = null,
                                    )
                                },
                                text = { Text(stringResource(R.string.module_app_about_title)) },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                },
                            )
                        }
                    }
                }
            },
        ) {
            item {
                val containerColor =
                    if (activationState.isActivated) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                val contentColor =
                    if (activationState.isActivated) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                Surface(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp, bottom = 8.dp),
                    color = containerColor,
                    contentColor = contentColor,
                    shape = MaterialTheme.shapes.large,
                ) {
                    BaseWidget(
                        icon =
                            if (activationState.isActivated)
                                MaterialSymbols.OutlinedFilled.Check_circle
                            else MaterialSymbols.OutlinedFilled.Warning,
                        title = activationState.title,
                        titleStyle = MaterialTheme.typography.titleMediumEmphasized,
                        description =
                            stringResource(
                                R.string.module_app_version_summary,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE,
                            ) + "\n" + activationState.desc,
                        colors =
                            ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = contentColor,
                                leadingContentColor = contentColor,
                                supportingContentColor = contentColor.copy(alpha = 0.7f),
                            ),
                    )
                }
            }
            item {
                SegmentedColumn {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Code,
                            title = stringResource(R.string.module_app_build_commit_hash_label),
                            description = BuildConfig.COMMIT_HASH,
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Schedule,
                            title = stringResource(R.string.module_app_build_commit_time_label),
                            description = formatEpoch(BuildConfig.COMMIT_TIMESTAMP, true),
                        )
                    }
                }
            }
            item {
                SegmentedColumn {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Open_in_new,
                            title = stringResource(R.string.module_app_open_wechat_title),
                            description = stringResource(R.string.module_app_open_wechat_summary),
                            trailingContent = { NavigationArrow() },
                            onClick = {
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    val userId = androidUserId
                                    val hostPkg =
                                        prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                                    Shell.cmd(
                                            "am force-stop --user $userId $hostPkg",
                                            "am start --user $userId -n $hostPkg/${PackageNames.WECHAT}.ui.LauncherUI",
                                        )
                                        .submit { result ->
                                            if (result.isSuccess) {
                                                finishAndRemoveTask()
                                            } else {
                                                shortcutError =
                                                    (result.out + result.err)
                                                        .joinToString("\n")
                                                        .ifBlank {
                                                            context.getString(
                                                                R.string
                                                                    .module_app_force_start_wechat_failed,
                                                                hostPkg,
                                                            )
                                                        }
                                            }
                                        }
                                }
                            },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Settings,
                            title = stringResource(R.string.module_app_open_settings_title),
                            description = stringResource(R.string.module_app_open_settings_summary),
                            trailingContent = { NavigationArrow() },
                            onClick = {
                                val hostPkg =
                                    prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                                try {
                                    startActivity(
                                        Intent().apply {
                                            setClassName(
                                                hostPkg,
                                                "${PackageNames.WECHAT}.ui.LauncherUI",
                                            )
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            putExtra(BuildConfig.TAG, "1")
                                        },
                                    )
                                    isLaunchingWeChat = true
                                } catch (e: Exception) {
                                    shortcutError =
                                        e.message
                                            ?: context.getString(
                                                R.string.module_app_open_wechat_failed,
                                                hostPkg,
                                            )
                                }
                            },
                        )
                    }
                }
            }
            item {
                SegmentedColumn {
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Code,
                            title = stringResource(R.string.brand_github),
                            description = "Ujhhgtg/WeKit",
                            trailingContent = { ExternalLinkIcon() },
                            onClick = { onUrlClick("https://github.com/Ujhhgtg/WeKit") },
                        )
                    }
                    item {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Send,
                            title = stringResource(R.string.brand_telegram),
                            description = "https://t.me/+7j5dJ6g16B43OWVl",
                            trailingContent = { ExternalLinkIcon() },
                            onClick = { onUrlClick("https://t.me/+7j5dJ6g16B43OWVl") },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showModifyHostPkgNameDialog) {
            var pkgName by remember {
                mutableStateOf(
                    prefs.getString(
                        "host_pkg_name",
                        PackageNames.WECHAT,
                    )!!,
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
                            onValueChange = { pkgName = it },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showModifyHostPkgNameDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showModifyHostPkgNameDialog = false
                            prefs.edit {
                                putString("host_pkg_name", pkgName)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
            )
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
                    "/data/user/999/${PackageNames.WECHAT}/tinker_temp",
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
                    TextButton(
                        onClick = {
                            showConfirmDeleteTinkerDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirmDeleteTinkerDialog = false
                            if (!(Shell.isAppGrantedRoot() ?: false)) {
                                showNoRootDialog = true
                            } else {
                                paths.forEach { path ->
                                    ProcessBuilder(
                                            "su",
                                            "-mm",
                                            "-c",
                                            "if [ -d '$path' ]; then " +
                                                "find '$path' -mindepth 1 -exec rm -rf {} + ; " +
                                                "chmod -R 000 '$path' ; " +
                                                "fi",
                                        )
                                        .redirectErrorStream(true)
                                        .start()
                                }
                                showToast(
                                    this@MainActivity,
                                    context.getString(R.string.module_app_repair_loading_success),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
            )
        }

        if (showConfirmDeleteModuleDataDialog) {
            val paths = remember {
                @Suppress("SdCardPath") listOf("/data/data/${PackageNames.WECHAT}/files/wekit")
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
                    TextButton(
                        onClick = {
                            showConfirmDeleteModuleDataDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirmDeleteModuleDataDialog = false
                            if (!(Shell.isAppGrantedRoot() ?: false)) {
                                showNoRootDialog = true
                            } else {
                                // if using Shell.cmd or su -c without -mm, the view of /data/user/0
                                // is restricted
                                paths.forEach { path ->
                                    ProcessBuilder("su", "-mm", "-c", "rm -rf $path")
                                        .redirectErrorStream(true)
                                        .start()
                                }
                                showToast(
                                    this@MainActivity,
                                    context.getString(R.string.module_app_clear_data_success),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
            )
        }

        if (showNoRootDialog) {
            AlertDialog(
                onDismissRequest = { showNoRootDialog = false },
                title = { Text(stringResource(R.string.module_app_root_required_title)) },
                text = { Text(stringResource(R.string.module_app_root_required_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNoRootDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
            )
        }

        shortcutError?.let { error ->
            AlertDialog(
                onDismissRequest = { shortcutError = null },
                title = { Text(stringResource(R.string.module_app_operation_failed_title)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            shortcutError = null
                        },
                    ) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
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
                        Text(
                            stringResource(
                                R.string.module_app_about_version,
                                BuildConfig.VERSION_NAME,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.module_app_about_version_code,
                                BuildConfig.VERSION_CODE,
                            ),
                        )
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

    @Composable
    private fun NavigationArrow() {
        Icon(MaterialSymbols.Outlined.Chevron_right, contentDescription = null)
    }

    @Composable
    private fun ExternalLinkIcon() {
        Icon(MaterialSymbols.Outlined.Open_in_new, contentDescription = null)
    }
}
