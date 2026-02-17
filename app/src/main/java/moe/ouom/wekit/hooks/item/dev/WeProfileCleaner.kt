package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import com.afollestad.materialdialogs.MaterialDialog
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.util.log.WeLogger

@HookItem(path = "娱乐功能/清空资料信息", desc = "点击清空你之前所选择的微信地区和性别等资料信息")
class WeProfileCleaner : BaseClickableFunctionHookItem() {
    override fun onClick(context: Context?) {
        context?.let {
            MaterialDialog(it)
                .title(text = "提示")
                .message(text = "确定清空吗？清空后你任然可以重新选择资料信息")
                .positiveButton(text = "清除") { dialog ->
                    val payload =
                        """{"1":{"1":1,"2":{"1":1,"2":{"1":91,"2":{"1":128,"2":{"1":""},"3":{"1":""},"4":0,"5":{"1":""},"6":{"1":""},"7":0,"8":0,"9":"","10":0,"11":"","12":"","13":"","14":1,"16":0,"17":0,"19":0,"20":0,"21":0,"22":0,"23":0,"24":"","25":0,"27":"","28":"","29":0,"30":0,"31":0,"33":0,"34":0,"36":0,"38":""}}}}}"""

                    WePkgHelper.INSTANCE?.sendCgi(
                        "/cgi-bin/micromsg-bin/oplog",
                        681, 0, 0,
                        jsonPayload = payload
                    ) {
                        onSuccess { json, bytes ->
                            WeLogger.i("WeProfileCleaner", "成功，回包: $json")
                            MaterialDialog(it)
                                .title(text = "提示")
                                .message(text = "微信响应了你的请求，你可能需要重启才能看到更改。\n服务器响应数据: $json")
                                .positiveButton(text = "我知道了") { dialog ->
                                    dialog.dismiss()
                                }
                                .show()
                        }

                        onFail { errType, errCode, errMsg ->
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

    override fun noSwitchWidget(): Boolean = true
}