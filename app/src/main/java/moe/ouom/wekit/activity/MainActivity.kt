package moe.ouom.wekit.activity

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.delay
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.R
import moe.ouom.wekit.constants.PackageConstants
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.ui.theme.AppTheme
import moe.ouom.wekit.utils.common.CheckAbiVariantModel
import moe.ouom.wekit.utils.common.Utils
import moe.ouom.wekit.utils.getEnable
import moe.ouom.wekit.utils.hookstatus.AbiUtils
import moe.ouom.wekit.utils.hookstatus.HookStatus
import moe.ouom.wekit.utils.setEnable

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化 HookStatus
        try {
            HookStatus.init(this)
        } catch (_: Exception) {
        }

        setContent {
            AppTheme {
                AppContent(
                    onUrlClick = { url -> Utils.jumpUrl(this, url) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
                "moe.ouom.wekit.activity.MainActivityAlias"
            ).getEnable(context)
        )
    }

    // 激活状态数据类
    data class ActivationState(
        val isActivated: Boolean,
        val isAbiMatch: Boolean,
        val title: String,
        val desc: String,
        val color: Color
    )

    fun getActivationState(): ActivationState {
        val mHostAppPackages = setOf(PackageConstants.PACKAGE_NAME_WECHAT)
        val isHookEnabledByLegacyApi = HookStatus.isModuleEnabled || HostInfo.isInHostProcess()
        val xposedService: XposedService? = HookStatus.xposedService.value
        val isHookEnabledByLibXposedApi = if (xposedService != null) {
            mHostAppPackages.intersect(xposedService.scope.toSet()).isNotEmpty()
        } else false
        val isHookEnabled = isHookEnabledByLegacyApi || isHookEnabledByLibXposedApi

        var isAbiMatch = try {
            CheckAbiVariantModel.collectAbiInfo(context).isAbiMatch
        } catch (e: Exception) {
            true
        }

        if ((isHookEnabled && HostInfo.isInModuleProcess() && !HookStatus.isZygoteHookMode
            && HookStatus.isTaiChiInstalled(context))
            && HookStatus.hookType == HookStatus.HookType.APP_PATCH
            && "armAll" != AbiUtils.moduleFlavorName
        ) {
            isAbiMatch = false
        }

        return if (isAbiMatch) {
            ActivationState(
                isActivated = isHookEnabled,
                isAbiMatch = true,
                title = if (isHookEnabled) "已激活" else "未激活",
                desc = if (HostInfo.isInHostProcess()) HostInfo.getPackageName() else (if (isHookEnabledByLibXposedApi) "${xposedService?.frameworkName} ${xposedService?.frameworkVersion} (${xposedService?.frameworkVersionCode}), API ${xposedService?.apiVersion}" else HookStatus.hookProviderNameForLegacyApi),
                color = if (isHookEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        } else {
            ActivationState(
                isActivated = isHookEnabled,
                isAbiMatch = false,
                title = if (isHookEnabled) "未完全激活" else "未激活",
                desc = "原生库不完全匹配",
                color = Color(0xFFF44336)
            )
        }
    }

    var activationState by remember { mutableStateOf(getActivationState()) }

    // 模拟 onResume 和定时刷新
    LaunchedEffect(Unit) {
        while (true) {
            activationState = getActivationState()
            delay(3000)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "WeKit",
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
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
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
                                    "moe.ouom.wekit.activity.MainActivityAlias"
                                )
                                val newState = !isLauncherIconEnabled
                                componentName.setEnable(context, newState)
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

        Box(modifier = Modifier.fillMaxSize()) {
            // 内容层
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 24.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            imageVector = if (activationState.isActivated && activationState.isAbiMatch) Icons.Default.CheckCircle else Icons.Default.Warning,
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
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("构建信息", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        InfoItem("构建 UUID", BuildConfig.BUILD_UUID)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoItem(
                            "构建日期",
                            Utils.convertTimestampToDate(BuildConfig.BUILD_TIMESTAMP)
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .alpha(0.1f),
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Link Cards
                LinkCard(
                    iconRes = R.drawable.ic_telegram,
                    title = "Telegram",
                    subtitle = "@ouom_pub",
                    onClick = { onUrlClick("https://t.me/ouom_pub") }
                )

                LinkCard(
                    iconRes = R.drawable.ic_github,
                    title = "GitHub",
                    subtitle = "修改于 Ujhhgtg/WeKit (原始: cwuom/WeKit)",
                    onClick = { onUrlClick("https://github.com/Ujhhgtg/WeKit") }
                )

                Spacer(modifier = Modifier.height(30.dp))
            }

            // 关于弹窗逻辑
            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = "关于 WeKit") },
                    text = {
                        Column {
                            Text("WeKit 是一款基于 Xposed 框架的免费开源微信模块")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("版本: ${BuildConfig.VERSION_NAME}")
                            Text("构建版本: ${BuildConfig.VERSION_CODE}")
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
fun LinkCard(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
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