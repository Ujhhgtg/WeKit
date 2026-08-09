package dev.ujhhgtg.wekit.features.items.beautify

import android.view.View
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "隐藏其他设备横幅",
    nameRes = "feature_hide_other_devices_banner_name",
    categoryIds = [FeatureCategoryIds.BEAUTIFY],
    descriptionRes = "feature_hide_other_devices_banner_description",
)
object HideOtherDevicesBanner : SwitchFeature(), IResolveDex {

    private val methodSetOtherOnlineBannerVisibility by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation.banner")
        matcher {
            paramTypes("int")
            returnType = "void"
            usingEqStrings(
                "com/tencent/mm/ui/conversation/banner/OtherOnlineBanner",
                "setVisibility"
            )
        }
    }

    override fun onEnable() {
        methodSetOtherOnlineBannerVisibility.hookBefore {
            if (args.isNotEmpty() && args[0] is Int) {
                args[0] = View.GONE
            }
        }
    }
}

