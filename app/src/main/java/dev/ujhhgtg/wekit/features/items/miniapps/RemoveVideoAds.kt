package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcesses
import org.json.JSONObject

object RemoveVideoAds : SwitchFeature() {

    override val technicalId = "移除视频广告"
    override val nameRes = R.string.feature_remove_video_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_video_ads_description

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass().reflekt()
            .firstMethod { name = "subscribeHandler" }
            .hookBefore {
                val type = args[0] as String? ?: ""
                val json = args[1] as String? ?: ""

                if (type == "onVideoTimeUpdate") {
                    val json = JSONObject(json)
                    json.put("position", 60)
                    json.put("duration", 1)
                    args[1] = json.toString()
                }
            }
    }
}
