package dev.ujhhgtg.wekit.features.items.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Language
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.features.api.ui.WeStartActivityApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.openInSystem

@Feature(
    name = "链接跳转系统打开方式",
    categories = ["系统与隐私"],
    description = "打开链接或卡片链接时显示对话框, 可直接使用系统打开方式打开\n若要跳转到第三方应用, 需先在对应应用设置中启用「在此应用中打开支持的网页链接」"
)
object LinkExternalAppJump : SwitchFeature(),
    WeStartActivityApi.IStartActivityListener {

    private const val TAG = "LinkExternalAppJump"

    private val WECHAT_INTERNAL_HOSTS = setOf(
        "weixin.com",
        "qq.com",
        "weixin.qq.com.cn",
        "wechatpay.cn",
        "tenpay.com",
        "weixinbridge.com",
        "kf.qq.com",
        "pay.wechatpay.cn"
    )

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(
        param: HookParam,
        intent: Intent
    ) {
        // prevent loop
        if (intent.getBooleanExtra("skip_link_hook", false)) return

        val context = if (intent.extras?.run {
                containsKey("key_scan_qr_code_get_a8key_resp") ||
                        containsKey("key_scan_qr_code_get_a8key_req")
            } ?: false) {
            LauncherUI.getInstance()!!
        } else {
            param.thisObject as Context
        }

        val component = intent.component ?: return
        val shortClassName = component.shortClassName ?: return
        if (!shortClassName.contains("WebViewUI")) return

        val rawUrl = intent.getStringExtra("rawUrl") ?: return
        if (!rawUrl.startsWith("http")) return
        val url = rawUrl.toUri()
        if (WECHAT_INTERNAL_HOSTS.contains(url.host)) return

        val newIntent = Intent(Intent.ACTION_VIEW)
        newIntent.addCategory(Intent.CATEGORY_BROWSABLE)
        newIntent.data = url

        val pm = context.packageManager

        @SuppressLint("QueryPermissionsNeeded")
        val resolveInfos = pm.queryIntentActivities(
            newIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("选择打开方式") },
                text = {
                    LazyColumn {
                        items(resolveInfos) { info ->
                            AppItemRow(info, pm) {
                                launchApp(context, info, url)
                                onDismiss()
                            }
                        }

                        item {
                            CustomTabsRow {
                                url.openInSystem(context, true)
                                onDismiss()
                            }
                        }

                        if (!resolveInfos.isEmpty())
                            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

                        item {
                            InternalWebViewRow {
                                try {
                                    intent.putExtra("skip_link_hook", true)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    WeLogger.e(TAG, "failed to open internal webview", e)
                                }
                                onDismiss()
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        copyToClipboard(context, url.toString())
                        showToast(context, "已复制链接")
                        onDismiss()
                    }) { Text("复制链接") }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
        }

        param.result = null
    }

    @Composable
    private fun CustomTabsRow(onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Open_in_new,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(text = "系统默认 (Custom Tabs)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "使用系统默认浏览器的 Custom Tabs 模式打开",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }

    @Composable
    private fun InternalWebViewRow(onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Language,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(text = "微信", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "不改变打开方式, 仍使用微信内置 WebView 打开",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }

    @Composable
    private fun AppItemRow(info: ResolveInfo, pm: PackageManager, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = remember(info) { info.loadIcon(pm).toBitmap().asImageBitmap() }
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = info.loadLabel(pm).toString(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = info.activityInfo.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }

    private fun launchApp(context: Context, info: ResolveInfo, url: Uri) {
        val finalIntent = Intent(Intent.ACTION_VIEW, url).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setClassName(info.activityInfo.packageName, info.activityInfo.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(finalIntent)
    }
}
