package dev.ujhhgtg.wekit.hooks.items.profile

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.hooks.api.net.WePacketHelper
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.logging.WeLogger

@HookItem(path = "个人资料/设置微信昵称", desc = "通过发包来更灵活的设置微信昵称")
object SetProfileNickname : ClickableHookItem() {

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var nickname by remember { mutableStateOf("") }

            AlertDialogContent(
                title = { Text("设置微信昵称") },
                text = {
                    TextField(
                        label = { Text("新的昵称") },
                        value = nickname, onValueChange = { nickname = it }, singleLine = false
                    )
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        val payload = """{"1":{"1":1,"2":{"1":64,"2":{"1":16,"2":{"1":1,"2":"${
                            escapeJsonString(nickname)
                        }"}}}}}"""

                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            jsonPayload = payload
                        ) {
                            onSuccess { json, _ ->
                                WeLogger.i("WeProfileNameSetter", "成功，回包: $json")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送成功, 响应结果:") },
                                        text = { Text(json) },
                                        confirmButton = {
                                            TextButton(onClick = dismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }

                            onFailure { type, code, msg ->
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送失败, 响应结果:") },
                                        text = { Text("type: $type, code: $code, msg: $msg") },
                                        confirmButton = {
                                            TextButton(onClick = dismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }
                        }
                        dismiss()
                    }) { Text("确定") }
                })
        }
    }

    private fun escapeJsonString(input: String): String {
        return input.replace("\"", "\\\"")
    }

    override val noSwitchWidget: Boolean
        get() = true
}
