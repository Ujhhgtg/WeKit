package dev.ujhhgtg.wekit.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.getEnabled
import dev.ujhhgtg.wekit.utils.hookstatus.HookStatus
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.setEnabled


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        runCatching { HookStatus.init(this) }

        setContent {
            AppTheme {
                AppContent(
                    onUrlClick = { url ->
                        url.toUri().openInSystem(this, true) }
                )
            }
        }
    }

    @Composable
    fun AppContent(onUrlClick: (String) -> Unit) {
        val context = LocalContext.current
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        // 状态管理
        var showMenu by remember { mutableStateOf(false) }
        // 关于弹窗的状态
        var showAboutDialog by remember { mutableStateOf(false) }

        var isLauncherIconEnabled by remember {
            mutableStateOf(
                ComponentName(
                    context,
                    "${PackageNames.THIS}.activity.MainActivityAlias"
                ).getEnabled(context)
            )
        }

        // 激活状态数据类
        data class ActivationState(
            val isActivated: Boolean,
            val title: String,
            val desc: String,
            val color: Color
        )

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
                                text = { Text(if (isLauncherIconEnabled) "隐藏桌面图标" else "显示桌面图标") },
                                onClick = {
                                    showMenu = false
                                    val componentName = ComponentName(
                                        context,
                                        "${PackageNames.THIS}.activity.MainActivityAlias"
                                    )
                                    val newState = !isLauncherIconEnabled
                                    componentName.setEnabled(context, newState)
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
            // 内容层
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(top = 16.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Activation Status Card
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

                // Build Info Card
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

                var showErrorDialog by remember { mutableStateOf(false) }

                // Open WeChat Card
                ElevatedCard(
                    onClick = {
                        val userId = run {
                            val userManager =
                                context.getSystemService(USER_SERVICE) as UserManager
                            val userHandle = android.os.Process.myUserHandle()
                            userManager.getSerialNumberForUser(userHandle)
                        }
                        Shell.cmd(
                            "am force-stop --user $userId ${PackageNames.WECHAT}",
                            "am start --user $userId -n ${PackageNames.WECHAT}/${PackageNames.WECHAT}.ui.LauncherUI"
                        ).submit { result ->
                            if (!result.isSuccess) {
                                showErrorDialog = true
                            } else {
                                finishAndRemoveTask()
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
                                text = "一键强制停止并启动微信",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Open Module Settings Card
                ElevatedCard(
                    onClick = {
                        startActivity(Intent().apply {
                            setClassName(PackageNames.WECHAT, "${PackageNames.WECHAT}.ui.LauncherUI")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(BuildConfig.TAG, "1")
                        })
                        finishAndRemoveTask()
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

                if (showErrorDialog) {
                    AlertDialog(
                        onDismissRequest = { showErrorDialog = false },
                        title = { Text("未授予 Root 权限") },
                        text = { Text("请授予 Root 权限以一键强制停止并启动微信") },
                        confirmButton = {
                            Button(onClick = {
                                showErrorDialog = false
                            }) { Text("确定") }
                        })
                }

                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .alpha(0.1f),
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Link Cards
                LinkCard(
                    icon = GitHubIcon,
                    title = "GitHub",
                    subtitle = "修改于 Ujhhgtg/WeKit (原始: cwuom/WeKit)",
                    onClick = { onUrlClick("https://github.com/Ujhhgtg/WeKit") }
                )
                LinkCard(
                    icon = TelegramIcon,
                    title = "Telegram",
                    subtitle = "@ujhhgtg_wekit_ci",
                    onClick = { onUrlClick("https://t.me/ujhhgtg_wekit_ci") }
                )
            }

            // 关于弹窗逻辑
            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = "关于") },
                    text = {
                        Column {
                            Text("WeKit 是一款基于 Xposed 框架的开源免费微信模块")
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
    fun InfoItem(label: String, value: String) {
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
    fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
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
