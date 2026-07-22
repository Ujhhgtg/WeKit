package dev.ujhhgtg.wekit.activity.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.formatEpoch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType


// ---------------------------------------------------------------------------
//  Page 0 — Home
// ---------------------------------------------------------------------------

/**
 * Opens the LSPosed manager from within a hooked process, replicating the two-pronged shell
 * routine LSPosed itself documents:
 *  1. Start `com.android.shell/.BugreportWarningActivity` with the manager's
 *     `LAUNCH_MANAGER` category — LSPosed's hook on the shell app intercepts this and swaps in
 *     the manager UI.
 *  2. Broadcast the `*#*#5776733#*#*` SECRET_CODE (action differs on API >= 29) as a fallback
 *     for setups where the activity trick is unavailable.
 */
private fun openLsposedManager(context: Context) {
    val managerPackage = "org.lsposed.manager"
    val injectedPackage = "com.android.shell"

    runCatching {
        context.startActivity(
            Intent {
                component = ComponentName(injectedPackage, "$injectedPackage.BugreportWarningActivity")
                addCategory("$managerPackage.LAUNCH_MANAGER")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure { WeLogger.e("SettingsActivity", "failed to launch LSPosed manager activity", it) }

    runCatching {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "android.telephony.action.SECRET_CODE"
        } else {
            "android.provider.Telephony.SECRET_CODE"
        }
        context.sendBroadcast(
            Intent(action, "android_secret_code://5776733".toUri()).setPackage("android")
        )
    }.onFailure { WeLogger.e("SettingsActivity", "failed to broadcast LSPosed secret code", it) }
}

@Composable
fun HomePager(onOpenFeatures: () -> Unit) {
    val enabledCount = remember {
        FeaturesProvider.ALL_HOOK_ITEMS.count { WePrefs.getBoolOrFalse(it.name) }
    }
    val totalCount = remember { FeaturesProvider.ALL_HOOK_ITEMS.size }

    MiuixListScaffold(title = "WeKit") {
        item {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusRow(
                    enabledCount = enabledCount,
                    totalCount = totalCount,
                    onOpenFeatures = onOpenFeatures
                )
                SystemInfoCard()
                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
        }
    }
}

@Composable
private fun StatusRow(enabledCount: Int, totalCount: Int, onOpenFeatures: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: activation status. No detection — seeing this screen means the module is active.
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.defaultColors(
                color = when {
                    MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                    isDark -> Color(0xFF1A3825)
                    else -> Color(0xFFDFFAE4)
                }
            ),
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
            onClick = { openLsposedManager(context) },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(38.dp, 45.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = MaterialSymbols.Outlined.Check_circle,
                        tint = if (MiuixTheme.isDynamicColor) {
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        } else {
                            Color(0xFF36D167)
                        },
                        contentDescription = null,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(text = "模块已激活", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(text = BuildConfig.VERSION_NAME, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // Right: enabled / total feature counts.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            CountCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "已启用功能", value = enabledCount.toString(),
                onClick = onOpenFeatures,
            )
            Spacer(Modifier.height(12.dp))
            CountCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "全部功能", value = totalCount.toString(),
                onClick = onOpenFeatures,
            )
        }
    }
}

@Composable
private fun CountCard(modifier: Modifier, label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(
                text = label,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SystemInfoCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            InfoText("微信版本", "${HostInfo.versionName} (${HostInfo.versionCode})")
            InfoText("模块版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoText("构建时间", formatEpoch(BuildConfig.BUILD_TIMESTAMP, true))
            InfoText("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoText("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoText(
                title = "加载环境",
                content = buildString {
                    append("当前加载器：${StartupInfo.loaderService.loaderName}")
                    append("\n当前 Hook 桥接：")
                    append(StartupInfo.hookBridge?.hookBridgeName ?: "未提供")
                },
                bottomPadding = 0.dp,
            )
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = content,
        fontSize = 14.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}
