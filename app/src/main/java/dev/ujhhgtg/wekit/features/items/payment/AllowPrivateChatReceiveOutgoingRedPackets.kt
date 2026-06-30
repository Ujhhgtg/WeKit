package dev.ujhhgtg.wekit.features.items.payment

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "允许领取私聊红包", categories = ["红包与支付"], description = "允许打开私聊中自己发出的红包\n可能导致发送红包提示「请求不成功」")
object AllowPrivateChatReceiveOutgoingRedPackets : SwitchFeature() {

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                activity.intent.putExtra("key_type", 1)
            }
        }
    }
}
