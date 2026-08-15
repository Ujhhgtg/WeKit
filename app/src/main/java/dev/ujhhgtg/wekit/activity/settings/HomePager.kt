package dev.ujhhgtg.wekit.activity.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.formatEpoch


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
        FeaturesProvider.ALL_HOOK_ITEMS.count { WePrefs.getBoolOrFalse(it.technicalId) }
    }
    val totalCount = remember { FeaturesProvider.ALL_HOOK_ITEMS.size }

    M3ListScaffold(title = stringResource(R.string.app_name)) {
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
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
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        contentDescription = null,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_module_activated),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                label = stringResource(R.string.home_enabled_features), value = enabledCount.toString(),
                onClick = onOpenFeatures,
            )
            Spacer(Modifier.height(12.dp))
            CountCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = stringResource(R.string.home_all_features), value = totalCount.toString(),
                onClick = onOpenFeatures,
            )
        }
    }
}

@Composable
private fun CountCard(modifier: Modifier, label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Composable
private fun SystemInfoCard() {
    SegmentedColumn {
        item {
            BaseItemContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    InfoText(
                        stringResource(R.string.home_wechat_version),
                        stringResource(R.string.home_version_value, HostInfo.versionName, HostInfo.versionCode),
                    )
                    InfoText(
                        stringResource(R.string.home_module_version),
                        stringResource(R.string.home_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    )
                    InfoText(stringResource(R.string.home_build_time), formatEpoch(BuildConfig.BUILD_TIMESTAMP, true))
                    InfoText(
                        stringResource(R.string.home_device_model),
                        stringResource(R.string.home_device_model_value, Build.MANUFACTURER, Build.MODEL),
                    )
                    InfoText(
                        stringResource(R.string.home_android_version),
                        stringResource(R.string.home_android_version_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                    )
                    InfoText(
                        title = stringResource(R.string.home_loading_environment),
                        content = stringResource(
                            R.string.home_loading_environment_value,
                            StartupInfo.loaderService.loaderName,
                            StartupInfo.hookBridge?.hookBridgeName ?: stringResource(R.string.common_not_provided),
                        ),
                        bottomPadding = 0.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = content,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}
