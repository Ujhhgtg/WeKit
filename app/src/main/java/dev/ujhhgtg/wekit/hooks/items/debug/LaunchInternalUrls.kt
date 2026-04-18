package dev.ujhhgtg.wekit.hooks.items.debug

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeUnsafeApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "调试/启动微信内部 URL", description = "跳转微信 weixin:// URL")
object LaunchInternalUrls : ClickableHookItem(), IResolvesDex {

    override val noSwitchWidget = true

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var url by remember { mutableStateOf("weixin://") }
            var argsInput by remember { mutableStateOf("") }

            AlertDialogContent(title = { Text("启动微信内部 URL") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") })
                        TextField(
                            value = argsInput,
                            onValueChange = { argsInput = it },
                            label = { Text("参数 (可留空)") })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = { Button(onClick = {
                    onDismiss()
                    val args = if (argsInput.isBlank()) emptyList() else argsInput.split("\n")
                    methodOpenUrl.method.invoke(
                        // FIXME: getDeclaredConstructor() says no ctor exists?? but Unsafe works????
                        WeUnsafeApi.allocateInstance(methodOpenUrl.method.declaringClass),
                        *arrayOf(context, url, args.toTypedArray()))
                }) { Text("确定") } })
        }
    }

    private val methodOpenUrl by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodOpenUrl.find(dexKit) {
            searchPackages("com.tencent.mm.app.plugin")
            matcher {
                usingEqStrings("MicroMsg.MMURIJumpHandler", "openSpecificUI, context is null")
            }
        }
    }
}
