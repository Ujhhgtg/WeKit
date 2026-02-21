package moe.ouom.wekit.hooks.items.debug

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.utils.common.ToastUtils
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "调试/发包调试", desc = "发送自定义数据包到微信服务器")
object SendPacket : BaseClickableFunctionHookItem() {
    private const val TAG = "WePacketDebugger"

    override fun onClick(context: Context?) {
        showComposeDialog(context) { onDismiss ->
            var uri by remember { mutableStateOf("/cgi-bin/micromsg-bin/oplog") }
            var cmdIdStr by remember { mutableStateOf("681") }
            var funcIdStr by remember { mutableStateOf("0") }
            var routeIdStr by remember { mutableStateOf("0") }
            var jsonPayloadStr by remember { mutableStateOf("{}") }

            AlertDialog(onDismissRequest = onDismiss,
                title = { Text("发包调试") },
                text = {
                    Column {
                        TextField(uri, onValueChange = { uri = it },
                            label = { Text("CGI 路径 (str)") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        TextField(cmdIdStr, onValueChange = { cmdIdStr = it },
                            label = { Text("cmdId (int)") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        TextField(funcIdStr, onValueChange = { funcIdStr = it },
                            label = { Text("funcId (int)") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        TextField(routeIdStr, onValueChange = { routeIdStr = it },
                            label = { Text("routeId (int)") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        TextField(jsonPayloadStr, onValueChange = { jsonPayloadStr = it }, label = { Text("JSON 载荷 (str)") })
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
                            ToastUtils.showToast(context, "URI 不能为空")
                            return@TextButton
                        }

                        if (cmdId == null || funcId == null || routeId == null) {
                            ToastUtils.showToast(context, "cmdId, funcId 和 routeId 必须为整数")
                            return@TextButton
                        }

                        WePkgHelper.INSTANCE?.sendCgi(
                            uri,
                            cmdId,
                            funcId,
                            routeId,
                            payload
                        ) {
                            onSuccess { json, byteArray ->
                                WeLogger.i(TAG, "success: $json")
                                showComposeDialog(context) { onDismiss ->
                                    AlertDialog(onDismissRequest = onDismiss,
                                        title = { Text("发送成功, 响应结果:") },
                                        text = { Text("json: $json\n\nbyteArray: ${byteArray?.size ?: 0} 字节") },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }
                            onFail { type, code, msg ->
                                WeLogger.e(TAG, "失败: $type, $code, $msg")
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
                    }) { Text("确定") }
                })
        }
    }

    override fun noSwitchWidget(): Boolean = true
}