package dev.ujhhgtg.wekit.features.items.moments

import com.tencent.mm.plugin.sns.storage.ADInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

object RemoveMomentsAds : SwitchFeature() {

    override val technicalId = "拦截朋友圈广告"
    override val nameRes = R.string.feature_remove_moments_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_remove_moments_ads_description

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
