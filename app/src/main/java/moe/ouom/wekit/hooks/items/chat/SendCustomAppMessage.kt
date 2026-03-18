package moe.ouom.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.material3.Text
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog

@SuppressLint("DiscouragedApi")
@HookItem(path = "聊天/发送卡片消息", desc = "长按 '发送' 按钮, 发送 XML 卡片消息")
object SendCustomAppMessage : SwitchHookItem() {
    // 实现逻辑在 WeChatFooterApi
    // TODO: move logic inside this hook item

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            dismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(dismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
