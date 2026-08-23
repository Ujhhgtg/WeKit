package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisablePat : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用拍一拍"
    override val nameRes = R.string.feature_disable_pat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_pat_description

    private val methodAvatarDoubleClick by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AvatarDoubleClickListener", "onDoubleClick: %s")
        }
    }

    override fun onEnable() {
        methodAvatarDoubleClick.hookBefore {
            result = true
        }
    }
}
