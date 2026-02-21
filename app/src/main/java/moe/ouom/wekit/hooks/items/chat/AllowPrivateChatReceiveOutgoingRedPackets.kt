package moe.ouom.wekit.hooks.items.chat

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeStartActivityListener
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "聊天与消息/红包私聊领取", desc = "允许打开私聊中自己发出的红包")
object AllowPrivateChatReceiveOutgoingRedPackets : BaseSwitchFunctionHookItem(), WeStartActivityListener.IStartActivityListener {
    private const val TAG = "AllowPrivateChatReceiveOutgoingRedPackets"

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

        if (className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI"
            || className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI") {
            WeLogger.i(TAG, "set key_type to 1 for $className")
            intent.putExtra("key_type", 1)
        }
    }
}