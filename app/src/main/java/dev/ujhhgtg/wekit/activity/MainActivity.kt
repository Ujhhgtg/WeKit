package dev.ujhhgtg.wekit.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.registerBshSnapshotDecompileLaunchers
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.android.androidUserId
import dev.ujhhgtg.wekit.utils.android.getEnabled
import dev.ujhhgtg.wekit.utils.android.setEnabled
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.hook_status.HookStatus
import dev.ujhhgtg.wekit.utils.openInSystem


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

        runCatching { HookStatus.init(this) }
        Shell.getShell()

        setContent {
            AppTheme {
                AppContent(
                    selectFileLauncher,
                    onUrlClick = { url ->
                        url.toUri().openInSystem(this, true)
                    }
                )
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

        val isHookEnabledByLegacyApi = remember { HookStatus.isModuleEnabled || HostInfo.isHost }

        @Composable
        fun rememberActivationState(): ActivationState {
            val hostAppPackages = remember { setOf(PackageNames.WECHAT) }
            val xposedService by HookStatus.xposedService.collectAsState()
            val isHookEnabledByLibXposedApi = remember(xposedService) {
                xposedService?.let {
                    hostAppPackages.intersect(it.scope.toSet()).isNotEmpty()
                } ?: false
            }

            val isHookEnabled = isHookEnabledByLegacyApi || isHookEnabledByLibXposedApi

            return remember(
                isHookEnabled,
                isHookEnabledByLibXposedApi,
                xposedService
            ) {
                ActivationState(
                    isActivated = isHookEnabled,
                    title = if (isHookEnabled) "已激活" else "未激活",
                    desc = if (HostInfo.isHost) {
                        HostInfo.packageName
                    } else {
                        if (isHookEnabledByLibXposedApi) {
                            "${xposedService?.frameworkName} ${xposedService?.frameworkVersion} " +
                                    "(${xposedService?.frameworkVersionCode}), API ${xposedService?.apiVersion}"
                        } else {
                            HookStatus.hookProviderNameForLegacyApi
                        }
                    },
                    color = if (isHookEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }

        val activationState = rememberActivationState()

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
                                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
                            Icon(MaterialSymbols.OutlinedFilled.More_vert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("设置宿主包名") },
                                onClick = {
                                    showMenu = false
                                    showModifyHostPkgNameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("修复模块加载") },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteTinkerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清除模块数据") },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteModuleDataDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("反编译 BeanShell 快照") },
                                onClick = {
                                    showMenu = false
                                    resultLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isLauncherIconEnabled) "隐藏桌面图标" else "显示桌面图标") },
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
                                text = { Text("关于") },
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
                            Text("构建信息", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        InfoItem("构建 Git 哈希", BuildConfig.GIT_HASH)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoItem(
                            "构建时间",
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
                                        .ifBlank { "无法启动微信 (包名: $hostPkg)" }
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
                                text = "打开微信",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "一键强行停止并启动微信",
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
                            startActivity(Intent {
                                setClassName(hostPkg, "${PackageNames.WECHAT}.ui.LauncherUI")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(BuildConfig.TAG, "1")
                            })
                            isLaunchingWeChat = true
                        } catch (e: Exception) {
                            shortcutError = e.message
                                ?: "无法打开微信 (包名: $hostPkg)"
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
                                text = "打开模块设置",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "打开微信内的模块设置",
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
                            startActivity(Intent {
                                setClassName(hostPkg, "${PackageNames.WECHAT}.ui.LauncherUI")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(BuildConfig.TAG, "2")
                            })
                            isLaunchingWeChat = true
                        } catch (e: Exception) {
                            shortcutError = e.message
                                ?: "无法打开微信 (包名: $hostPkg)"
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
                                text = "打开模块设置 (强制非全屏 UI)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "如果一打开模块设置就闪退那就用这个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showModifyHostPkgNameDialog) {
                    var pkgName by remember { mutableStateOf(prefs.getString("host_pkg_name", PackageNames.WECHAT)!!) }

                    AlertDialog(
                        onDismissRequest = { showModifyHostPkgNameDialog = false },
                        title = { Text("设置宿主包名") },
                        text = {
                            DefaultColumn {
                                Text("本选项控制模块应用首页三个快捷方式打开的目标微信包名, 适用于通过修改包名而非多用户实现多微信共存的用户")

                                TextField(
                                    label = { Text("宿主包名 (默认为 com.tencent.mm)") },
                                    value = pkgName,
                                    onValueChange = { pkgName = it }
                                )
                            }

                        },
                        dismissButton = {
                            TextButton(onClick = { showModifyHostPkgNameDialog = false }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showModifyHostPkgNameDialog = false
                                prefs.edit {
                                    putString("host_pkg_name", pkgName)
                                }
                            }) { Text("确定") }
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
                        title = { Text("修复模块加载") },
                        text = {
                            Text(
                                "本操作将尝试修复微信热更新导致的模块不加载, 执行后请重启微信\n" +
                                        "将清空以下路径的内容, 并将其权限递归设置为 000 以阻止微信重读写, 请确认无误后再继续!\n${
                                            paths.joinToString("\n") { "- $it" }
                                        }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDeleteTinkerDialog = false }) { Text("取消") }
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
                                    showToast(this@MainActivity, "操作成功!")
                                }
                            }) { Text("确定") }
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
                        title = { Text("清除模块数据") },
                        text = {
                            Text(
                                "本操作将删除模块的功能配置, 用于解决部分功能导致微信无限闪退, 删除后请重启微信\n此操作*不会*重新执行 DEX 解析\n将删除以下路径的文件, 请确认无误后再删除!\n${
                                    paths.joinToString("\n") { "- $it" }
                                }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDeleteModuleDataDialog = false }) { Text("取消") }
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
                                    showToast(this@MainActivity, "删除成功!")
                                }
                            }) { Text("确定") }
                        })
                }

                if (showNoRootDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoRootDialog = false },
                        title = { Text("未授予 Root 权限") },
                        text = { Text("请授予 Root 权限以执行此操作") },
                        confirmButton = {
                            Button(onClick = {
                                showNoRootDialog = false
                            }) { Text("确定") }
                        })
                }

                shortcutError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { shortcutError = null },
                        title = { Text("操作失败") },
                        text = { Text(error) },
                        confirmButton = {
                            Button(onClick = {
                                shortcutError = null
                            }) { Text("确定") }
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
                    title = "GitHub",
                    subtitle = "Ujhhgtg/WeKit",
                    onClick = { onUrlClick("https://github.com/Ujhhgtg/WeKit") }
                )
                LinkCard(
                    icon = TelegramIcon,
                    title = "Telegram",
                    subtitle = "Telegram 超级群组",
                    onClick = { onUrlClick("https://t.me/+4XsfR-SWAtk1NGRl") }
                )
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = "关于") },
                    text = {
                        Column {
                            Text("${BuildConfig.TAG} 是一款基于 Xposed 框架的开源免费微信模块")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("版本: ${BuildConfig.VERSION_NAME}")
                            Text("版本号: ${BuildConfig.VERSION_CODE}")
                            Text("作者：Ujhhgtg@github, cwuom@github")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("确定")
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
