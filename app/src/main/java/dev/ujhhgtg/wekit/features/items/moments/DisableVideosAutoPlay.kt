package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "禁止自动播放视频",
    nameRes = "feature_disable_videos_auto_play_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_disable_videos_auto_play_description",
)
object DisableVideosAutoPlay : SwitchFeature(), IResolveDex {

    private val methodCheckAutoPlay by dexMethod {
        matcher {
            usingEqStrings(
                "checkAutoPlay",
                "com.tencent.mm.plugin.sns.util.SnsAutoPlayUtil"
            )
        }
    }

    private val methodImproveAutoPlayInvoke by dexMethod {
        matcher {
            usingEqStrings(
                "invoke",
                $$"com.tencent.mm.plugin.sns.ui.improve.util.ImproveAutoPlayManager$autoPlay$2"
            )
        }
    }

    override fun onEnable() {
        methodCheckAutoPlay.hookBefore {
            result = false
        }
        methodImproveAutoPlayInvoke.hookBefore {
            result = false
        }
    }
}
