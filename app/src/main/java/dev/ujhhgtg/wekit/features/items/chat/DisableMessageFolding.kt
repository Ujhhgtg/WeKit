package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableMessageFolding : SwitchFeature(), IResolveDex {
    override val technicalId = "禁用消息折叠"
    override val nameRes = R.string.feature_disable_message_collapsing_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_message_collapsing_description

    private val methodFoldMsg by dexMethod {
        paramTypes(null, "java.lang.CharSequence", null, "boolean", null, null)
        usingStrings(".msgsource.sec_msg_node.clip-len")
    }

    override fun onEnable() {
        methodFoldMsg.hookBefore {
            result = null
        }
    }
}
