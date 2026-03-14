package moe.ouom.wekit.hooks.items.contacts

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeStartActivityApi
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "联系人与群组/移除消息批量转发限制", desc = "移除消息多选目标的 9 个数量限制")
object RemoveMessageBatchForwardLimit : SwitchHookItem(),
    WeStartActivityApi.IStartActivityListener {

    private val TAG = nameof(RemoveMessageBatchForwardLimit)

    override fun onLoad() {
        WeStartActivityApi.addListener(this)
    }

    override fun onUnload() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(
        param: XC_MethodHook.MethodHookParam,
        intent: Intent
    ) {
        val className = intent.component?.className ?: return

        if (className == "com.tencent.mm.ui.mvvm.MvvmSelectContactUI"
            || className == "com.tencent.mm.ui.mvvm.MvvmContactListUI"
        ) {
            WeLogger.i(TAG, "removed batch forward limit for $className")
            intent.putExtra("max_limit_num", 999)
        }
    }
}