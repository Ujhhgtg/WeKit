package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "伪装宿主版本",
    nameRes = "feature_spoof_host_version_name",
    categoryIds = [FeatureCategoryIds.MINIAPPS],
    descriptionRes = "feature_spoof_host_version_description",
)
object SpoofHostVersion : SwitchFeature(), IResolveDex {

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
