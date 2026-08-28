package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.bool

@Feature(
    id = "禁用消息折叠",
    nameRes = "feature_disable_message_collapsing_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_disable_message_collapsing_description",
)
object DisableMessageCollapsing : SwitchFeature(), IResolveDex {

    private val methodFoldMsg by dexMethod {
        matcher {
            usingStrings(".msgsource.sec_msg_node.clip-len")
            paramTypes(null, CharSequence::class.java, null, bool, null, null)
        }
    }

    override fun onEnable() {
        methodFoldMsg.hookBefore {
            result = null
        }
    }
}
