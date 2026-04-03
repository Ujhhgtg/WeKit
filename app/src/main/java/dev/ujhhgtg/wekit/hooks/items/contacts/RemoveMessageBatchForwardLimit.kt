package dev.ujhhgtg.wekit.hooks.items.contacts

import android.app.Activity
import com.highcapable.kavaref.extension.toClassOrNull
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "联系人与群组/移除消息批量转发限制", desc = "移除消息多选目标的 9 个数量限制")
object RemoveMessageBatchForwardLimit : SwitchHookItem() {

    private val TAG = nameOf(RemoveMessageBatchForwardLimit)

    override fun onEnable() {
        listOf(
            "com.tencent.mm.ui.mvvm.MvvmSelectContactUI",
            "com.tencent.mm.ui.mvvm.MvvmContactListUI"
        ).forEach {
            it.toClassOrNull()?.hookBeforeOnCreate { param ->
                val activity = param.thisObject as Activity
                activity.intent.putExtra("max_limit_num", 999)
                WeLogger.i(TAG, "removed batch forward limit for $it")
            }
        }
    }
}
