package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "娱乐功能/设置微信昵称", desc = "通过发包来更灵活的设置微信昵称")
class WeProfileNameSetter : BaseClickableFunctionHookItem() {

    override fun onClick(context: Context?) {
        showComposeDialog(context) { onDismiss ->
            var nickname by remember { mutableStateOf("") }

            AlertDialog(onDismissRequest = onDismiss,
                title = { Text("设置微信昵称") },
                text = { TextField(label = { Text("新的昵称") },
                    value = nickname, onValueChange = { nickname = it }, singleLine = false) },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = { TextButton(onClick = {
                    val payload = """{"1":{"1":1,"2":{"1":64,"2":{"1":16,"2":{"1":1,"2":"${
                        escapeJsonString(nickname)
                    }"}}}}}"""

                    WePkgHelper.INSTANCE?.sendCgi(
                        "/cgi-bin/micromsg-bin/oplog",
                        681, 0, 0,
                        jsonPayload = payload
                    ) {
                        onSuccess { json, _ ->
                            WeLogger.i("WeProfileNameSetter", "成功，回包: $json")
                            showComposeDialog(context) { onDismiss ->
                                AlertDialog(onDismissRequest = onDismiss,
                                    title = { Text("发送成功, 响应结果:") },
                                    text = { Text(json) },
                                    confirmButton = {
                                        TextButton(onClick = onDismiss) { Text("关闭") }
                                    }
                                )
                            }
                        }

                        onFail { type, code, msg ->
                            showComposeDialog(context) { onDismiss ->
                                AlertDialog(onDismissRequest = onDismiss,
                                    title = { Text("发送失败, 响应结果:") },
                                    text = { Text("type: $type, code: $code, msg: $msg") },
                                    confirmButton = {
                                        TextButton(onClick = onDismiss) { Text("关闭") }
                                    }
                                )
                            }
                        }
                    }
                    onDismiss()
                }) { Text("确定") } })
        }
    }

    private fun escapeJsonString(input: String): String {
        return input.replace("\"", "\\\"")
    }

    override fun noSwitchWidget(): Boolean = true
}