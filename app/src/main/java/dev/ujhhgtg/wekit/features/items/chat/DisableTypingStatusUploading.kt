package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.find
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableTypingStatusUploading : SwitchFeature(), IResolveDex {

    override val technicalId = "禁止上传正在输入状态"
    override val nameRes = R.string.feature_disable_typing_status_uploading_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_typing_status_uploading_description

    private val classMmTypingSendReq by dexClass()

    override fun onEnable() {
        if (!classMmTypingSendReq.isFound) return

        classMmTypingSendReq
            .reflekt()
            .firstMethod { name = "doScene" }
            .hookBefore {
                result = -1
            }
    }

    override fun resolveDex() {
        classMmTypingSendReq.find(allowFailure = true) {
            searchPackages("com.tencent.mm.modelsimple")
            usingEqString(
                "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
            )
            usingEqString("autoAuth")
        }
    }
}
