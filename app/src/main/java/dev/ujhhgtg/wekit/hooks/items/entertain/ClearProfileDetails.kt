package dev.ujhhgtg.wekit.hooks.items.entertain

import android.content.Context
import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.hooks.api.net.WePacketHelper
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "娱乐/清空资料信息", description = "清空当前用户的地区与性别等资料信息")
object ClearProfileDetails : ClickableHookItem() {

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清空资料信息") },
                text = { Text("确定清空吗？清空后你仍然可以重新选择资料信息") },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        val payload =
                            """{"1":{"1":1,"2":{"1":1,"2":{"1":91,"2":{"1":128,"2":{"1":""},"3":{"1":""},"4":0,"5":{"1":""},"6":{"1":""},"7":0,"8":0,"9":"","10":0,"11":"","12":"","13":"","14":1,"16":0,"17":0,"19":0,"20":0,"21":0,"22":0,"23":0,"24":"","25":0,"27":"","28":"","29":0,"30":0,"31":0,"33":0,"34":0,"36":0,"38":""}}}}}"""

                        WePacketHelper.sendCgi(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            payload
                        ) {
                            onSuccess { json, _ ->
                                WeLogger.i("WeProfileCleaner", "成功，回包: $json")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送成功, 响应结果:") },
                                        text = { Text(json) },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text("关闭") }
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
                                            TextButton(onClick = onDismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }

    override val noSwitchWidget: Boolean
        get() = true
}
