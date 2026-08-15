package dev.ujhhgtg.wekit.activity.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
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
fun HomePager() {
    M3ListScaffold(title = stringResource(R.string.app_name)) {
        item {
            Column(
                modifier = Modifier.padding(top = 12.dp),
            ) {
                StatusCard()
                DeviceInformation()
                LearnMore()
                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard() {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val statusTitle = stringResource(R.string.home_module_activated)
    val loaderName = StartupInfo.loaderService.loaderName
    val hookBridgeName = StartupInfo.hookBridge?.hookBridgeName
        ?: stringResource(R.string.common_not_provided)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        onClick = { openLsposedManager(context) },
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Check_circle,
                    contentDescription = statusTitle,
                )
            },
            supportingContent = {
                Text(
                    text = "${BuildConfig.VERSION_NAME} · $loaderName",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                StatusTag(
                    label = hookBridgeName,
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = contentColor,
                leadingContentColor = contentColor,
                trailingContentColor = contentColor,
                supportingContentColor = contentColor.copy(alpha = 0.7f),
            ),
            content = {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusTag(label: String, backgroundColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier.background(
            color = backgroundColor,
            shape = RoundedCornerShape(4.dp),
        )
    ) {
        Text(
            text = label,
            modifier = Modifier
                .widthIn(max = 96.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceInformation() {
    val loaderName = StartupInfo.loaderService.loaderName
    SegmentedColumn(title = stringResource(R.string.home_device_info_title)) {
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_wechat_version),
                description = stringResource(R.string.home_version_value, HostInfo.versionName, HostInfo.versionCode),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_module_version),
                description = stringResource(
                    R.string.home_version_value,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_build_time),
                description = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_device_model),
                description = stringResource(R.string.home_device_model_value, Build.MANUFACTURER, Build.MODEL),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_android_version),
                description = stringResource(
                    R.string.home_android_version_value,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT,
                ),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_loading_environment),
                description = stringResource(
                    R.string.home_loading_environment_value,
                    loaderName,
                    StartupInfo.hookBridge?.hookBridgeName ?: stringResource(R.string.common_not_provided),
                ),
            )
        }
    }
}

@Composable
private fun LearnMore() {
    val uriHandler = LocalUriHandler.current
    SegmentedColumn(title = stringResource(R.string.home_learn_more_title)) {
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_learn_more_item_title),
                description = stringResource(R.string.home_learn_more_item_summary),
                onClick = { uriHandler.openUri("https://ujhhgtgteams.gitbook.io/wekit-docs") },
            )
        }
    }
}
