package moe.ouom.wekit.hooks.items.chat

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeStartActivityListener
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "联系人/移除消息批量转发限制", desc = "移除消息多选目标的 9 个数量限制")
object RemoveMessageBatchForwardLimit : BaseSwitchFunctionHookItem(), WeStartActivityListener.IStartActivityListener {
    private const val TAG = "RemoveMessageBatchForwardLimit"

    override fun entry(classLoader: ClassLoader) {
        WeStartActivityListener.addListener(this)
    }

    override fun unload(classLoader: ClassLoader) {
        super.unload(classLoader)
        WeStartActivityListener.removeListener(this)
    }

    override fun onStartActivity(
        hookParam: XC_MethodHook.MethodHookParam,
        intent: Intent
    ) {
        val className = intent.component?.className ?: return

        if (className == "com.tencent.mm.ui.mvvm.MvvmSelectContactUI"
            || className == "com.tencent.mm.ui.mvvm.MvvmContactListUI") {
            WeLogger.i(TAG, "removed batch forward limit for $className")
            intent.putExtra("max_limit_num", 999)
        }
    }
}
