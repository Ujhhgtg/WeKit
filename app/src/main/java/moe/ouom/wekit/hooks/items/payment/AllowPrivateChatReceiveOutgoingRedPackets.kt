package moe.ouom.wekit.hooks.items.payment

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeStartActivityApi
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "红包与支付/允许领取私聊红包", desc = "允许打开私聊中自己发出的红包")
object AllowPrivateChatReceiveOutgoingRedPackets : SwitchHookItem(),
    WeStartActivityApi.IStartActivityListener {

    private val TAG = nameof(AllowPrivateChatReceiveOutgoingRedPackets)

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

        if (className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI"
            || className == "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI"
        ) {
            WeLogger.i(TAG, "set key_type to 1 for $className")
            intent.putExtra("key_type", 1)
        }
    }
}