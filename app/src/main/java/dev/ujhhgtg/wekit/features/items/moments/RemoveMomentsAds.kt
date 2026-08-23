package dev.ujhhgtg.wekit.features.items.moments

import com.tencent.mm.plugin.sns.storage.ADInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "拦截朋友圈广告",
    nameRes = "feature_remove_moments_ads_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_remove_moments_ads_description",
)
object RemoveMomentsAds : SwitchFeature() {

    private const val TAG = "RemoveMomentsAds"

    override fun onEnable() {
        ADInfo::class.reflekt()
            .firstConstructor {
                parameters(String::class)
            }
            .hookBefore {
                WeLogger.i(TAG, "blocked ad")
                result = null
            }
    }
}
