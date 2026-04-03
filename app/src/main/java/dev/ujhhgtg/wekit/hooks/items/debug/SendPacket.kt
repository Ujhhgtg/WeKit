package dev.ujhhgtg.wekit.hooks.items.debug

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.api.net.WePacketHelper
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.showToast
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "调试/发包调试", desc = "发送自定义数据包到微信服务器")
object SendPacket : ClickableHookItem() {
    private val TAG = nameOf(SendPacket)

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var uri by remember { mutableStateOf("/cgi-bin/micromsg-bin/oplog") }
            var cmdIdStr by remember { mutableStateOf("681") }
            var funcIdStr by remember { mutableStateOf("0") }
            var routeIdStr by remember { mutableStateOf("0") }
            var jsonPayloadStr by remember { mutableStateOf("{}") }

            AlertDialogContent(
                title = { Text("发包调试") },
                text = {
                    DefaultColumn {
                        TextField(
                            uri, onValueChange = { uri = it },
                            label = { Text("CGI 路径 (str)") })
                        TextField(
                            cmdIdStr, onValueChange = { cmdIdStr = it },
                            label = { Text("cmdId (int)") })
                        TextField(
                            funcIdStr, onValueChange = { funcIdStr = it },
                            label = { Text("funcId (int)") })
                        TextField(
                            routeIdStr, onValueChange = { routeIdStr = it },
                            label = { Text("routeId (int)") })
                        TextField(
                            jsonPayloadStr,
                            onValueChange = { jsonPayloadStr = it },
                            label = { Text("JSON 载荷 (str)") })
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uri = uri.trim()
                        val cmdId = cmdIdStr.toIntOrNull()
                        val funcId = funcIdStr.toIntOrNull()
                        val routeId = routeIdStr.toIntOrNull()
                        val payload = jsonPayloadStr.trim()

                        if (uri.isEmpty()) {
                            showToast(context, "URI 不能为空")
                            return@TextButton
                        }

                        if (cmdId == null || funcId == null || routeId == null) {
                            showToast(context, "cmdId, funcId 和 routeId 必须为整数")
                            return@TextButton
                        }

                        WePacketHelper.sendCgi(
                            uri,
                            cmdId,
                            funcId,
                            routeId,
                            payload
                        ) {
                            onSuccess { json, byteArray ->
                                WeLogger.i(TAG, "success: $json")
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送成功, 响应结果:") },
                                        text = { Text("json: $json\n\nbyteArray: ${byteArray?.size ?: 0} 字节") },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }
                            onFailure { type, code, msg ->
                                WeLogger.e(TAG, "失败: $type, $code, $msg")
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
