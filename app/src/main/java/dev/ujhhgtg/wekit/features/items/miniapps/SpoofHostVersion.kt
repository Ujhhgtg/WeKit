package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object SpoofHostVersion : SwitchFeature(), IResolveDex {

    override val technicalId = "伪装宿主版本"
    override val nameRes = R.string.feature_spoof_host_version_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_spoof_host_version_description

    override fun onEnable() {
        ctorCgiLaunchWxaAppFunc1122.hookBefore {
            args[6] = 9999
        }
    }

    private val ctorCgiLaunchWxaAppFunc1122 by dexConstructor {
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrand.CgiLaunchWxaApp|func:1122",
                "<init> cgiHash[%d], username[%s] appId[%s] sync[%b] sessionId[%s] instanceId[%s] libVersion[%d], source:%s, launchMode:%d, migrate:%b, fallback:%b"
            )
        }
    }
}
