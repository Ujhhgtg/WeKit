package dev.ujhhgtg.wekit.features.items.payment

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "允许领取私聊红包",
    nameRes = "feature_allow_private_chat_receive_outgoing_red_packets_name",
    categoryIds = [FeatureCategoryIds.PAYMENT],
    descriptionRes = "feature_allow_private_chat_receive_outgoing_red_packets_description",
)
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
