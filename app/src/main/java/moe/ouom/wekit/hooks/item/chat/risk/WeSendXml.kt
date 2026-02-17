package moe.ouom.wekit.hooks.item.chat.risk

import android.annotation.SuppressLint
import android.content.Context
import com.afollestad.materialdialogs.MaterialDialog
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@SuppressLint("DiscouragedApi")
@HookItem(path = "聊天与消息/发送 AppMsg (XML)", desc = "长按'发送'按钮，自动发送卡片消息")
class WeSendXml : BaseSwitchFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) {
        // 实现逻辑在 WeChatFooterApi
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            MaterialDialog(context)
                .title(text = "警告")
                .message(text = "此功能可能导致账号异常，确定要启用吗?")
                .positiveButton(text = "确定") { dialog ->
                    applyToggle(true)
                }
                .negativeButton(text = "取消") { dialog ->
                    dialog.dismiss()
                }
                .show()

            // 返回 false 阻止自动切换
            return false
        }

        // 禁用功能时直接允许
        return true
    }
}
