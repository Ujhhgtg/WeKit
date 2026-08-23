package dev.ujhhgtg.wekit.features.items.beautify

import android.view.View
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object HideOtherDevicesBanner : SwitchFeature(), IResolveDex {

    override val technicalId = "隐藏其他设备横幅"
    override val nameRes = R.string.feature_hide_other_devices_banner_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_hide_other_devices_banner_description

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

