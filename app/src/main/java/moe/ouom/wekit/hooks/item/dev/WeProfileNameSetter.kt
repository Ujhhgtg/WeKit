package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.util.log.WeLogger

@HookItem(path = "娱乐功能/设置微信昵称", desc = "通过发包来更灵活的设置微信昵称")
class WeProfileNameSetter : BaseClickableFunctionHookItem() {

    override fun onClick(context: Context?) {
        context?.let {
            MaterialDialog(context)
                .title(text = "设置新的微信昵称")
                .input(
                    hint = "请输入新的微信昵称",
                    allowEmpty = true,
                    waitForPositiveButton = true
                ) { _, text ->
                    val payload = """{"1":{"1":1,"2":{"1":64,"2":{"1":16,"2":{"1":1,"2":"${
                        escapeJsonString(text.toString())
                    }"}}}}}"""

                    WePkgHelper.INSTANCE?.sendCgi(
                        "/cgi-bin/micromsg-bin/oplog",
                        681, 0, 0,
                        jsonPayload = payload
                    ) {
                        onSuccess { json, bytes ->
                            WeLogger.i("WeProfileNameSetter", "成功，回包: $json")
                            MaterialDialog(it)
                                .title(text = "提示")
                                .message(text = "微信响应了你的请求，你可能需要重启才能看到更改。\n服务器响应数据: $json")
                                .positiveButton(text = "我知道了") { dialog ->
                                    dialog.dismiss()
                                }
                                .show()
                        }

                        onFail { errType, errCode, errMsg ->
                            WeLogger.e(
                                "WeProfileNameSetter",
                                "失败: type=$errType, code=$errCode, msg=$errMsg"
                            )
                            MaterialDialog(it)
                                .title(text = "出错了！")
                                .message(text = "微信拒绝了你的请求！\n错误: $errType, $errCode, $errMsg")
                                .positiveButton(text = "我知道了") { dialog ->
                                    dialog.dismiss()
                                }
                                .show()
                        }
                    }
                }
                .negativeButton(text = "取消") { dialog ->
                    dialog.dismiss()
                }
                .show()

        }
    }


    private fun escapeJsonString(input: String): String {
        return input.replace("\"", "\\\"")
    }

    override fun noSwitchWidget(): Boolean = true
}