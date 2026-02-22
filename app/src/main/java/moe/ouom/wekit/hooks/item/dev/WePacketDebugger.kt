package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.input.input
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.util.common.Toasts
import moe.ouom.wekit.util.log.WeLogger

@HookItem(path = "开发者选项/发包调试", desc = "发送自定义数据包到微信服务器")
class WePacketDebugger : BaseClickableFunctionHookItem() {

    override fun onClick(context: Context?) {
        context?.let {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = 16
                setPadding(padding, padding, padding, padding)
            }

            fun createEditText(hintText: String, defaultText: String = ""): EditText {
                return EditText(context).apply {
                    hint = hintText
                    setText(defaultText)
                    textSize = 14f
                    isSingleLine = true
                }
            }

            val edtUri = createEditText("CGI 路径", "/cgi-bin/micromsg-bin/oplog")
            val edtCmdId = createEditText("CmdId (Int)", "681")
            val edtFuncId = createEditText("FuncId (Int)", "0")
            val edtRouteId = createEditText("routeId (Int)", "0")
            val edtPayload = createEditText("JSON Payload", "{}").apply {
                isSingleLine = false
            }

            layout.addView(edtUri)
            layout.addView(edtCmdId)
            layout.addView(edtFuncId)
            layout.addView(edtRouteId)
            layout.addView(edtPayload)

            MaterialDialog(context).show {
                title(text = "发包调试")
                customView(view = layout, scrollable = true)

                positiveButton(text = "发送") { dialog ->
                    val uri = edtUri.text.toString().trim()
                    val cmdId = edtCmdId.text.toString().toIntOrNull() ?: 0
                    val funcId = edtFuncId.text.toString().toIntOrNull() ?: 0
                    val routeId = edtRouteId.text.toString().toIntOrNull() ?: 0
                    val payload = edtPayload.text.toString()

                    if (uri.isEmpty()) {
                        Toasts.showToast(context, "URI 不能为空")
                        return@positiveButton
                    }

                    // 执行发包逻辑
                    WePkgHelper.INSTANCE?.sendCgi(
                        uri,
                        cmdId,
                        funcId,
                        routeId,
                        jsonPayload = payload
                    ) {
                        onSuccess { json, _ ->
                            WeLogger.i("WePacketDebugger", "成功: $json")
                            showResult(context, "发送成功", json)
                        }
                        onFail { type, code, msg ->
                            WeLogger.e("WePacketDebugger", "失败: $type, $code, $msg")
                            showResult(context, "发送失败", "Type: $type\nCode: $code\nMsg: $msg")
                        }
                    }
                }
                negativeButton(text = "取消")
            }

        }
    }

    private fun showResult(context: Context, title: String, content: String) {
        MaterialDialog(context).show {
            title(text = title)
            message(text = content)
            positiveButton(text = "确定")
        }
    }

    override fun noSwitchWidget(): Boolean = true
}