package moe.ouom.wekit.hooks.item.chat.msg

import android.app.Activity
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "聊天与消息/移除消息批量转发限制", desc = "移除消息多选目标的 9 个数量限制")
class RemoveMessageBatchForwardLimit : BaseSwitchFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) {
        "com.tencent.mm.ui.mvvm.MvvmContactListUI".toClass(classLoader)
            .hookBefore("onCreate") {
                val activity = it.thisObject as Activity
                activity.intent.putExtra("max_limit_num", 999)
            }
    }
}
